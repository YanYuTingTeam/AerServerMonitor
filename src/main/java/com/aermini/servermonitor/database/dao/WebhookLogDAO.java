package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.database.DatabaseManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.*;

public class WebhookLogDAO {

    private final DatabaseManager db;

    public WebhookLogDAO(DatabaseManager db) {
        this.db = db;
    }

    public void insert(String eventid, String eventType, String url, String body) {
        String sql = "INSERT INTO " + db.prefix() + "webhook_log (eventid, event_type, url, body, status, attempts, time) VALUES (?, ?, ?, ?, 0, 0, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventid);
            ps.setString(2, eventType);
            ps.setString(3, url);
            ps.setString(4, body);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void updateStatus(int id, int status, int attempts) {
        String sql = "UPDATE " + db.prefix() + "webhook_log SET status = ?, attempts = ?, last_attempt = ? WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, status);
            ps.setInt(2, attempts);
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public JsonArray getHistory(int limit, int offset) {
        JsonArray arr = new JsonArray();
        String sql = "SELECT * FROM " + db.prefix() + "webhook_log ORDER BY time DESC LIMIT ? OFFSET ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", rs.getInt("id"));
                obj.addProperty("eventid", rs.getString("eventid"));
                obj.addProperty("event_type", rs.getString("event_type"));
                obj.addProperty("url", rs.getString("url"));
                obj.addProperty("status", rs.getInt("status"));
                obj.addProperty("attempts", rs.getInt("attempts"));
                obj.addProperty("time", rs.getLong("time"));
                obj.addProperty("last_attempt", rs.getLong("last_attempt"));
                arr.add(obj);
            }
        } catch (SQLException ignored) {}
        return arr;
    }

    public JsonObject getById(int id) {
        String sql = "SELECT * FROM " + db.prefix() + "webhook_log WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", rs.getInt("id"));
                obj.addProperty("eventid", rs.getString("eventid"));
                obj.addProperty("event_type", rs.getString("event_type"));
                obj.addProperty("url", rs.getString("url"));
                obj.addProperty("body", rs.getString("body"));
                obj.addProperty("status", rs.getInt("status"));
                obj.addProperty("attempts", rs.getInt("attempts"));
                obj.addProperty("time", rs.getLong("time"));
                obj.addProperty("last_attempt", rs.getLong("last_attempt"));
                return obj;
            }
        } catch (SQLException ignored) {}
        return null;
    }
}
