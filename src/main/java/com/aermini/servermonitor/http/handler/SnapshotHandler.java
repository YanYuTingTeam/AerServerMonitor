package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.util.ServerInfoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;

import java.util.List;
import java.util.Map;

public class SnapshotHandler {

    private final AerServerMonitor plugin;
    private final PlayerDAO playerDAO;
    private final SkinDatabaseManager skinDB;
    private final LogManager logManager;

    public SnapshotHandler(AerServerMonitor plugin, PlayerDAO playerDAO, SkinDatabaseManager skinDB, LogManager logManager) {
        this.plugin = plugin;
        this.playerDAO = playerDAO;
        this.skinDB = skinDB;
        this.logManager = logManager;
    }

    public void handleSnapshot(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);

        JsonObject data = new JsonObject();

        Map<String, ServerInfo> serverMap = ProxyServer.getInstance().getServers();
        JsonArray servers = new JsonArray();
        int totalOnline = 0;
        for (Map.Entry<String, ServerInfo> entry : serverMap.entrySet()) {
            String name = entry.getKey();
            ServerInfo info = entry.getValue();
            JsonObject s = new JsonObject();
            s.addProperty("name", name);
            s.addProperty("ip", ServerInfoUtil.formatAddress(info));
            s.addProperty("port", info.getAddress().getPort());
            s.addProperty("describe", info.getMotd());
            s.addProperty("restricted", info.isRestricted());
            int online = playerDAO.getOnlineCountByServer(name);
            s.addProperty("online", online);
            totalOnline += online;
            servers.add(s);
        }
        data.add("servers", servers);
        data.addProperty("total_online", totalOnline);
        data.addProperty("server_count", serverMap.size());

        JsonArray players = new JsonArray();
        List<JsonObject> onlinePlayers = playerDAO.getOnlinePlayers();
        for (JsonObject p : onlinePlayers) {
            JsonObject pp = new JsonObject();
            pp.addProperty("uuid", p.get("uuid").getAsString());
            pp.addProperty("name", p.get("name").getAsString());
            String cs = p.has("current_server") && !p.get("current_server").isJsonNull()
                    ? p.get("current_server").getAsString() : "";
            pp.addProperty("server", cs);
            pp.addProperty("ip", p.has("ip") && !p.get("ip").isJsonNull()
                    ? p.get("ip").getAsString() : "");
            pp.addProperty("firstjoin", p.has("fjoin_time") ? p.get("fjoin_time").getAsLong() : 0);
            pp.addProperty("lastjoin", p.has("ljoin_time") ? p.get("ljoin_time").getAsLong() : 0);
            players.add(pp);
        }
        data.add("players", players);
        data.addProperty("player_count", onlinePlayers.size());

        data.addProperty("snapshot_time", System.currentTimeMillis());

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }
}
