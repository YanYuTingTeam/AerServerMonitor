package com.aermini.servermonitor.post;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.database.dao.WebhookLogDAO;
import com.aermini.servermonitor.event.EventType;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class PostManager {

    private static final String QUEUE_KEY = "webhook:queue";
    private static final String PROCESSING_KEY = "webhook:processing";
    private static final String DELAY_KEY = "webhook:delay";
    private static final String DEAD_KEY = "webhook:dead";
    private static final String DONE_KEY = "webhook:done:";
    private static final int DONE_TTL_SECONDS = 86400;
    private static final String DELAY_SCRIPT =
            "local items=redis.call('ZRANGEBYSCORE',KEYS[1],0,ARGV[1],'LIMIT',0,50)\n" +
            "for _,item in ipairs(items) do\n" +
            "redis.call('ZREM',KEYS[1],item)\n" +
            "redis.call('LPUSH',KEYS[2],item)\n" +
            "end\n" +
            "return #items";

    private final PluginConfig config;
    private final WebhookLogDAO webhookLogDAO;
    private final LogManager logManager;
    private final Logger logger;
    private final RedisManager redis;
    private final ExecutorService postPool;
    private final Map<String, String> postUrls;
    private final List<Integer> retryIntervals;

    private volatile boolean running = false;
    private volatile boolean requeueDone = false;
    private Thread[] workers;
    private Thread scheduler;

    public PostManager(PluginConfig config, WebhookLogDAO webhookLogDAO, LogManager logManager, Logger logger,
                       RedisManager redis) {
        this.config = config;
        this.webhookLogDAO = webhookLogDAO;
        this.logManager = logManager;
        this.logger = logger;
        this.redis = redis;
        this.postPool = new ThreadPoolExecutor(
                config.getAsyncPostPoolSize(),
                config.getAsyncPostPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getAsyncPostPoolQueue()),
                r -> { Thread t = new Thread(r, "AerMonitor-Webhook"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        this.postUrls = config.getPostUrls();
        this.retryIntervals = config.getAsyncPostRetryIntervals();
    }

    public void start() {
        if (running) return;
        running = true;
        if (!config.isRedisFeatureEnabled("webhook-queue")) return;

        if (useRedisQueue()) {
            try {
                requeueDone = requeueProcessing();
            } catch (Exception e) {
                logger.warning("Webhook requeue failed: " + e.getMessage());
            }
        }

        workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            Thread t = new Thread(this::runWorker, "AerMonitor-WebhookQueue-" + (i + 1));
            t.setDaemon(true);
            workers[i] = t;
            t.start();
        }

        scheduler = new Thread(this::runScheduler, "AerMonitor-WebhookDelay");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    public void shutdown() {
        running = false;
        if (workers != null) {
            for (Thread t : workers) {
                if (t != null) t.interrupt();
            }
        }
        if (scheduler != null) scheduler.interrupt();
        postPool.shutdown();
        try {
            postPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
    }

    public void post(EventType eventType, String body) {
        String url = postUrls.get(eventType.getKey());
        if (url == null || url.isEmpty()) return;
        String payload = body == null ? "" : body;

        if (useRedisQueue()) {
            postPool.execute(() -> enqueueRedis(eventType, url, payload));
            return;
        }

        postPool.execute(() -> doPost(url, payload, eventType.getKey(), 1));
    }

    private void enqueueRedis(EventType eventType, String url, String payload) {
        String eventId = extractEventId(payload);
        String doneKey = redis.key(DONE_KEY + eventId);
        Boolean dedup = redis.setNxOrNull(doneKey, "1", DONE_TTL_SECONDS);
        if (Boolean.FALSE.equals(dedup)) return;
        if (Boolean.TRUE.equals(dedup)) {
            Long queueSize = redis.llen(redis.key(QUEUE_KEY));
            if (queueSize != null && queueSize >= config.getAsyncWebhookQueueMax()) {
                redis.del(doneKey);
                logger.warning("Webhook queue full, dropped event " + eventId);
                return;
            }
            try {
                JsonObject job = new JsonObject();
                job.addProperty("eventid", eventId);
                job.addProperty("eventType", eventType.getKey());
                job.addProperty("url", url);
                job.addProperty("body", payload);
                job.addProperty("attempt", 0);
                if (redis.lpush(redis.key(QUEUE_KEY), job.toString())) return;
            } catch (Exception e) {
                logger.warning("Webhook enqueue failed: " + e.getMessage());
            }
            redis.del(doneKey);
        }
        doPost(url, payload, eventType.getKey(), 1);
    }

    private void runWorker() {
        while (running) {
            if (!useRedisQueue() || !requeueDone) {
                sleepQuiet(100L);
                continue;
            }

            String jobJson;
            try {
                jobJson = redis.lmove(redis.key(QUEUE_KEY), redis.key(PROCESSING_KEY), false, false);
            } catch (Exception e) {
                sleepQuiet(500L);
                continue;
            }
            if (jobJson == null) {
                sleepQuiet(100L);
                continue;
            }

            try {
                handleJob(jobJson);
            } catch (Exception e) {
                Long removed = redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
                if (removed != null && removed > 0) {
                    redis.lpush(redis.key(QUEUE_KEY), jobJson);
                }
                logger.warning("Webhook job failed: " + e.getMessage());
            }
        }
    }

    private void handleJob(String jobJson) {
        JsonObject job;
        try {
            job = JsonUtil.parse(jobJson);
        } catch (Exception e) {
            redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
            redis.lpush(redis.key(DEAD_KEY), jobJson);
            logger.warning("Invalid webhook job moved to dead.");
            return;
        }

        String eventId = getString(job, "eventid");
        String eventType = getString(job, "eventType");
        String url = getString(job, "url");
        String body = getString(job, "body");
        int attempt = getInt(job, "attempt");

        if (url.isEmpty() || !postUrls.containsValue(url)) {
            moveToDead(jobJson, eventId, eventType, url, body, attempt);
            return;
        }

        String sentKey = redis.key("webhook:sent:" + eventId);
        if (redis.exists(sentKey)) {
            redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
            return;
        }

        if (doPostOnce(url, body)) {
            redis.set(sentKey, "1", DONE_TTL_SECONDS);
            redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
            logManager.log("[POST] " + eventType + " -> " + url + " ok (attempt " + (attempt + 1) + ")");
            return;
        }

        attempt++;
        if (attempt >= config.getAsyncPostRetryMax()) {
            moveToDead(jobJson, eventId, eventType, url, body, attempt);
            return;
        }

        redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
        job.addProperty("attempt", attempt);
        long nextAt = System.currentTimeMillis() + retryDelay(attempt);
        redis.zadd(redis.key(DELAY_KEY), nextAt, job.toString());
        logManager.log("[POST] " + eventType + " -> " + url + " failed, retry at " + nextAt + " (attempt " + attempt + ")");
    }

    private void moveToDead(String jobJson, String eventId, String eventType, String url, String body, int attempt) {
        redis.lrem(redis.key(PROCESSING_KEY), 1, jobJson);
        redis.lpush(redis.key(DEAD_KEY), jobJson);
        redis.ltrim(redis.key(DEAD_KEY), 0, 499);
        redis.del(redis.key(DONE_KEY + eventId));
        webhookLogDAO.insert(eventId, eventType, url, body);
        logManager.log("[POST] " + eventType + " -> " + url + " DEAD after " + attempt + " attempts.");
    }

    private void runScheduler() {
        while (running) {
            try {
                if (!requeueDone && useRedisQueue()) {
                    requeueDone = requeueProcessing();
                }
                if (useRedisQueue()) {
                    if (redis.setNx(redis.key("webhook:scheduler:lock"), "1", 3)) {
                        Object moved = redis.eval(DELAY_SCRIPT,
                                Arrays.asList(redis.key(DELAY_KEY), redis.key(QUEUE_KEY)),
                                Collections.singletonList(String.valueOf(System.currentTimeMillis())));
                        if (moved == null) {
                            fallbackMoveDue();
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Webhook delay scheduler failed: " + e.getMessage());
            }
            sleepQuiet(1000L);
        }
    }

    private void fallbackMoveDue() {
        long now = System.currentTimeMillis();
        List<String> due = redis.zrangeByScore(redis.key(DELAY_KEY), 0, now, 0, 50);
        for (String job : due) {
            Long removed = redis.zrem(redis.key(DELAY_KEY), job);
            if (removed != null && removed > 0) {
                redis.lpush(redis.key(QUEUE_KEY), job);
            }
        }
    }

    private boolean requeueProcessing() {
        String processing = redis.key(PROCESSING_KEY);
        String queue = redis.key(QUEUE_KEY);
        while (true) {
            String jobJson = redis.lmove(processing, queue, false, false);
            if (jobJson == null) break;
        }
        Long remaining = redis.llen(processing);
        return remaining != null && remaining == 0;
    }

    private boolean doPostOnce(String url, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void doPost(String url, String body, String eventType, int attempt) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            conn.disconnect();

            if (status >= 200 && status < 300) {
                logManager.log("[POST] " + eventType + " -> " + url + " ok (attempt " + attempt + ")");
            } else {
                logManager.log("[POST] " + eventType + " -> " + url + " failed (status " + status + ", attempt " + attempt + ")");
                scheduleRetry(url, body, eventType, attempt);
            }
        } catch (Exception e) {
            logManager.log("[POST] " + eventType + " -> " + url + " error: " + e.getMessage() + " (attempt " + attempt + ")");
            scheduleRetry(url, body, eventType, attempt);
        }
    }

    private void scheduleRetry(String url, String body, String eventType, int attempt) {
        if (attempt >= config.getAsyncPostRetryMax()) {
            logManager.log("[POST] " + eventType + " -> " + url + " DEAD after " + attempt + " attempts.");
            return;
        }

        int delay = attempt <= retryIntervals.size() ? retryIntervals.get(attempt - 1) : retryIntervals.get(retryIntervals.size() - 1);

        postPool.execute(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            doPost(url, body, eventType, attempt + 1);
        });
    }

    private boolean useRedisQueue() {
        return redis.isAvailable() && config.isRedisFeatureEnabled("webhook-queue");
    }

    private String extractEventId(String body) {
        try {
            JsonObject obj = JsonUtil.parse(body);
            if (obj != null && obj.has("eventid") && !obj.get("eventid").isJsonNull()) {
                String eventId = obj.get("eventid").getAsString();
                if (eventId != null && !eventId.isEmpty()) return eventId;
            }
        } catch (Exception ignored) {}
        return UUID.randomUUID().toString();
    }

    private int retryDelay(int attempt) {
        if (retryIntervals == null || retryIntervals.isEmpty()) return 1000;
        int index = Math.max(attempt - 1, 0);
        if (index >= retryIntervals.size()) index = retryIntervals.size() - 1;
        return retryIntervals.get(index);
    }

    private String getString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) return "";
        try {
            return obj.get(field).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private int getInt(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) return 0;
        try {
            return obj.get(field).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
