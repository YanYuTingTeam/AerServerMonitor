package com.aermini.servermonitor.http.ws;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class WsBatcher {

    private final WebSocketServer wsServer;
    private final Logger logger;
    private final long flushIntervalMs;

    private final ConcurrentLinkedQueue<JsonObject> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile Thread flusher;
    private volatile boolean running = false;

    private static final int MAX_EVENTS_PER_BATCH = 500;

    private static final int MAX_QUEUE_SIZE = 50000;

    public WsBatcher(WebSocketServer wsServer, Logger logger, long flushIntervalMs) {
        this.wsServer = wsServer;
        this.logger = logger;
        this.flushIntervalMs = flushIntervalMs;
    }

    public void start() {
        running = true;
        flusher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(flushIntervalMs);
                    flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("[WS-Batcher] flush error: " + e.getMessage());
                }
            }

            try { flush(); } catch (Exception ignored) {}
        }, "AerMonitor-WsBatcher");
        flusher.setDaemon(true);
        flusher.start();
    }

    public void stop() {
        running = false;
        if (flusher != null) flusher.interrupt();
    }

    public void offer(String event, JsonObject data) {
        if (!running) {

            wsServer.broadcast(event, data);
            return;
        }

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("event", event);
        wrapper.addProperty("time", System.currentTimeMillis());
        wrapper.add("data", data);

        if (pending.size() >= MAX_QUEUE_SIZE) {

            pending.poll();
        }
        pending.offer(wrapper);
    }

    public void flush() {
        if (pending.isEmpty()) return;
        if (!flushing.compareAndSet(false, true)) return;
        try {
            List<JsonObject> batch = new ArrayList<>(Math.min(MAX_EVENTS_PER_BATCH, 256));
            JsonObject e;
            while ((e = pending.poll()) != null && batch.size() < MAX_EVENTS_PER_BATCH) {
                batch.add(e);
            }
            if (batch.isEmpty()) return;

            if (batch.size() == 1) {

                wsServer.broadcastRaw(batch.get(0));
            } else {

                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("event", "__batch");
                wrapper.addProperty("time", System.currentTimeMillis());
                JsonObject data = new JsonObject();
                JsonArray events = new JsonArray();
                for (JsonObject o : batch) events.add(o);
                data.add("events", events);
                wrapper.add("data", data);
                wsServer.broadcastRaw(wrapper);
            }
        } finally {
            flushing.set(false);
        }

        if (!pending.isEmpty()) flush();
    }
}
