package com.aermini.servermonitor.database.dao;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
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
    private final RedisManager redis;
    private HikariDataSource dataSource;
    private boolean available = false;
    private String tableName;

    public SkinDatabaseManager(PluginConfig config, Logger logger, DatabaseManager mainDb, RedisManager redis) {
        this.config = config;
        this.logger = logger;
        this.mainDb = mainDb;
        this.redis = redis;
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
                hikari.setDriverClassName(org.h2.Driver.class.getName());
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

        String cacheKey = redis.key("skin:" + playerUuid);
        if (redis.isAvailable()) {
            String cached = redis.get(cacheKey);
            if (cached != null) return cached;
        }

        String type = config.getSkinDbType().toLowerCase();
        String skin = playerUuid;
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
                String value = rs.getString("skin");
                if (value != null && !value.isEmpty()) skin = value;
            }
        } catch (SQLException ignored) {} finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
        if (redis.isAvailable()) {
            redis.set(cacheKey, skin, 3600);
        }
        return skin;
    }
}
