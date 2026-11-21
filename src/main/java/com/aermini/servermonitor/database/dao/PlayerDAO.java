package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.database.redis.PresenceStore;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.ProxyServer;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlayerDAO {
    private final DatabaseManager db;
    private final RedisManager redis;
    private final PresenceStore presenceStore;

    public PlayerDAO(DatabaseManager db, RedisManager redis, PresenceStore presenceStore) {
        this.db = db;
        this.redis = redis;
        this.presenceStore = presenceStore;
    }

    public JsonObject getPlayerByUuid(String uuid) {
        String cached = redis.get(redis.key("player:" + uuid));
        if (cached != null) {
            try {
                JsonObject obj = com.aermini.servermonitor.util.JsonUtil.parse(cached);
                applyPresence(obj);
                return obj;
            } catch (Exception e) {
                redis.del(redis.key("player:" + uuid));
            }
        }
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject obj = playerFromRs(rs);
                redis.set(redis.key("player:" + uuid), obj.toString(), 300);
                applyPresence(obj);
                return obj;
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public JsonObject getPlayerByName(String name) {
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE name = ? ORDER BY online DESC, update_time DESC LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject obj = playerFromRs(rs);
                redis.set(redis.key("player:" + obj.get("uuid").getAsString()), obj.toString(), 300);
                applyPresence(obj);
                return obj;
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public void upsertPlayer(String uuid, String name, String ip, String server, boolean online, long now) {
        String sql = "INSERT INTO " + db.prefix() + "players " +
                "(uuid, name, ip, current_server, online, fjoin_time, ljoin_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "name = VALUES(name), " +
                "ip = VALUES(ip), " +
                "current_server = VALUES(current_server), " +
                "online = VALUES(online), " +
                "ljoin_time = VALUES(ljoin_time), " +
                "update_time = VALUES(update_time)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setString(4, server);
            ps.setBoolean(5, online);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            if (online) {
                presenceStore.markOnline(uuid, name, ip, server);
            }
            redis.del(redis.key("player:" + uuid));
        } catch (SQLException e) {
            java.util.logging.Logger.getLogger("AerServerMonitor")
                    .warning("upsertPlayer failed for " + uuid + ": " + e.getMessage());
        }
    }

    public void setOffline(String uuid, long now) {
        String sql = "UPDATE " + db.prefix() + "players SET online = FALSE, leave_time = ?, update_time = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, uuid);
            ps.executeUpdate();
            presenceStore.markOffline(uuid);
            redis.del(redis.key("player:" + uuid));
        } catch (SQLException ignored) {}
    }

    public void resetAllOnline() {
        String sql = "UPDATE " + db.prefix() + "players SET online = FALSE WHERE online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void updateServer(String uuid, String server, long now) {
        String sql = "UPDATE " + db.prefix() + "players SET current_server = ?, ljoin_time = ?, update_time = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setString(4, uuid);
            ps.executeUpdate();
            presenceStore.changeServer(uuid, server);
            redis.del(redis.key("player:" + uuid));
        } catch (SQLException ignored) {}
    }

    public void updateBrand(String uuid, String brand) {
        if (uuid == null || brand == null) return;
        String sql = "UPDATE " + db.prefix() + "players SET client_brand = ?, update_time = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, brand);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid);
            ps.executeUpdate();
            redis.del(redis.key("player:" + uuid));
        } catch (SQLException ignored) {}
    }

    public List<JsonObject> getOnlinePlayers() {
        if (presenceStore.isEnabled()) {
            Set<String> uuids = presenceStore.getOnlineUuids();
            if (uuids != null && (!uuids.isEmpty() || hasLocalPlayers())) {
                List<JsonObject> list = new ArrayList<>();
                for (String uuid : uuids) {
                    JsonObject obj = getPlayerByUuid(uuid);
                    if (obj != null) list.add(obj);
                }
                return list;
            }
        }
        List<JsonObject> list = new ArrayList<>();
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(playerFromRs(rs));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    public List<JsonObject> getOnlinePlayers(int limit, int offset) {
        if (presenceStore.isEnabled()) {
            Set<String> uuids = presenceStore.getOnlineUuids();
            if (uuids != null && (!uuids.isEmpty() || hasLocalPlayers())) {
                List<JsonObject> all = new ArrayList<>();
                for (String uuid : uuids) {
                    JsonObject obj = getPlayerByUuid(uuid);
                    if (obj != null) all.add(obj);
                }
                int from = Math.max(offset, 0);
                if (limit <= 0 || from >= all.size()) return new ArrayList<>();
                int to = (int) Math.min((long) from + limit, all.size());
                return new ArrayList<>(all.subList(from, to));
            }
        }
        List<JsonObject> list = new ArrayList<>();
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE online = TRUE LIMIT ? OFFSET ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(playerFromRs(rs));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    public List<JsonObject> getPlayersByServer(String server) {
        if (presenceStore.isEnabled()) {
            Set<String> uuids = presenceStore.getOnlineUuids(server);
            if (uuids != null && (!uuids.isEmpty() || hasLocalPlayers())) {
                List<JsonObject> list = new ArrayList<>();
                for (String uuid : uuids) {
                    JsonObject obj = getPlayerByUuid(uuid);
                    if (obj != null) list.add(obj);
                }
                return list;
            }
        }
        List<JsonObject> list = new ArrayList<>();
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE current_server = ? AND online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, server);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(playerFromRs(rs));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    public int getOnlineCount() {
        if (presenceStore.isEnabled()) {
            int count = presenceStore.getOnlineCount();
            if (count > 0 || !hasLocalPlayers()) return count;
        }
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "players WHERE online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getOnlineCountByServer(String server) {
        if (presenceStore.isEnabled()) {
            int count = presenceStore.getOnlineCount(server);
            if (count > 0 || !hasLocalPlayers()) return count;
        }
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "players WHERE current_server = ? AND online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, server);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    private void applyPresence(JsonObject obj) {
        if (obj == null || !presenceStore.isEnabled()) return;
        String uuid = obj.has("uuid") && !obj.get("uuid").isJsonNull() ? obj.get("uuid").getAsString() : null;
        if (uuid == null || uuid.isEmpty()) return;
        Map<String, String> presence = presenceStore.getPresence(uuid);
        if (presence == null) return;
        if (!presence.isEmpty()) {
            obj.addProperty("online", true);
            String server = presence.get("server");
            obj.addProperty("current_server", server == null || server.isEmpty() ? null : server);
        } else if (redis.isAvailable()) {
            obj.addProperty("online", false);
            obj.addProperty("current_server", (String) null);
        }
    }

    private boolean hasLocalPlayers() {
        try {
            return !ProxyServer.getInstance().getPlayers().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private JsonObject playerFromRs(ResultSet rs) throws SQLException {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", rs.getString("uuid"));
        obj.addProperty("name", rs.getString("name"));
        obj.addProperty("ip", rs.getString("ip"));
        obj.addProperty("current_server", rs.getString("current_server"));
        obj.addProperty("online", rs.getBoolean("online"));
        obj.addProperty("skin", rs.getString("skin"));
        obj.addProperty("client_brand", rs.getString("client_brand"));
        obj.addProperty("fjoin_time", rs.getLong("fjoin_time"));
        obj.addProperty("ljoin_time", rs.getLong("ljoin_time"));
        obj.addProperty("leave_time", rs.getLong("leave_time"));
        return obj;
    }
}