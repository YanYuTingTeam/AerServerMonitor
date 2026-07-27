package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
import com.google.gson.JsonObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PlayerDAO {
    private final DatabaseManager db;
    private final RedisManager redis;

    public PlayerDAO(DatabaseManager db, RedisManager redis) {
        this.db = db;
        this.redis = redis;
    }

    public JsonObject getPlayerByUuid(String uuid) {
        String cached = redis.get("aermonitor:player:" + uuid);
        if (cached != null) return com.aermini.servermonitor.util.JsonUtil.parse(cached);
        String sql = "SELECT * FROM " + db.prefix() + "players WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject obj = playerFromRs(rs);
                redis.set("aermonitor:player:" + uuid, obj.toString(), 300);
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
                redis.set("aermonitor:player:" + obj.get("uuid").getAsString(), obj.toString(), 300);
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
            redis.del("aermonitor:player:" + uuid);
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
            redis.del("aermonitor:player:" + uuid);
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
            redis.del("aermonitor:player:" + uuid);
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
            redis.del("aermonitor:player:" + uuid);
        } catch (SQLException ignored) {}
    }

    public List<JsonObject> getOnlinePlayers() {
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
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "players WHERE online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getOnlineCountByServer(String server) {
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "players WHERE current_server = ? AND online = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, server);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
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