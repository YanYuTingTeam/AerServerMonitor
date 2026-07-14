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

public class SkinDatabaseManager {

    private final PluginConfig config;
    private final Logger logger;
    private final DatabaseManager mainDb;
    private HikariDataSource dataSource;
    private boolean available = false;
    private String tableName;

    public SkinDatabaseManager(PluginConfig config, Logger logger, DatabaseManager mainDb) {
        this.config = config;
        this.logger = logger;
        this.mainDb = mainDb;
        this.tableName = config.getSkinTable();
    }

    public void init() {
        String type = config.getSkinDbType().toLowerCase();

        if ("monitor".equals(type)) {
            available = true;
            return;
        }

        try {
            HikariConfig hikari = new HikariConfig();
            if ("h2".equals(type)) {
                String path = config.getSkinDbAddress();
                if (path.isEmpty()) path = new File(config.getDataFolder(), "skin.db").getAbsolutePath();
                hikari.setJdbcUrl("jdbc:h2:" + path + ";AUTO_SERVER=TRUE;MODE=MySQL");
                hikari.setDriverClassName("org.h2.Driver");
            } else {
                hikari.setJdbcUrl("jdbc:mysql://" + config.getSkinDbAddress() + "/" + config.getSkinDbName()
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
                hikari.setUsername(config.getSkinDbUsername());
                hikari.setPassword(config.getSkinDbPassword());
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            }

            hikari.setMaximumPoolSize(config.getSkinPoolMaxSize());
            hikari.setMinimumIdle(config.getSkinPoolMinIdle());
            hikari.setPoolName("AerMonitor-SkinPool");

            dataSource = new HikariDataSource(hikari);
            available = true;
        } catch (Exception e) {
            available = false;
            logger.warning("SkinRestorer database not available: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public String getSkinUuid(String playerUuid) {
        if (!available) return playerUuid;

        String type = config.getSkinDbType().toLowerCase();
        Connection conn = null;
        try {
            if ("monitor".equals(type)) {
                conn = mainDb.getConnection();
            } else {
                conn = dataSource.getConnection();
            }

            String sql = "SELECT skin FROM " + tableName + " WHERE uuid = ? LIMIT 1";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("skin");
            }
        } catch (SQLException ignored) {} finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
        return playerUuid;
    }
}
