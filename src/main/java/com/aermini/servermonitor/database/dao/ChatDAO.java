package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.util.ServerInfoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class ChatDAO {
    private final DatabaseManager db;
    private SkinDatabaseManager skinDB;

    public ChatDAO(DatabaseManager db) {
        this.db = db;
    }

    public void setSkinDatabaseManager(SkinDatabaseManager skinDB) {
        this.skinDB = skinDB;
    }

    public void insertChat(String name, String uuid, String ip, String server, long time, String msgid, String content) {
        String sql = "INSERT INTO " + db.prefix() + "chat (name, uuid, ip, servername, time, msgid, content) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, uuid);
            ps.setString(3, ip);
            ps.setString(4, server);
            ps.setLong(5, time);
            ps.setString(6, msgid);
            ps.setString(7, content);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void insertChatBatch(List<Object[]> records) {
        if (records.isEmpty()) return;
        String sql = "INSERT INTO " + db.prefix() + "chat (name, uuid, ip, servername, time, msgid, content) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] rec : records) {
                ps.setString(1, (String) rec[0]);
                ps.setString(2, (String) rec[1]);
                ps.setString(3, (String) rec[2]);
                ps.setString(4, (String) rec[3]);
                ps.setLong(5, (Long) rec[4]);
                ps.setString(6, (String) rec[5]);
                ps.setString(7, (String) rec[6]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ignored) {}
    }

    public JsonArray getHistory(Long timeFrom, Long timeTo, int limit, int offset) {
        JsonArray arr = new JsonArray();
        StringBuilder sql = new StringBuilder("SELECT * FROM " + db.prefix() + "chat WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (timeFrom != null) { sql.append(" AND time >= ?"); params.add(timeFrom); }
        if (timeTo != null) { sql.append(" AND time <= ?"); params.add(timeTo); }
        sql.append(" ORDER BY time DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<JsonObject> rawRows = new ArrayList<>();

        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Long) ps.setLong(i + 1, (Long) p);
                else if (p instanceof Integer) ps.setInt(i + 1, (Integer) p);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("msgid", rs.getString("msgid"));
                obj.addProperty("time", rs.getLong("time"));
                obj.addProperty("name", rs.getString("name"));
                obj.addProperty("uuid", rs.getString("uuid"));
                obj.addProperty("ip", rs.getString("ip"));
                obj.addProperty("servername", rs.getString("servername"));
                obj.addProperty("content", rs.getString("content"));
                rawRows.add(obj);
            }
        } catch (SQLException ignored) {}

        if (rawRows.isEmpty()) return arr;

        long dayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        java.util.Set<String> uuidSet = new java.util.HashSet<>();
        for (JsonObject r : rawRows) {
            String u = r.get("uuid").getAsString();
            if (u != null && !u.isEmpty()) uuidSet.add(u);
        }

        java.util.Map<String, int[]> stats = new java.util.HashMap<>();
        if (!uuidSet.isEmpty()) {
            String inClause = String.join(",", java.util.Collections.nCopies(uuidSet.size(), "?"));
            String countSql = "SELECT uuid, COUNT(*) AS c, " +
                    "SUM(CASE WHEN time >= ? THEN 1 ELSE 0 END) AS dc " +
                    "FROM " + db.prefix() + "chat WHERE uuid IN (" + inClause + ") GROUP BY uuid";
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setLong(1, dayStart);
                int i = 2;
                for (String u : uuidSet) { ps.setString(i++, u); }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    stats.put(rs.getString("uuid"), new int[]{rs.getInt("c"), rs.getInt("dc")});
                }
            } catch (SQLException ignored) {}
        }

        for (JsonObject r : rawRows) {
            String uuid = r.get("uuid").getAsString();
            String serverName = r.has("servername") && !r.get("servername").isJsonNull()
                    ? r.get("servername").getAsString() : "";

            JsonObject obj = new JsonObject();
            obj.addProperty("msgid", r.get("msgid").getAsString());
            obj.addProperty("time", r.get("time").getAsLong());
            obj.addProperty("name", r.get("name").getAsString());
            obj.addProperty("uuid", uuid);

            JsonObject server = ServerInfoUtil.buildServerObject(serverName);
            String skin = skinDB != null ? skinDB.getSkinUuid(uuid) : uuid;
            server.addProperty("skin", skin);
            obj.add("server", server);

            int[] st = stats.get(uuid);
            obj.addProperty("count", st != null ? st[0] : 0);
            obj.addProperty("daycount", st != null ? st[1] : 0);
            obj.addProperty("content", r.get("content").getAsString());
            arr.add(obj);
        }

        return arr;
    }

    public JsonObject getMsgById(String msgid) {
        String sql = "SELECT * FROM " + db.prefix() + "chat WHERE msgid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msgid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("msgid", rs.getString("msgid"));
                obj.addProperty("time", rs.getLong("time"));
                obj.addProperty("name", rs.getString("name"));
                obj.addProperty("uuid", rs.getString("uuid"));
                obj.addProperty("ip", rs.getString("ip"));
                obj.addProperty("servername", rs.getString("servername"));
                obj.addProperty("content", rs.getString("content"));
                return obj;
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public int getTotalCount(String uuid) {
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "chat WHERE uuid = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public int getDayCount(String uuid, long dayStart) {
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "chat WHERE uuid = ? AND time >= ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setLong(2, dayStart);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }
}
