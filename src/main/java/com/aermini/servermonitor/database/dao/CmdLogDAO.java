package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.database.DatabaseManager;
import java.sql.*;

public class CmdLogDAO {
    private final DatabaseManager db;
    public CmdLogDAO(DatabaseManager db) {
        this.db = db;
    }

    public void insert(String logid, String command, String result, boolean success) {
        String sql = "INSERT INTO " + db.prefix() + "cmd_log (time, logid, command, result, success) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, logid);
            ps.setString(3, command);
            ps.setString(4, result);
            ps.setBoolean(5, success);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
