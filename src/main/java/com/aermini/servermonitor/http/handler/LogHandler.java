package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.database.dao.WebhookLogDAO;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.util.TimeUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class LogHandler {

    private final LogManager logManager;
    private final ChatDAO chatDAO;
    private final WebhookLogDAO webhookLogDAO;
    private final File logDir;

    public LogHandler(LogManager logManager, ChatDAO chatDAO, WebhookLogDAO webhookLogDAO, File dataFolder) {
        this.logManager = logManager;
        this.chatDAO = chatDAO;
        this.webhookLogDAO = webhookLogDAO;
        this.logDir = new File(dataFolder, "logs");
    }

    public void handleGet(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());

        int t = 50;
        int offset = 0;
        Long timeFrom = null, timeTo = null;
        String type = null;

        try {
            if (params.containsKey("t")) t = Math.min(Integer.parseInt(params.get("t")), 500);
            if (params.containsKey("offset")) offset = Integer.parseInt(params.get("offset"));
            if (params.containsKey("timefrom")) timeFrom = Long.parseLong(params.get("timefrom"));
            if (params.containsKey("timeto")) timeTo = Long.parseLong(params.get("timeto"));
            if (params.containsKey("type")) type = params.get("type");
        } catch (NumberFormatException e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 70002, "invalid parameter", logId), logManager);
            return;
        }

        JsonArray logs = new JsonArray();
        List<String> types = type != null ? Arrays.asList(type.toUpperCase().split(",")) : null;

        try {
            List<String> allLines = new ArrayList<>();
            if (logDir.exists()) {
                File[] files = logDir.listFiles((dir, name) -> name.startsWith("monitor-") && name.endsWith(".log"));
                if (files != null) {
                    Arrays.sort(files, Comparator.comparing(File::getName).reversed());
                    for (File f : files) {
                        List<String> lines = Files.readAllLines(f.toPath());
                        allLines.addAll(lines);
                    }
                }
            }

            Long finalTimeFrom = timeFrom;
            Long finalTimeTo = timeTo;
            List<String> filtered = allLines.stream()
                    .filter(line -> {
                        if (types != null) {
                            boolean match = false;
                            for (String t2 : types) {
                                if (line.startsWith("[" + t2 + "]")) { match = true; break; }
                            }
                            if (!match) return false;
                        }
                        if (finalTimeFrom != null || finalTimeTo != null) {
                            int start = line.indexOf("][", line.indexOf("][") + 1);
                            int end = line.indexOf("]", start + 2);
                            if (start > 0 && end > start) {
                                try {
                                    String timeStr = line.substring(start + 2, end);
                                    long ts = parseLogTimestamp(timeStr);
                                    if (finalTimeFrom != null && ts < finalTimeFrom) return false;
                                    if (finalTimeTo != null && ts > finalTimeTo) return false;
                                } catch (Exception ignored) {}
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            int total = filtered.size();
            int start = Math.min(offset, filtered.size());
            int end = Math.min(start + t, filtered.size());

            for (int i = start; i < end; i++) {
                JsonObject entry = new JsonObject();
                String line = filtered.get(i);
                entry.addProperty("raw", line);
                logs.add(entry);
            }

            JsonObject data = new JsonObject();
            data.addProperty("total", total);
            data.addProperty("offset", offset);
            data.addProperty("count", end - start);
            data.add("logs", logs);
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);

        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 70001, "error reading logs", logId), logManager);
        }
    }

    public void handleGetLog(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String id = params.get("id");

        if (id == null || id.isEmpty()) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 70002, "id required", logId), logManager);
            return;
        }

        JsonArray entries = searchLogFiles(id);
        if (entries.size() == 0) {
            HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 70003, "logid not found", logId), logManager);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("logid", id);
        data.add("entries", entries);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleGetMsg(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String id = params.get("id");

        if (id == null || id.isEmpty()) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 70002, "id required", logId), logManager);
            return;
        }

        JsonObject msg = chatDAO.getMsgById(id);
        if (msg == null) {
            HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 70003, "msgid not found", logId), logManager);
            return;
        }

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, msg), logManager);
    }

    public void handleGetEvent(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String id = params.get("id");

        if (id == null || id.isEmpty()) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 70002, "id required", logId), logManager);
            return;
        }

        JsonArray entries = searchLogFiles(id);
        if (entries.size() == 0) {
            HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 70003, "eventid not found", logId), logManager);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("eventid", id);
        data.add("entries", entries);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    private JsonArray searchLogFiles(String id) {
        JsonArray result = new JsonArray();
        if (!logDir.exists()) return result;

        File[] files = logDir.listFiles((dir, name) -> name.startsWith("monitor-") && name.endsWith(".log"));
        if (files == null) return result;

        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File f : files) {
            try {
                List<String> lines = Files.readAllLines(f.toPath());
                for (String line : lines) {
                    if (line.contains(id)) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("raw", line);
                        result.add(entry);
                    }
                }
                if (result.size() > 0) break;
            } catch (IOException ignored) {}
        }
        return result;
    }

    private long parseLogTimestamp(String timeStr) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return sdf.parse(timeStr).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
