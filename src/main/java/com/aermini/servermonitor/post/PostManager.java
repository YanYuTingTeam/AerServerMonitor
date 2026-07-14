package com.aermini.servermonitor.post;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.WebhookLogDAO;
import com.aermini.servermonitor.event.EventType;
import com.aermini.servermonitor.log.LogManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class PostManager {

    private final PluginConfig config;
    private final WebhookLogDAO webhookLogDAO;
    private final LogManager logManager;
    private final Logger logger;
    private final ExecutorService postPool;
    private final Map<String, String> postUrls;
    private final List<Integer> retryIntervals;

    public PostManager(PluginConfig config, WebhookLogDAO webhookLogDAO, LogManager logManager, Logger logger) {
        this.config = config;
        this.webhookLogDAO = webhookLogDAO;
        this.logManager = logManager;
        this.logger = logger;
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

    public void post(EventType eventType, String body) {
        String url = postUrls.get(eventType.getKey());
        if (url == null || url.isEmpty()) return;

        postPool.execute(() -> doPost(url, body, eventType.getKey(), 1));
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

    public void shutdown() {
        postPool.shutdown();
        try { postPool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
