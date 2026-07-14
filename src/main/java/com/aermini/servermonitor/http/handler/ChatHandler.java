package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public class ChatHandler {

    private final AerServerMonitor plugin;
    private final ChatDAO chatDAO;
    private final LogManager logManager;

    public ChatHandler(AerServerMonitor plugin, ChatDAO chatDAO, LogManager logManager) {
        this.plugin = plugin;
        this.chatDAO = chatDAO;
        this.logManager = logManager;
    }

    public void handleCommand(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);

        String command = body.startsWith("/") ? body : "/" + body;

        Logger bungeeRoot = Logger.getLogger("");
        CapturingHandler captor = new CapturingHandler();
        captor.setLevel(java.util.logging.Level.ALL);
        bungeeRoot.addHandler(captor);

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf, true));
        System.setErr(new PrintStream(errBuf, true));

        String collectedOutput;
        try {
            boolean success = ProxyServer.getInstance().getPluginManager().dispatchCommand(
                    ProxyServer.getInstance().getConsole(), command);

            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            collectedOutput = captor.getCollected();
            String outStr = outBuf.toString("UTF-8");
            String errStr = errBuf.toString("UTF-8");

            StringBuilder full = new StringBuilder();
            if (!outStr.isEmpty()) full.append(outStr);
            if (!collectedOutput.isEmpty()) {
                if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') full.append('\n');
                full.append(collectedOutput);
            }
            if (!errStr.isEmpty()) {
                if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') full.append('\n');
                full.append(errStr);
            }
            collectedOutput = full.toString().trim();

            logManager.log("[CMD] Console executed " + command + " -> " + collectedOutput.replaceAll("\\s+", " "));

            if (!success) {

                JsonObject data = new JsonObject();
                HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 30001, "Command not found", logId, data), logManager);
                return;
            }

            JsonObject data = new JsonObject();

            String message = "CONSOLE executed command: " + command;
            if (!collectedOutput.isEmpty()) {
                message += "\n" + collectedOutput;
            }
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.error(200, 0, message, logId, data), logManager);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            String partial = captor.getCollected();
            String errStr = errBuf.toString().trim();

            StringBuilder full = new StringBuilder();
            if (!partial.isEmpty()) full.append(partial);
            if (!errStr.isEmpty()) {
                if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') full.append('\n');
                full.append(errStr);
            }
            if (full.length() > 0) {
                if (full.charAt(full.length() - 1) != '\n') full.append('\n');
                full.append(msg);
            } else {
                full.append(msg);
            }
            JsonObject data = new JsonObject();
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 30002, full.toString().trim(), logId, data), logManager);
        } finally {
            bungeeRoot.removeHandler(captor);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    public void handleHistory(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());

        Long timeFrom = null, timeTo = null;
        try {
            if (params.containsKey("timefrom")) timeFrom = Long.parseLong(params.get("timefrom"));
            if (params.containsKey("timeto")) timeTo = Long.parseLong(params.get("timeto"));
        } catch (NumberFormatException e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "参数错误", logId), logManager);
            return;
        }

        JsonArray messages = chatDAO.getHistory(timeFrom, timeTo, 100, 0);

        JsonObject data = new JsonObject();
        data.add("messages", messages);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    private static class CapturingHandler extends Handler {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() == null) return;

            String msg = record.getMessage();
            lines.add(format(record, msg));
        }

        private String format(LogRecord record, String msg) {

            return msg;
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String getCollected() {
            return String.join("\n", lines);
        }
    }
}
