package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.util.ServerInfoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class PlayerHandler {

    private final AerServerMonitor plugin;
    private final PlayerDAO playerDAO;
    private final SkinDatabaseManager skinDB;
    private final LogManager logManager;

    public PlayerHandler(AerServerMonitor plugin, PlayerDAO playerDAO, SkinDatabaseManager skinDB, LogManager logManager) {
        this.plugin = plugin;
        this.playerDAO = playerDAO;
        this.skinDB = skinDB;
        this.logManager = logManager;
    }

    public void handleInfo(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String uuidParam = params.get("uuid");
        String nameParam = params.get("name");

        if (uuidParam != null && uuidParam.contains(",")) {
            handleBatchInfo(exchange, logId, uuidParam.split(","), null);
            return;
        }
        if (nameParam != null && nameParam.contains(",")) {
            handleBatchInfo(exchange, logId, null, nameParam.split(","));
            return;
        }

        JsonObject player = findPlayer(uuidParam, nameParam);
        if (player == null) {
            int errcode = uuidParam != null ? 10001 : 10002;
            if (uuidParam != null && nameParam != null) errcode = 10005;
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, errcode, "player not found", logId), logManager);
            return;
        }

        if (uuidParam != null && nameParam != null) {
            String actualName = player.get("name").getAsString();
            if (!actualName.equals(nameParam)) {

                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 10003, "uuid and name mismatch", logId), logManager);
                return;
            }
        }

        boolean online = player.get("online").getAsBoolean();
        int errcode = online ? 0 : 10006;

        JsonObject data = buildPlayerData(player);

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(errcode, logId, data), logManager);
    }

    private void handleBatchInfo(HttpExchange exchange, String logId, String[] uuids, String[] names) {
        JsonArray results = new JsonArray();
        int count = Math.max(uuids != null ? uuids.length : 0, names != null ? names.length : 0);

        for (int i = 0; i < count; i++) {
            String uuid = uuids != null && i < uuids.length ? uuids[i].trim() : null;
            String name = names != null && i < names.length ? names[i].trim() : null;

            JsonObject player = findPlayer(uuid, name);
            if (player == null) {
                JsonObject entry = new JsonObject();
                entry.addProperty("code", 400);
                entry.addProperty("errcode", uuid != null ? 10001 : 10002);
                entry.add("player", null);
                results.add(entry);
            } else {
                JsonObject entry = new JsonObject();
                entry.addProperty("code", 200);
                entry.addProperty("errcode", player.get("online").getAsBoolean() ? 0 : 10006);
                entry.add("player", buildPlayerData(player));
                results.add(entry);
            }
        }

        JsonObject data = new JsonObject();
        data.add("players", results);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleList(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());

        int total = playerDAO.getOnlineCount();

        String tParam = params.get("t");
        String offsetParam = params.get("offset");

        java.util.List<JsonObject> players;
        if (tParam != null || offsetParam != null) {
            int t = tParam != null ? Integer.parseInt(tParam) : total;
            int offset = offsetParam != null ? Integer.parseInt(offsetParam) : 0;
            players = playerDAO.getOnlinePlayers(t, offset);
        } else {
            players = playerDAO.getOnlinePlayers();
        }

        JsonArray arr = new JsonArray();
        for (JsonObject p : players) {
            JsonObject pp = new JsonObject();
            pp.addProperty("name", p.get("name").getAsString());
            pp.addProperty("uuid", p.get("uuid").getAsString());
            String serverName = p.has("current_server") && !p.get("current_server").isJsonNull()
                    ? p.get("current_server").getAsString() : null;
            pp.add("server", serverName != null ? ServerInfoUtil.buildServerObject(serverName) : new JsonObject());
            pp.addProperty("skin", skinDB.getSkinUuid(p.get("uuid").getAsString()));
            arr.add(pp);
        }

        JsonObject data = new JsonObject();
        data.addProperty("total", total);
        data.add("players", arr);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleKick(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String uuid = params.get("uuid");
        String name = params.get("name");
        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);

        JsonObject player = findPlayer(uuid, name);
        if (player == null) {

            int errcode = uuid != null ? 10001 : 10002;
            if (uuid != null && name != null) errcode = 10005;

            JsonObject data = new JsonObject();
            data.add("server", new JsonObject());
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, errcode, "player not found", logId, data), logManager);
            return;
        }

        if (!player.get("online").getAsBoolean()) {

            JsonObject data = buildPlayerData(player, true);
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 10006, "player offline", logId, data), logManager);
            return;
        }

        String playerUuid = player.get("uuid").getAsString();
        ProxiedPlayer pp = ProxyServer.getInstance().getPlayer(java.util.UUID.fromString(playerUuid));
        if (pp != null) {
            String reason = body != null && !body.isEmpty() ? body : "Kicked by operator";
            pp.disconnect(reason);
        }

        JsonObject data = buildPlayerData(player);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    private JsonObject findPlayer(String uuid, String name) {
        if (uuid != null) {
            JsonObject p = playerDAO.getPlayerByUuid(uuid);
            if (p != null) return p;
        }
        if (name != null) {
            return playerDAO.getPlayerByName(name);
        }
        return null;
    }

    private JsonObject buildPlayerData(JsonObject player) {
        return buildPlayerData(player, false);
    }

    private JsonObject buildPlayerData(JsonObject player, boolean emptyServerWhenOffline) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.get("name").getAsString());
        data.addProperty("uuid", player.get("uuid").getAsString());
        data.addProperty("ip", player.has("ip") && !player.get("ip").isJsonNull()
                ? player.get("ip").getAsString() : "");
        data.addProperty("online", player.get("online").getAsBoolean());

        boolean online = player.get("online").getAsBoolean();
        String currentServer = player.has("current_server") && !player.get("current_server").isJsonNull()
                ? player.get("current_server").getAsString() : null;

        JsonObject server = new JsonObject();
        if (currentServer != null && !(emptyServerWhenOffline && !online)) {

            server = ServerInfoUtil.buildServerObject(currentServer);
        }
        data.add("server", server);

        String skinUuid = player.get("uuid").getAsString();
        String skin = skinDB.getSkinUuid(skinUuid);
        data.addProperty("skin", skin);

        data.addProperty("firstjoin", player.has("fjoin_time") ? player.get("fjoin_time").getAsLong() : 0);
        data.addProperty("lastjoin", player.has("ljoin_time") ? player.get("ljoin_time").getAsLong() : 0);
        data.addProperty("leave", player.has("leave_time") ? player.get("leave_time").getAsLong() : 0);

        return data;
    }
}