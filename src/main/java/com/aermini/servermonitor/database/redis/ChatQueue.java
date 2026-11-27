package com.aermini.servermonitor.database.redis;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.util.JsonUtil;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class ChatQueue {

    private static final int LOCAL_QUEUE_SIZE = 10000;
    private static final int RECENT_MAX = 200;
    private static final int RECENT_TTL_SECONDS = 604800;

    private final PluginConfig config;
    private final RedisManager redis;
    private final ChatDAO chatDAO;
    private final Logger logger;
    private final LinkedBlockingQueue<String> localQueue = new LinkedBlockingQueue<>(LOCAL_QUEUE_SIZE);

    private volatile boolean running = false;
    private volatile boolean requeueDone = false;
    private Thread worker;

    public ChatQueue(PluginConfig config, RedisManager redis, ChatDAO chatDAO, Logger logger) {
        this.config = config;
        this.redis = redis;
        this.chatDAO = chatDAO;
        this.logger = logger;
    }

    public void start() {
        if (running) return;
        running = true;
        tryRequeue();
        worker = new Thread(() -> {
            while (running) {
                if (!requeueDone) tryRequeue();
                try {
                    Thread.sleep(Math.max(10L, config.getAsyncChatBatchInterval()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    processBatch();
                } catch (Exception e) {
                    logger.warning("Chat queue batch failed: " + e.getMessage());
                }
            }
            flushLocal();
        }, "AerMonitor-ChatQueue");
        worker.setDaemon(true);
        worker.start();
    }

    public void shutdown() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushLocal();
    }

    public void offer(String name, String uuid, String ip, String server, long time, String msgid, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name == null ? "" : name);
        obj.addProperty("uuid", uuid == null ? "" : uuid);
        obj.addProperty("ip", ip == null ? "" : ip);
        obj.addProperty("server", server == null ? "" : server);
        obj.addProperty("time", time);
        obj.addProperty("msgid", msgid == null ? "" : msgid);
        obj.addProperty("content", content == null ? "" : content);
        String json = obj.toString();
        if (!localQueue.offer(json)) {
            logger.warning("Local chat queue is full, message dropped.");
        }
    }

    private void processBatch() {
        if (!running) return;
        int batchSize = config.getAsyncChatBatchSize();
        if (batchSize <= 0) batchSize = 50;

        List<String> localItems = new ArrayList<>();
        localQueue.drainTo(localItems, batchSize);

        List<String> redisItems = new ArrayList<>();
        if (useRedis()) {
            for (int i = 0; i < batchSize; i++) {
                String json = redis.lmove(redis.key("chat:queue"), redis.key("chat:processing"), false, false);
                if (json == null) break;
                redisItems.add(json);
            }
        }

        int remain = batchSize - redisItems.size();
        if (remain > 0) {
            localQueue.drainTo(localItems, remain);
        }
        if (redisItems.isEmpty() && localItems.isEmpty()) return;

        List<String> acceptedRedis = new ArrayList<>();
        List<Object[]> records = new ArrayList<>();

        for (String json : redisItems) {
            JsonObject obj = parse(json);
            if (obj == null) {
                redis.lrem(redis.key("chat:processing"), 1, json);
                logger.warning("Invalid chat queue item dropped.");
                continue;
            }
            Object[] record = toRecord(obj);
            if (record == null) {
                redis.lrem(redis.key("chat:processing"), 1, json);
                continue;
            }
            acceptedRedis.add(json);
            records.add(record);
        }

        for (String json : localItems) {
            JsonObject obj = parse(json);
            if (obj == null) continue;
            Object[] record = toRecord(obj);
            if (record != null) records.add(record);
        }

        if (records.isEmpty()) return;

        boolean inserted;
        try {
            inserted = chatDAO.insertChatBatch(records);
        } catch (Exception e) {
            inserted = false;
        }
        if (!inserted) {
            for (String json : acceptedRedis) {
                redis.lrem(redis.key("chat:processing"), 1, json);
                redis.lpush(redis.key("chat:queue"), json);
            }
            for (String json : localItems) {
                if (!localQueue.offer(json)) {
                    logger.warning("Local chat queue is full, retry item dropped.");
                }
            }
            logger.warning("Chat batch insert failed, items requeued.");
            return;
        }

        for (String json : acceptedRedis) {
            redis.lrem(redis.key("chat:processing"), 1, json);
            redis.lpush(redis.key("chat:recent"), json);
        }
        if (useRedis()) {
            for (String json : localItems) {
                redis.lpush(redis.key("chat:recent"), json);
            }
        }
        if (!acceptedRedis.isEmpty() || !localItems.isEmpty()) {
            redis.ltrim(redis.key("chat:recent"), 0, RECENT_MAX - 1);
            redis.expire(redis.key("chat:recent"), RECENT_TTL_SECONDS);
        }
    }

    private void flushLocal() {
        if (localQueue.isEmpty()) return;
        int batchSize = config.getAsyncChatBatchSize();
        if (batchSize <= 0) batchSize = 50;
        while (!localQueue.isEmpty()) {
            List<String> items = new ArrayList<>();
            localQueue.drainTo(items, batchSize);
            if (items.isEmpty()) break;
            List<Object[]> records = new ArrayList<>();
            for (String json : items) {
                JsonObject obj = parse(json);
                if (obj == null) continue;
                Object[] record = toRecord(obj);
                if (record != null) records.add(record);
            }
            if (records.isEmpty()) continue;
            boolean inserted;
            try {
                inserted = chatDAO.insertChatBatch(records);
            } catch (Exception e) {
                inserted = false;
            }
            if (!inserted) {
                for (String json : items) {
                    localQueue.offer(json);
                }
                logger.warning("Chat shutdown flush failed, items kept local.");
                break;
            }
        }
    }

    private void tryRequeue() {
        if (!useRedis()) return;
        requeueDone = requeueProcessing();
    }

    private boolean requeueProcessing() {
        String processing = redis.key("chat:processing");
        String queue = redis.key("chat:queue");
        while (true) {
            String json = redis.lmove(processing, queue, false, false);
            if (json == null) break;
        }
        Long remaining = redis.llen(processing);
        return remaining != null && remaining == 0;
    }

    private Object[] toRecord(JsonObject obj) {
        String uuid = getString(obj, "uuid");
        String msgid = getString(obj, "msgid");
        if (uuid.isEmpty() || msgid.isEmpty()) return null;
        return new Object[]{
                getString(obj, "name"),
                uuid,
                getString(obj, "ip"),
                getString(obj, "server"),
                getLong(obj, "time"),
                msgid,
                getString(obj, "content")
        };
    }

    private JsonObject parse(String json) {
        try {
            return JsonUtil.parse(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) return "";
        try {
            return obj.get(field).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private long getLong(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) return 0L;
        try {
            return obj.get(field).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean useRedis() {
        return redis.isAvailable() && config.isRedisFeatureEnabled("chat-queue");
    }
}
