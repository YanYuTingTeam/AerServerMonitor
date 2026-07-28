package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.middleware.WhitelistMiddleware;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.slp.SLPing;
import com.aermini.servermonitor.util.ServerInfoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ServerHandler {

    private final AerServerMonitor plugin;
    private final PlayerDAO playerDAO;
    private final SkinDatabaseManager skinDB;
    private final LogManager logManager;
    private final WhitelistMiddleware ssrfCheck;

    private final ExecutorService pingPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "AerMonitor-SLP"); t.setDaemon(true); return t;
    });

    public void shutdown() {
        pingPool.shutdownNow();
    }

    public ServerHandler(AerServerMonitor plugin, PlayerDAO playerDAO, SkinDatabaseManager skinDB, LogManager logManager, WhitelistMiddleware ssrfCheck) {
        this.plugin = plugin;
        this.playerDAO = playerDAO;
        this.skinDB = skinDB;
        this.logManager = logManager;
        this.ssrfCheck = ssrfCheck;
    }

    public void handleList(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        boolean ping = "true".equalsIgnoreCase(params.get("ping"));

        Map<String, ServerInfo> serverMap = ProxyServer.getInstance().getServers();

        List<String> names = new ArrayList<>(serverMap.keySet());
        List<JsonObject> servers = new ArrayList<>();

        Map<String, CompletableFuture<SLPing.Response>> pingFutures = new ConcurrentHashMap<>();
        if (ping) {
            for (String name : names) {
                ServerInfo info = serverMap.get(name);
                pingFutures.put(name, CompletableFuture.supplyAsync(() ->
                        SLPing.ping(info.getAddress().getHostName(), info.getAddress().getPort(), 5000),
                        pingPool));
            }
        }

        for (String name : names) {
            ServerInfo info = serverMap.get(name);

            JsonObject server = new JsonObject();
            server.addProperty("name", name);

            server.addProperty("ip", ServerInfoUtil.formatAddress(info));
            server.addProperty("port", info.getAddress().getPort());
            server.addProperty("describe", info.getMotd());
            server.addProperty("restricted", info.isRestricted());

            JsonArray players = new JsonArray();
            for (JsonObject p : playerDAO.getPlayersByServer(name)) {
                JsonObject pp = new JsonObject();
                pp.addProperty("name", p.get("name").getAsString());
                pp.addProperty("uuid", p.get("uuid").getAsString());

                pp.add("server", ServerInfoUtil.buildServerObject(name));
                pp.addProperty("skin", skinDB.getSkinUuid(p.get("uuid").getAsString()));
                players.add(pp);
            }

            if (ping) {
                try {
                    SLPing.Response pingResult = pingFutures.get(name).get(6, TimeUnit.SECONDS);
                    if (pingResult != null) {
                        server.addProperty("online", pingResult.online);
                        server.addProperty("max", pingResult.max);
                        server.addProperty("motd", pingResult.motdClean);
                        server.addProperty("ico", pingResult.favicon);
                        server.addProperty("version", pingResult.version);
                    } else {
                        server.addProperty("online", -1);
                        server.addProperty("max", -1);
                        server.addProperty("motd", "");
                        server.addProperty("ico", "");
                        server.addProperty("version", "");
                    }
                } catch (Exception e) {
                    server.addProperty("online", -1);
                    server.addProperty("max", -1);
                    server.addProperty("motd", "");
                    server.addProperty("ico", "");
                    server.addProperty("version", "");
                }
            } else {
                server.addProperty("online", playerDAO.getOnlineCountByServer(name));
            }

            server.add("players", players);
            servers.add(server);
        }

        JsonObject data = new JsonObject();
        data.add("servers", new JsonArray());
        for (JsonObject s : servers) data.getAsJsonArray("servers").add(s);
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleInfo(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        Map<String, String> params = HttpApiServer.parseQuery(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String portStr = params.get("port");
        String ipParam = params.get("ip");
        boolean ping = "true".equalsIgnoreCase(params.get("ping"));

        if (name != null && portStr != null && ipParam != null) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "参数过多", logId), logManager);
            return;
        }

        if (ipParam != null) {
            if (!ssrfCheck.checkServerIp(ipParam)) {
                HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 50002, "ip not allowed", logId), logManager);
                return;
            }
            SLPing.Response result = SLPing.ping(ipParam.split(":")[0],
                    ipParam.contains(":") ? Integer.parseInt(ipParam.split(":")[1]) : 25565, 5000);
            JsonObject data = new JsonObject();
            if (result != null) {
                data.addProperty("online", result.online);
                data.addProperty("max", result.max);
                data.addProperty("motd", result.motdClean);
                data.addProperty("ico", result.favicon);
                data.addProperty("version", result.version);
                HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
            } else {
                data.addProperty("online", -1);
                data.addProperty("max", -1);
                HttpApiServer.sendWithLog(exchange, 200, ApiResponse.error(200, 20001, "success", logId, data), logManager);
            }
            return;
        }

        Map<String, ServerInfo> serverMap = ProxyServer.getInstance().getServers();
        ServerInfo target = null;
        int errcode = 0;

        if (name != null && portStr != null) {
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid port", logId), logManager);
                return;
            }

            List<ServerInfo> byNameList = new ArrayList<>();
            List<ServerInfo> byPortList = new ArrayList<>();
            for (ServerInfo si : serverMap.values()) {
                if (si.getName().equals(name)) byNameList.add(si);
                if (si.getAddress().getPort() == port) byPortList.add(si);
            }

            if (byNameList.isEmpty() && byPortList.isEmpty()) {

                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20008, "server not found", logId), logManager);
                return;
            }
            if (byNameList.isEmpty() && !byPortList.isEmpty()) {

                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20004, "name and port mismatch", logId), logManager);
                return;
            }
            if (!byNameList.isEmpty() && byPortList.isEmpty()) {

                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20005, "name and port mismatch", logId), logManager);
                return;
            }

            ServerInfo byName = byNameList.get(0);
            ServerInfo byPort = byPortList.get(0);
            if (!byName.equals(byPort)) {
                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20004, "name and port mismatch", logId), logManager);
                return;
            }
            target = byName;

            if (byNameList.size() > 1) errcode = 20007;
        } else if (name != null) {

            List<ServerInfo> matches = new ArrayList<>();
            for (ServerInfo si : serverMap.values()) {
                if (si.getName().equals(name)) matches.add(si);
            }

            if (matches.isEmpty()) {
                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20002, "server not found by name", logId), logManager);
                return;
            }
            target = matches.get(matches.size() - 1);
            if (matches.size() > 1) errcode = 20007;
        } else if (portStr != null) {
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid port", logId), logManager);
                return;
            }

            List<ServerInfo> matches = new ArrayList<>();
            for (ServerInfo si : serverMap.values()) {
                if (si.getAddress().getPort() == port) matches.add(si);
            }
            if (matches.isEmpty()) {
                HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 20003, "server not found by port", logId), logManager);
                return;
            }
            if (matches.size() > 1) {

                HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 20006, "port matches multiple servers", logId), logManager);
                return;
            }
            target = matches.get(0);
        } else {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "name or port required", logId), logManager);
            return;
        }

        JsonObject data = new JsonObject();
        String serverName = target.getName();
        data.addProperty("name", serverName);
        data.addProperty("ip", ServerInfoUtil.formatAddress(target));
        data.addProperty("port", target.getAddress().getPort());
        data.addProperty("describe", target.getMotd());
        data.addProperty("restricted", target.isRestricted());

        JsonArray players = new JsonArray();
        for (JsonObject p : playerDAO.getPlayersByServer(serverName)) {
            JsonObject pp = new JsonObject();
            pp.addProperty("name", p.get("name").getAsString());
            pp.addProperty("uuid", p.get("uuid").getAsString());
            pp.add("server", ServerInfoUtil.buildServerObject(serverName));
            pp.addProperty("skin", skinDB.getSkinUuid(p.get("uuid").getAsString()));
            players.add(pp);
        }
        data.add("players", players);

        if (ping) {
            SLPing.Response result = SLPing.ping(target.getAddress().getHostName(), target.getAddress().getPort(), 5000);
            if (result != null) {
                data.addProperty("online", result.online);
                data.addProperty("max", result.max);
                data.addProperty("motd", result.motdClean);
                data.addProperty("ico", result.favicon);
                data.addProperty("version", result.version);
            } else {
                data.addProperty("online", -1);
                data.addProperty("max", -1);

                if (errcode == 0) errcode = 20001;
            }
        } else {
            data.addProperty("online", playerDAO.getOnlineCountByServer(serverName));
        }

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.error(200, errcode, "success", logId, data), logManager);
    }
}
