package com.aermini.servermonitor.database;

import com.aermini.servermonitor.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

public class DatabaseManager {
    private final PluginConfig config;
    private final Logger logger;
    private HikariDataSource dataSource;
    private String tablePrefix;

    public DatabaseManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.tablePrefix = config.getDbTablePrefix();
    }

    public void init() throws SQLException {
        HikariConfig hikari = new HikariConfig();

        String type = config.getDbType().toLowerCase();
        if ("h2".equals(type)) {
            File dbFile = new File(config.getDataFolder(), "monitor.db");
            hikari.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath() + ";AUTO_SERVER=TRUE;MODE=MySQL");
            hikari.setDriverClassName("org.h2.Driver");
        } else {
            hikari.setJdbcUrl("jdbc:mysql://" + config.getDbAddress() + "/" + config.getDbName()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8");
            hikari.setUsername(config.getDbUsername());
            hikari.setPassword(config.getDbPassword());
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }

        hikari.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikari.setMinimumIdle(config.getDbPoolMinIdle());
        hikari.setConnectionTimeout(config.getDbPoolConnTimeout());
        hikari.setIdleTimeout(config.getDbPoolIdleTimeout());
        hikari.setMaxLifetime(config.getDbPoolMaxLifetime());
        hikari.setKeepaliveTime(config.getDbPoolKeepalive());
        hikari.setPoolName("AerMonitor-MainPool");
        dataSource = new HikariDataSource(hikari);
        createTables();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public String prefix() {
        return tablePrefix;
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            boolean isH2 = config.getDbType().equalsIgnoreCase("h2");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "name VARCHAR(16), " +
                    "ip VARCHAR(45), " +
                    "current_server VARCHAR(64), " +
                    "online BOOLEAN DEFAULT FALSE, " +
                    "skin VARCHAR(64), " +
                    "client_brand VARCHAR(64), " +
                    "fjoin_time BIGINT, " +
                    "ljoin_time BIGINT, " +
                    "leave_time BIGINT, " +
                    "update_time BIGINT" +
                    ")");

            migratePlayersColumn(conn, isH2);
            migrateBrandColumn(conn, isH2);

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "chat (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(16), " +
                    "uuid VARCHAR(36), " +
                    "ip VARCHAR(45), " +
                    "servername VARCHAR(64), " +
                    "time BIGINT, " +
                    "msgid VARCHAR(64), " +
                    "content TEXT" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "cmd_log (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "time BIGINT, " +
                    "logid VARCHAR(64), " +
                    "command TEXT, " +
                    "result TEXT, " +
                    "success BOOLEAN" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "webhook_log (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "eventid VARCHAR(64), " +
                    "event_type VARCHAR(32), " +
                    "url VARCHAR(512), " +
                    "body TEXT, " +
                    "status INT DEFAULT 0, " +
                    "attempts INT DEFAULT 0, " +
                    "time BIGINT, " +
                    "last_attempt BIGINT" +
                    ")");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "admins (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "username VARCHAR(32) UNIQUE, " +
                    "password_hash VARCHAR(128), " +
                    "role VARCHAR(16) DEFAULT 'admin', " +
                    "is_default BOOLEAN DEFAULT FALSE, " +
                    "token_version INT DEFAULT 0, " +
                    "created_at BIGINT, " +
                    "last_login BIGINT, " +
                    "last_login_ip VARCHAR(45)" +
                    ")");

            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_online ON " + tablePrefix + "players(online)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_server ON " + tablePrefix + "players(current_server)");

                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_name ON " + tablePrefix + "players(name)");
                } catch (SQLException ignored) {}

                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_online_server ON " + tablePrefix + "players(online, current_server)");
                } catch (SQLException ignored) {}
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "chat_time ON " + tablePrefix + "chat(time)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "chat_uuid ON " + tablePrefix + "chat(uuid)");

                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "chat_uuid_time ON " + tablePrefix + "chat(uuid, time)");
                } catch (SQLException ignored) {}
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "webhook_status ON " + tablePrefix + "webhook_log(status)");
            } catch (SQLException ignored) {
            }
        }
    }

    private void migratePlayersColumn(Connection conn, boolean isH2) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            boolean hasCurrentServer = false;
            boolean hasServername = false;
            try (ResultSet rs = meta.getColumns(null, null, tablePrefix + "players", "current_server")) {
                if (rs.next()) hasCurrentServer = true;
            }
            try (ResultSet rs = meta.getColumns(null, null, tablePrefix + "players", "servername")) {
                if (rs.next()) hasServername = true;
            }

            if (!hasCurrentServer) {
                try (Statement s = conn.createStatement()) {
                    if (hasServername) {

                        if (isH2) {
                            s.executeUpdate("ALTER TABLE " + tablePrefix + "players ALTER COLUMN servername RENAME TO current_server");
                        } else {
                            s.executeUpdate("ALTER TABLE " + tablePrefix + "players CHANGE COLUMN servername current_server VARCHAR(64)");
                        }
                        logger.info("[DB] Migrated column 'servername' -> 'current_server' in " + tablePrefix + "players");
                    } else {

                        s.executeUpdate("ALTER TABLE " + tablePrefix + "players ADD COLUMN current_server VARCHAR(64)");
                        logger.info("[DB] Added missing column 'current_server' to " + tablePrefix + "players");
                    }
                }
            }
        } catch (SQLException e) {
            logger.warning("[DB] Players column migration skipped: " + e.getMessage());
        }
    }

    private void migrateBrandColumn(Connection conn, boolean isH2) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            boolean hasBrand = false;
            try (ResultSet rs = meta.getColumns(null, null, tablePrefix + "players", "client_brand")) {
                if (rs.next()) hasBrand = true;
            }
            if (!hasBrand) {
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("ALTER TABLE " + tablePrefix + "players ADD COLUMN client_brand VARCHAR(64)");
                    logger.info("[DB] Added missing column 'client_brand' to " + tablePrefix + "players");
                }
            }
        } catch (SQLException e) {
            logger.warning("[DB] client_brand migration skipped: " + e.getMessage());
        }
    }
}
