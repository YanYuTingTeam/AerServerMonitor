package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.DatabaseManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

public class FastLoginDatabaseManager {

    private final PluginConfig config;
    private final Logger logger;
    private final DatabaseManager mainDb;
    private HikariDataSource dataSource;
    private boolean available = false;
    private String tableName;

    public FastLoginDatabaseManager(PluginConfig config, Logger logger, DatabaseManager mainDb) {
        this.config = config;
        this.logger = logger;
        this.mainDb = mainDb;
        this.tableName = config.getFastLoginTable();
    }

    public void init() {
        String type = config.getFastLoginDbType().toLowerCase();

        if ("monitor".equals(type)) {
            available = true;
            return;
        }

        try {
            HikariConfig hikari = new HikariConfig();
            if ("h2".equals(type)) {
                String path = config.getFastLoginDbAddress();
                if (path.isEmpty()) path = new File(config.getDataFolder(), "fastlogin.db").getAbsolutePath();
                hikari.setJdbcUrl("jdbc:h2:" + path + ";AUTO_SERVER=TRUE;MODE=MySQL");
                hikari.setDriverClassName("org.h2.Driver");
            } else {
                hikari.setJdbcUrl("jdbc:mysql://" + config.getFastLoginDbAddress() + "/" + config.getFastLoginDbName()
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
                hikari.setUsername(config.getFastLoginDbUsername());
                hikari.setPassword(config.getFastLoginDbPassword());
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            }

            hikari.setMaximumPoolSize(config.getFastLoginPoolMaxSize());
            hikari.setMinimumIdle(config.getFastLoginPoolMinIdle());
            hikari.setPoolName("AerMonitor-FastLoginPool");

            dataSource = new HikariDataSource(hikari);
            available = true;
        } catch (Exception e) {
            available = false;
            logger.warning("FastLogin database not available: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public String getPremiumUuid(String playerName) {
        if (!available) return null;

        String type = config.getFastLoginDbType().toLowerCase();
        Connection conn = null;
        try {
            if ("monitor".equals(type)) {
                conn = mainDb.getConnection();
            } else {
                conn = dataSource.getConnection();
            }

            String sql = "SELECT UUID FROM " + tableName + " WHERE Name = ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return toDashedUuid(rs.getString("UUID"));
            }
        } catch (SQLException ignored) {} finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
        return null;
    }

    // FastLogin 存的是无横线32位格式, 这里补回横线和网站侧统一
    private static String toDashedUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 36) return s;
        if (s.length() != 32) return null;
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20);
    }
}
