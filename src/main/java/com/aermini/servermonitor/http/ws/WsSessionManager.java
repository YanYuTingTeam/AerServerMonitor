package com.aermini.servermonitor.http.ws;

import com.google.gson.JsonObject;

import java.util.logging.Logger;

public class WsSessionManager {

    private final WebSocketServer wsServer;
    private final WsBatcher batcher;

    public WsSessionManager(WebSocketServer wsServer, Logger logger, long flushIntervalMs) {
        this.wsServer = wsServer;
        this.batcher = new WsBatcher(wsServer, logger, flushIntervalMs);
        this.batcher.start();
    }

    public void broadcast(String event, JsonObject data) {
        wsServer.broadcast(event, data);
    }

    public void offer(String event, JsonObject data) {
        batcher.offer(event, data);
    }

    public void flush() {
        batcher.flush();
    }

    public void shutdown() {
        batcher.stop();
    }
}
