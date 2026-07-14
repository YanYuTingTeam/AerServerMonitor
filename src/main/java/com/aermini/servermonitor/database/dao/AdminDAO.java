package com.aermini.servermonitor.database.dao;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.aermini.servermonitor.database.DatabaseManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.*;

public class AdminDAO {
    private final DatabaseManager db;
    public AdminDAO(DatabaseManager db) {
        this.db = db;
    }

    public void ensureDefaultAdmin(String username, String password) {
        String checkSql = "SELECT COUNT(*) FROM " + db.prefix() + "admins WHERE username = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
        } catch (SQLException e) {
            return;
        }

        String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        String sql = "INSERT INTO " + db.prefix() + "admins (username, password_hash, role, is_default, created_at) VALUES (?, ?, 'admin', TRUE, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public JsonObject authenticate(String username, String password) {
        String sql = "SELECT * FROM " + db.prefix() + "admins WHERE username = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String hash = rs.getString("password_hash");
                if (BCrypt.verifyer().verify(password.toCharArray(), hash).verified) return adminFromRs(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public JsonObject getById(int id) {
        String sql = "SELECT * FROM " + db.prefix() + "admins WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return adminFromRs(rs);
        } catch (SQLException ignored) {}
        return null;
    }

    public void updateLastLogin(int id, String ip) {
        String sql = "UPDATE " + db.prefix() + "admins SET last_login = ?, last_login_ip = ? WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setInt(3, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public boolean changePassword(int id, String newPassword) {
        String hash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray());
        String sql = "UPDATE " + db.prefix() + "admins SET password_hash = ?, is_default = FALSE, token_version = token_version + 1 WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public int getTokenVersion(int id) {
        String sql = "SELECT token_version FROM " + db.prefix() + "admins WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public boolean hasDefaultAdmin() {
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "admins WHERE is_default = TRUE";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException ignored) {}
        return false;
    }

    public JsonArray list() {
        JsonArray arr = new JsonArray();
        String sql = "SELECT id, username, role, is_default, token_version, created_at, last_login, last_login_ip FROM "
                + db.prefix() + "admins ORDER BY id ASC";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) arr.add(adminFromRs(rs));
        } catch (SQLException ignored) {}
        return arr;
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM " + db.prefix() + "admins";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public boolean create(String username, String password, String role) {
        if (!username.matches("^[a-zA-Z0-9_]{3,32}$")) return false;
        if (password == null || password.length() < 6) return false;
        if (role == null || role.isEmpty()) role = "admin";

        String checkSql = "SELECT COUNT(*) FROM " + db.prefix() + "admins WHERE username = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return false;
        } catch (SQLException e) {
            return false;
        }

        String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        String sql = "INSERT INTO " + db.prefix() + "admins (username, password_hash, role, is_default, created_at) VALUES (?, ?, ?, FALSE, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, role);
            ps.setLong(4, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean delete(int id) {
        String sql = "DELETE FROM " + db.prefix() + "admins WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean resetPassword(int id, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) return false;
        String hash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray());
        String sql = "UPDATE " + db.prefix() + "admins SET password_hash = ?, is_default = FALSE, token_version = token_version + 1 WHERE id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private JsonObject adminFromRs(ResultSet rs) throws SQLException {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", rs.getInt("id"));
        obj.addProperty("username", rs.getString("username"));
        obj.addProperty("role", rs.getString("role"));
        obj.addProperty("is_default", rs.getBoolean("is_default"));
        obj.addProperty("token_version", rs.getInt("token_version"));
        obj.addProperty("created_at", rs.getLong("created_at"));
        obj.addProperty("last_login", rs.getLong("last_login"));
        obj.addProperty("last_login_ip", rs.getString("last_login_ip"));
        return obj;
    }
}
