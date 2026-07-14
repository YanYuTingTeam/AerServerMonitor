package com.aermini.servermonitor.channel;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.database.dao.ChatDAO;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.event.EventType;
import com.aermini.servermonitor.http.ws.WsSessionManager;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.post.PostManager;
import com.aermini.servermonitor.util.JsonUtil;
import com.aermini.servermonitor.util.ServerInfoUtil;
import com.aermini.servermonitor.util.TimeUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

public class ChannelHandler implements Listener {
    private final AerServerMonitor plugin;
    private final PlayerDAO playerDAO;
    private final ChatDAO chatDAO;
    private final WsSessionManager wsSessionManager;
    private final PostManager postManager;

    public ChannelHandler(AerServerMonitor plugin, PlayerDAO playerDAO, ChatDAO chatDAO,
                          WsSessionManager wsSessionManager, PostManager postManager) {
        this.plugin = plugin;
        this.playerDAO = playerDAO;
        this.chatDAO = chatDAO;
        this.wsSessionManager = wsSessionManager;
        this.postManager = postManager;
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        String tag = event.getTag();
        if (tag != null && (tag.equals("minecraft:brand") || tag.equals("MC|Brand"))) {
            handleBrand(event);
            return;
        }
        if (!tag.equals("aermonitor:main")) return;
        event.setCancelled(true);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String json = in.readUTF();
            JsonObject req = JsonUtil.parse(json);
            String eventType = req.has("event") ? req.get("event").getAsString() : "";
            JsonObject resp;
            switch (eventType) {
                case "getServer":
                    resp = handleGetServer(req);
                    break;
                case "getPlayerInfo":
                    resp = handleGetPlayerInfo(req);
                    break;
                case "getMessage":
                    resp = handleGetMessage(req);
                    break;
                case "notifyStaff":
                    resp = handleNotifyStaff(req);
                    break;
                default:
                    resp = new JsonObject();
                    resp.addProperty("error", "unknown event");
                    break;
            }
            if (event.getSender() instanceof net.md_5.bungee.api.connection.Server) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);
                out.writeUTF(resp.toString());
                ((net.md_5.bungee.api.connection.Server) event.getSender()).sendData("aermonitor:main", baos.toByteArray());
            }
        } catch (Exception ignored) {}
    }

    private void handleBrand(PluginMessageEvent event) {
        try {
            if (!(event.getSender() instanceof ProxiedPlayer)) return;
            ProxiedPlayer player = (ProxiedPlayer) event.getSender();
            byte[] data = event.getData();
            if (data == null || data.length == 0) return;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            String raw = in.readUTF();
            if (raw == null || raw.isEmpty()) return;
            String brand = raw.length() > 64 ? raw.substring(0, 64) : raw;

            String uuid = player.getUniqueId().toString();
            long now = TimeUtil.now();
            String eventId = LogIdGenerator.generateEventId("playerBrandReceived",
                    player.getServer() != null ? player.getServer().getInfo().getName() : "", uuid);

            JsonObject body = new JsonObject();
            body.addProperty("eventid", eventId);
            body.addProperty("event", "playerBrandReceived");
            body.addProperty("time", now);
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName());
            p.addProperty("uuid", uuid);
            p.addProperty("ip", player.getAddress().getAddress().getHostAddress());
            p.addProperty("brand", brand);
            body.add("player", p);

            postManager.post(EventType.PLAYER_BRAND_RECEIVED, body.toString());
            wsSessionManager.offer("playerBrandReceived", body);

            plugin.getAsyncExecutor().execute(() -> playerDAO.updateBrand(uuid, brand));
        } catch (Exception ignored) {}
    }

    private JsonObject handleGetServer(JsonObject req) {
        String port = req.has("port") ? req.get("port").getAsString() : null;
        JsonObject resp = new JsonObject();
        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
            if (port != null && String.valueOf(info.getAddress().getPort()).equals(port)) {
                resp.addProperty("name", info.getName());

                resp.addProperty("address", ServerInfoUtil.formatAddress(info));
                resp.addProperty("describe", info.getMotd());
                return resp;
            }
        }
        resp.addProperty("name", "");
        resp.addProperty("address", "");
        resp.addProperty("describe", "");
        return resp;
    }

    private JsonObject handleGetPlayerInfo(JsonObject req) {
        String uuid = req.has("uuid") ? req.get("uuid").getAsString() : null;
        JsonObject resp = new JsonObject();
        if (uuid == null) {
            resp.addProperty("name", "");
            resp.addProperty("uuid", "");
            resp.addProperty("online", false);
            resp.add("server", new JsonObject());
            return resp;
        }
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(java.util.UUID.fromString(uuid));
        if (player != null && player.isConnected()) {
            resp.addProperty("name", player.getName());
            resp.addProperty("uuid", player.getUniqueId().toString());
            resp.addProperty("online", true);
            JsonObject server = new JsonObject();
            if (player.getServer() != null) {
                String serverName = player.getServer().getInfo().getName();

                server = ServerInfoUtil.buildServerObjectWithAddress(serverName);
            }
            resp.add("server", server);
        } else {
            JsonObject cached = playerDAO.getPlayerByUuid(uuid);
            if (cached != null) {
                resp.addProperty("name", cached.get("name").getAsString());
                resp.addProperty("uuid", uuid);
                resp.addProperty("online", false);
                resp.add("server", new JsonObject());
            } else {
                resp.addProperty("name", "");
                resp.addProperty("uuid", uuid);
                resp.addProperty("online", false);
                resp.add("server", new JsonObject());
            }
        }
        return resp;
    }

    private JsonObject handleGetMessage(JsonObject req) {
        String uuid = req.has("uuid") && !req.get("uuid").isJsonNull()
                ? req.get("uuid").getAsString() : null;
        int t = req.has("t") ? req.get("t").getAsInt() : 5;
        if (t <= 0) t = 5;
        if (t > 100) t = 100;

        JsonObject resp = new JsonObject();

        if (uuid == null || uuid.isEmpty()) {

            JsonArray history = chatDAO.getHistory(null, null, t, 0);
            JsonArray contents = new JsonArray();
            for (int i = history.size() - 1; i >= 0; i--) {

                JsonObject m = history.get(i).getAsJsonObject();
                contents.add(m.get("content").getAsString());
            }
            resp.addProperty("name", "");
            resp.addProperty("uuid", "");
            resp.addProperty("online", false);
            resp.add("server", new JsonObject());
            resp.add("content", contents);
            return resp;
        }

        JsonObject player = playerDAO.getPlayerByUuid(uuid);
        String playerName = player != null && player.has("name") && !player.get("name").isJsonNull()
                ? player.get("name").getAsString() : "";
        boolean online = player != null && player.has("online") && player.get("online").getAsBoolean();
        String currentServer = player != null && player.has("current_server")
                && !player.get("current_server").isJsonNull()
                ? player.get("current_server").getAsString() : null;

        resp.addProperty("name", playerName);
        resp.addProperty("uuid", uuid);
        resp.addProperty("online", online);

        JsonObject server = currentServer != null
                ? ServerInfoUtil.buildServerObjectWithAddress(currentServer)
                : new JsonObject();
        resp.add("server", server);

        JsonArray contents = new JsonArray();
        JsonArray history = chatDAO.getHistory(null, null, Math.min(t * 5, 200), 0);
        int added = 0;
        for (int i = 0; i < history.size() && added < t; i++) {
            JsonObject m = history.get(i).getAsJsonObject();
            if (uuid.equals(m.get("uuid").getAsString())) {
                contents.add(m.get("content").getAsString());
                added++;
            }
        }

        JsonArray ordered = new JsonArray();
        for (int i = contents.size() - 1; i >= 0; i--) ordered.add(contents.get(i));
        resp.add("content", ordered);

        return resp;
    }

    private JsonObject handleNotifyStaff(JsonObject req) {
        String permission = req.has("permission") ? req.get("permission").getAsString() : "aerreport.staff.alert";
        String message = req.has("message") ? req.get("message").getAsString() : "";

        int count = 0;
        if (!message.isEmpty()) {
            for (ProxiedPlayer pp : ProxyServer.getInstance().getPlayers()) {
                if (pp.hasPermission(permission)) {
                    pp.sendMessage(new net.md_5.bungee.api.chat.TextComponent(message));
                    count++;
                }
            }
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("sent", count);
        return resp;
    }
}
