package com.aermini.servermonitor.log;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.util.TimeUtil;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class LogManager {

    private final PluginConfig config;
    private final Logger logger;
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(50000);
    private volatile boolean running = true;
    private BufferedWriter writer;
    private String currentDate;
    private final File logDir;
    private Thread cleanupThread;

    public LogManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.logDir = new File(config.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();
    }

    public void start() {
        openWriter();
        Thread flushThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(config.getAsyncLogFlushInterval());
                    flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Log flush error: " + e.getMessage());
                }
            }
            flush();
        }, "AerMonitor-LogWriter");
        flushThread.setDaemon(true);
        flushThread.start();

        cleanupThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3600000);
                    cleanOldLogs();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {}
            }
        }, "AerMonitor-LogCleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    public void stop() {
        running = false;
        if (cleanupThread != null) cleanupThread.interrupt();
        flush();
        closeWriter();
    }

    public void log(String line) {
        if (!logQueue.offer(line)) {
        }
    }

    private void flush() {
        String line;
        int count = 0;
        while ((line = logQueue.poll()) != null && count < 5000) {
            try {
                ensureWriter();
                writer.write(line);
                writer.newLine();
                count++;
            } catch (IOException e) {
                logger.warning("Log write error: " + e.getMessage());
            }
        }
        if (count > 0) {
            try { writer.flush(); } catch (IOException ignored) {}
        }
    }

    private void ensureWriter() throws IOException {
        String today = TimeUtil.formatDate(System.currentTimeMillis());
        if (!today.equals(currentDate) || writer == null) {
            closeWriter();
            currentDate = today;
            openWriter();
        }
    }

    private void openWriter() {
        try {
            String today = TimeUtil.formatDate(System.currentTimeMillis());
            File logFile = new File(logDir, "monitor-" + today + ".log");
            writer = new BufferedWriter(new FileWriter(logFile, true));
            currentDate = today;
        } catch (IOException e) {
            logger.warning("Cannot open log file: " + e.getMessage());
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    private void cleanOldLogs() {
        int retention = config.getLogRetentionDays();
        if (retention <= 0) return;
        long cutoff = System.currentTimeMillis() - (retention * 86400000L);
        File[] files = logDir.listFiles((dir, name) -> name.startsWith("monitor-") && name.endsWith(".log"));
        if (files == null) return;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    public void logRequest(String logId, String method, String route, int code, String headerA, String headerB, String postBody, String ip) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        String line = "[REQUEST][" + logId + "][" + time + "][" + ip + "] " + method + " . " + route + " (" + code + ") | " + headerA + " | " + headerB;
        if (postBody != null && !postBody.isEmpty()) {
            line += " | " + postBody;
        }
        log(line);
        if (config.isPrintRequest()) {
            logger.info("[HTTP] " + method + " " + route + " (" + code + ") from " + ip);
        }
    }

    public void logResponse(String logId, String refLogId, String body) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        log("[RESPONSE][" + logId + "][" + time + "] " + refLogId + ": " + body);
    }

    public void logOperation(String logId, String refLogId, String content) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        log("[OPERATION][" + logId + "][" + time + "] " + refLogId + ": " + content);
    }

    public void logChat(String msgId, String eventId, String server, String playerName, String playerUuid, String message) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        log("[CHAT][" + msgId + "][" + eventId + "][" + time + "] " + server + " | " + playerName + " (" + playerUuid + ") | " + message);
        if (config.isPrintChat()) {
            logger.info("[CHAT] " + server + " | " + playerName + " (" + playerUuid + ") | " + message);
        }
    }

    public void logGame(String eventId, String server, String playerName, String playerUuid, String action) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        log("[GAME][" + eventId + "][" + time + "] " + server + " | " + playerName + " (" + playerUuid + ") | " + action);
    }

    public void logOther(String eventId, String content) {
        String time = TimeUtil.formatTimestamp(System.currentTimeMillis());
        log("[OTHER][" + eventId + "][" + time + "] " + content);
    }
}
