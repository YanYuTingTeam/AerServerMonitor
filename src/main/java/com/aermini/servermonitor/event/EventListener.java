package com.aermini.servermonitor.event;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.database.dao.CmdLogDAO;
import com.aermini.servermonitor.http.ws.WsSessionManager;
import com.aermini.servermonitor.post.PostManager;
import com.aermini.servermonitor.util.TimeUtil;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class EventListener implements Listener {

    private final AerServerMonitor plugin;
    private final LogManager logManager;
    private final PlayerDAO playerDAO;
    private final ChatDAO chatDAO;
    private final CmdLogDAO cmdLogDAO;
    private final PostManager postManager;
    private final WsSessionManager wsSessionManager;
    private final BlockingQueue<Object[]> chatBuffer;

    public EventListener(AerServerMonitor plugin, LogManager logManager, PlayerDAO playerDAO,
                         ChatDAO chatDAO, CmdLogDAO cmdLogDAO, PostManager postManager,
                         WsSessionManager wsSessionManager, BlockingQueue<Object[]> chatBuffer) {
        this.plugin = plugin;
        this.logManager = logManager;
        this.playerDAO = playerDAO;
        this.chatDAO = chatDAO;
        this.cmdLogDAO = cmdLogDAO;
        this.postManager = postManager;
        this.wsSessionManager = wsSessionManager;
        this.chatBuffer = chatBuffer;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        long now = TimeUtil.now();
        String eventId = LogIdGenerator.generateEventId("playerJoin", "", player.getUniqueId().toString());

        plugin.getAsyncExecutor().execute(() -> {
            playerDAO.upsertPlayer(
                    player.getUniqueId().toString(),
                    player.getName(),
                    player.getAddress().getAddress().getHostAddress(),
                    null, true, now
            );

            logManager.logGame(eventId, "-", player.getName(), player.getUniqueId().toString(), "player connected");

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerJoin");
            body.addProperty("time", now);
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", player.getUniqueId().toString());
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);

            postManager.post(EventType.PLAYER_JOIN, body.toString());
            wsSessionManager.offer("playerJoin", body);
        });
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        long now = TimeUtil.now();
        String uuid = player.getUniqueId().toString();
        String serverName = player.getServer() != null ? player.getServer().getInfo().getName() : "-";

        String eventId = LogIdGenerator.generateEventId("playerLeave", serverName, uuid);

        plugin.getAsyncExecutor().execute(() -> {
            playerDAO.setOffline(uuid, now);

            logManager.logGame(eventId, serverName, player.getName(), uuid, "player disconnected");

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerLeave");
            body.addProperty("time", now);
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);

            postManager.post(EventType.PLAYER_LEAVE, body.toString());
            wsSessionManager.offer("playerLeave", body);
        });
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (event.getPlayer() == null) return;
        ProxiedPlayer player = event.getPlayer();
        String uuid = player.getUniqueId().toString();

        // 首次连接到代理端，交给onServerConnected处理
        if (event.getReason() == ServerConnectEvent.Reason.JOIN_PROXY) {
            plugin.setPendingConnect(uuid, event);
            return;
        }

        // 转服(COMMAND/KICK_REDIRECT/LOBBY_FALLBACK/PLUGIN_MESSAGE/PLUGIN等)
        long now = TimeUtil.now();
        String targetName = event.getTarget().getName();
        String targetAddress = event.getTarget().getAddress().toString();
        boolean success = !event.isCancelled();

        String eventId = LogIdGenerator.generateEventId("playerJoinServer", targetName, uuid);

        plugin.getAsyncExecutor().execute(() -> {
            String originName = null;
            String originAddress = null;
            JsonObject existing = playerDAO.getPlayerByUuid(uuid);
            if (existing != null && existing.has("current_server") && !existing.get("current_server").isJsonNull()) {
                originName = existing.get("current_server").getAsString();
                if (originName != null) {
                    ServerInfo originServer = ProxyServer.getInstance().getServerInfo(originName);
                    if (originServer != null) {
                        originAddress = originServer.getAddress().toString();
                    }
                }
            }

            playerDAO.updateServer(uuid, targetName, now);

            String finalOrigin = originName;
            logManager.logGame(eventId, targetName, player.getName(), uuid,
                    (finalOrigin != null ? finalOrigin + " -> " : "-> ") + targetName);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerJoinServer");
            body.addProperty("time", now);
            body.addProperty("responseTime", now);

            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);

            JsonObject server = new JsonObject();
            JsonObject target = new JsonObject();
            target.addProperty("name", targetName);
            target.addProperty("address", targetAddress);
            target.addProperty("describe", "");
            server.add("target", target);
            JsonObject origin = new JsonObject();
            origin.addProperty("name", originName);
            origin.addProperty("address", originAddress);
            origin.addProperty("describe", "");
            server.add("origin", origin);
            body.add("server", server);

            body.addProperty("success", success);

            postManager.post(EventType.PLAYER_JOIN_SERVER, body.toString());
            wsSessionManager.offer("playerJoinServer", body);
        });
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        long responseTime = TimeUtil.now();
        String uuid = player.getUniqueId().toString();
        String targetName = event.getServer().getInfo().getName();
        String targetAddress = event.getServer().getInfo().getAddress().toString();

        ServerConnectEvent pending = plugin.removePendingConnect(uuid);
        if (pending == null) return;

        long time = responseTime;

        String eventId = LogIdGenerator.generateEventId("playerJoinServer", targetName, uuid);

        plugin.getAsyncExecutor().execute(() -> {
            playerDAO.upsertPlayer(uuid, player.getName(),
                    player.getAddress().getAddress().getHostAddress(), targetName, true, responseTime);

            logManager.logGame(eventId, targetName, player.getName(), uuid, "-> " + targetName);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerJoinServer");
            body.addProperty("time", time);
            body.addProperty("responseTime", responseTime);

            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);

            JsonObject server = new JsonObject();
            JsonObject target = new JsonObject();
            target.addProperty("name", targetName);
            target.addProperty("address", targetAddress);
            target.addProperty("describe", "");
            server.add("target", target);
            JsonObject origin = new JsonObject();
            origin.addProperty("name", (String) null);
            origin.addProperty("address", (String) null);
            origin.addProperty("describe", (String) null);
            server.add("origin", origin);
            body.add("server", server);

            body.addProperty("success", true);

            postManager.post(EventType.PLAYER_JOIN_SERVER, body.toString());
            wsSessionManager.offer("playerJoinServer", body);
        });
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (event.isCancelled()) return;

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String message = event.getMessage();
        boolean isCommand = message.startsWith("/");

        if (isCommand && !plugin.getConfig().isExecCmdAsPlayerChat()) {
            trackCommand(player, message, event);
            return;
        }

        if (isCommand && plugin.getConfig().isExecCmdAsPlayerChat()) {
            trackCommand(player, message, event);

        }

        long now = TimeUtil.now();
        String uuid = player.getUniqueId().toString();
        String serverName = player.getServer() != null ? player.getServer().getInfo().getName() : "-";
        String msgId = LogIdGenerator.generateMsgId(message);
        String eventId = LogIdGenerator.generateEventId("playerChat", serverName, uuid);

        chatBuffer.offer(new Object[]{
                player.getName(), uuid,
                player.getAddress().getAddress().getHostAddress(),
                serverName, now, msgId, message
        });

        logManager.logChat(msgId, eventId, serverName, player.getName(), uuid, message);

        JsonObject body = new JsonObject();
        body.addProperty("eventid", eventId);
        body.addProperty("event", "playerChat");
        body.addProperty("time", now);
        JsonObject p = new JsonObject();
        p.addProperty("name", player.getName());
        p.addProperty("uuid", uuid);
        p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
        body.add("player", p);
        JsonObject s = new JsonObject();
        s.addProperty("name", serverName);
        body.add("server", s);
        JsonObject m = new JsonObject();
        m.addProperty("msgid", msgId);
        m.addProperty("content", message);
        body.add("message", m);

        postManager.post(EventType.PLAYER_CHAT, body.toString());
        wsSessionManager.offer("playerChat", body);
    }

    private void trackCommand(ProxiedPlayer player, String message, ChatEvent event) {
        long now = TimeUtil.now();
        String uuid = player.getUniqueId().toString();
        String serverName = player.getServer() != null ? player.getServer().getInfo().getName() : "-";
        String eventId = LogIdGenerator.generateEventId("playerExecuteCmd", serverName, uuid);
        String msgId = LogIdGenerator.generateMsgId(message);

        plugin.getAsyncExecutor().execute(() -> {
            logManager.logGame(eventId, serverName, player.getName(), uuid, "executeCmd -> " + message);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerExecuteCmd");
            body.addProperty("time", now);
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);
            JsonObject s = new JsonObject();
            s.addProperty("name", serverName);
            body.add("server", s);
            JsonObject m = new JsonObject();
            m.addProperty("msgid", msgId);
            m.addProperty("content", message);
            body.add("message", m);

            postManager.post(EventType.PLAYER_EXECUTE_CMD, body.toString());
            wsSessionManager.offer("playerExecuteCmd", body);
        });
    }

    @EventHandler
    public void onProxyPing(ProxyPingEvent event) {
        long now = TimeUtil.now();
        String ip = event.getConnection().getAddress().getAddress().getHostAddress();
        String eventId = LogIdGenerator.generateEventId("proxyPing", "", ip);

        plugin.getAsyncExecutor().execute(() -> {
            logManager.logOther(eventId, "proxy was pinged by " + ip);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "proxyPing");
            body.addProperty("time", now);
            body.addProperty("ip", ip);

            postManager.post(EventType.PROXY_PING, body.toString());
        });
    }

    @EventHandler
    public void onServerKick(ServerKickEvent event) {
        ProxiedPlayer player = event.getPlayer();
        long now = TimeUtil.now();
        String uuid = player.getUniqueId().toString();
        String serverName = player.getServer() != null ? player.getServer().getInfo().getName() : "-";
        String reason = event.getKickReasonComponent() != null ?
                net.md_5.bungee.api.chat.TextComponent.toLegacyText(event.getKickReasonComponent()) : "";

        String eventId = LogIdGenerator.generateEventId("playerWasKicked", serverName, uuid);

        plugin.getAsyncExecutor().execute(() -> {
            logManager.logGame(eventId, serverName, player.getName(), uuid, "was kicked because: " + reason);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerWasKicked");
            body.addProperty("time", now);
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            body.add("player", p);
            JsonObject s = new JsonObject();
            s.addProperty("name", serverName);
            body.add("server", s);
            body.addProperty("reason", reason);

            postManager.post(EventType.PLAYER_WAS_KICKED, body.toString());
            wsSessionManager.offer("playerWasKicked", body);
        });
    }
}