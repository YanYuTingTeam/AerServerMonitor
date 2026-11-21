package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

        String command = body.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();

        if (command.isEmpty()) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "Command cannot be empty", logId), logManager);
            return;
        }
        if (command.length() > 512) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "Command too long", logId), logManager);
            return;
        }

        String commandName = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!isCommandAllowed(commandName)) {
            HttpApiServer.sendWithLog(exchange, 403, ApiResponse.error(403, 30004, "Command not allowed", logId), logManager);
            return;
        }

        ConsoleCommandSender sender = new ConsoleCommandSender();
        String collectedOutput;
        try {
            boolean success = ProxyServer.getInstance().getPluginManager().dispatchCommand(sender, command);
            collectedOutput = sender.getCollected();

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
            String partial = sender.getCollected();

            StringBuilder full = new StringBuilder();
            if (!partial.isEmpty()) full.append(partial);
            if (full.length() > 0) full.append('\n');
            full.append(msg);
            JsonObject data = new JsonObject();
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 30002, full.toString().trim(), logId, data), logManager);
        }
    }

    private boolean isCommandAllowed(String commandName) {
        PluginConfig config = plugin.getConfig();
        List<String> whitelist = config.getCommandWhitelist();
        if (!whitelist.isEmpty() && !containsCommand(whitelist, commandName)) return false;
        return !containsCommand(config.getCommandBlacklist(), commandName);
    }

    private boolean containsCommand(List<String> list, String commandName) {
        for (String item : list) {
            if (item != null && item.trim().toLowerCase(Locale.ROOT).equals(commandName)) return true;
        }
        return false;
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

    private static class ConsoleCommandSender implements CommandSender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "AerMonitor-Console";
        }

        @Override
        public void sendMessage(String message) {
            if (message != null) messages.add(message);
        }

        @Override
        public void sendMessages(String... messages) {
            for (String msg : messages) {
                sendMessage(msg);
            }
        }

        @Override
        public void sendMessage(BaseComponent... message) {
            if (message != null && message.length > 0) {
                messages.add(BaseComponent.toPlainText(message));
            }
        }

        @Override
        public void sendMessage(BaseComponent message) {
            if (message != null) messages.add(message.toPlainText());
        }

        @Override
        public Collection<String> getGroups() {
            return Collections.emptyList();
        }

        @Override
        public void addGroups(String... groups) {}

        @Override
        public void removeGroups(String... groups) {}

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void setPermission(String permission, boolean value) {}

        @Override
        public Collection<String> getPermissions() {
            return Collections.singleton("*");
        }

        String getCollected() {
            return String.join("\n", messages);
        }
    }
}
