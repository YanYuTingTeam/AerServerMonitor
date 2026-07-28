package com.aermini.servermonitor.config;

import com.google.gson.Gson;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class PluginConfig {
    private final File dataFolder;
    private Configuration config;
    private final Gson gson = new Gson();
    public PluginConfig(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void load() throws IOException {
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) Files.copy(in, configFile.toPath());
            }
        }
        config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
    }

    public void reload() throws IOException { load(); }

    public File getDataFolder() {
        return dataFolder;
    }

    public String getDbType() { return config.getString("database.monitor.type", "h2"); }
    public String getDbAddress() { return config.getString("database.monitor.address", "localhost:3306"); }
    public String getDbUsername() { return config.getString("database.monitor.username", "root"); }
    public String getDbPassword() { return config.getString("database.monitor.password", ""); }
    public String getDbName() { return config.getString("database.monitor.database", "db"); }
    public String getDbTablePrefix() { return config.getString("database.monitor.table_prefix", "asmonitor_"); }
    public int getDbPoolMaxSize() { return config.getInt("database.monitor.pool.maximum-pool-size", 10); }
    public int getDbPoolMinIdle() { return config.getInt("database.monitor.pool.minimum-idle", 2); }
    public long getDbPoolConnTimeout() { return config.getLong("database.monitor.pool.connection-timeout", 30000); }
    public long getDbPoolIdleTimeout() { return config.getLong("database.monitor.pool.idle-timeout", 600000); }
    public long getDbPoolMaxLifetime() { return config.getLong("database.monitor.pool.max-lifetime", 1800000); }
    public long getDbPoolKeepalive() { return config.getLong("database.monitor.pool.keepalive-time", 30000); }

    public String getSkinDbType() { return config.getString("database.skin.type", "h2"); }
    public String getSkinDbAddress() { return config.getString("database.skin.address", ""); }
    public String getSkinDbUsername() { return config.getString("database.skin.username", ""); }
    public String getSkinDbPassword() { return config.getString("database.skin.password", ""); }
    public String getSkinDbName() { return config.getString("database.skin.database", ""); }
    public String getSkinTable() { return config.getString("database.skin.table", "skinr_players"); }
    public int getSkinPoolMaxSize() { return config.getInt("database.skin.pool.maximum-pool-size", 5); }
    public int getSkinPoolMinIdle() { return config.getInt("database.skin.pool.minimum-idle", 1); }
    
    public String getFastLoginDbType() { return config.getString("database.fastlogin.type", "mysql"); }
    public String getFastLoginDbAddress() { return config.getString("database.fastlogin.address", "localhost:3306"); }
    public String getFastLoginDbUsername() { return config.getString("database.fastlogin.username", ""); }
    public String getFastLoginDbPassword() { return config.getString("database.fastlogin.password", ""); }
    public String getFastLoginDbName() { return config.getString("database.fastlogin.database", ""); }
    public String getFastLoginTable() { return config.getString("database.fastlogin.table", "premium"); }
    public int getFastLoginPoolMaxSize() { return config.getInt("database.fastlogin.pool.maximum-pool-size", 5); }
    public int getFastLoginPoolMinIdle() { return config.getInt("database.fastlogin.pool.minimum-idle", 1); }

    public String getRedisHost() { return config.getString("redis.host", "127.0.0.1"); }
    public int getRedisPort() { return config.getInt("redis.port", 6379); }
    public String getRedisPassword() { return config.getString("redis.password", ""); }
    public int getRedisDatabase() { return config.getInt("redis.database", 0); }
    public int getRedisPoolMaxTotal() { return config.getInt("redis.pool.max-total", 16); }
    public int getRedisPoolMaxIdle() { return config.getInt("redis.pool.max-idle", 8); }
    public int getRedisPoolMinIdle() { return config.getInt("redis.pool.min-idle", 2); }
    public int getRedisRetryMaxAttempts() { return config.getInt("redis.retry.max-attempts", 3); }
    public long getRedisRetryInterval() { return config.getLong("redis.retry.interval-ms", 5000); }
    public long getRedisHealthCheckInterval() { return config.getLong("redis.retry.health-check-interval-ms", 10000); }
    public int getRedisDegradedThreshold() { return config.getInt("redis.retry.degraded-threshold", 3); }

    public Map<String, String> getPostUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        Configuration section = config.getSection("postUrls");
        if (section != null) {
            for (String key : section.getKeys()) {
                String val = section.getString(key, "");
                if (!val.isEmpty()) urls.put(key, val);
            }
        }
        return urls;
    }

    public int getHttpPort() { return config.getInt("options.http-port", 8009); }
    public int getWsPort() { return config.getInt("options.ws-port", 8010); }
    public String getHmacSalt() { return config.getString("options.hmac-salt", "AerServerMonitor"); }
    public String getHmacOrigin() { return config.getString("options.hmac-origin", "AerServerMonitor"); }
    public String getHmacAlgorithm() { return config.getString("options.hmac-algorithm", "HmacMD5"); }
    public boolean isExecCmdAsPlayerChat() { return config.getBoolean("options.exec-cmd-as-playerChat", false); }
    public long getHeaderATimeOffset() { return config.getLong("options.header-a-timeoffset", 15000); }
    public int getMaxRequestPerTime() { return config.getInt("options.max-request-per-time", 3); }
    public long getPerTime() { return config.getLong("options.per-time", 2000); }
    public long getBannedTime() { return config.getLong("options.banned-time", 60000); }

    public boolean isSsrfEnabled() { return config.getBoolean("options.ssrf-protection.enabled", true); }
    public String getSsrfMode() { return config.getString("options.ssrf-protection.mode", "whitelist"); }
    public List<String> getSsrfWhitelist() {
        return getStringList("options.ssrf-protection.whitelist");
    }
    public List<String> getSsrfBlacklist() {
        return getStringList("options.ssrf-protection.blacklist");
    }

    public int getAsyncEventPoolSize() { return config.getInt("options.async.event-pool-size", 4); }
    public int getAsyncEventPoolQueue() { return config.getInt("options.async.event-pool-queue", 5000); }
    public int getAsyncPostPoolSize() { return config.getInt("options.async.post-pool-size", 2); }
    public int getAsyncPostPoolQueue() { return config.getInt("options.async.post-pool-queue", 2000); }
    public int getAsyncPostRetryMax() { return config.getInt("options.async.post-retry-max", 3); }
    public List<Integer> getAsyncPostRetryIntervals() {
        List<Integer> result = new ArrayList<>();
        for (String s : getStringList("options.async.post-retry-interval-ms")) {
            try { result.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
        }
        if (result.isEmpty()) { result.add(1000); result.add(5000); result.add(30000); }
        return result;
    }
    public long getAsyncLogFlushInterval() { return config.getLong("options.async.log-flush-interval-ms", 100); }
    public int getAsyncChatBatchSize() { return config.getInt("options.async.chat-batch-size", 50); }
    public long getAsyncChatBatchInterval() { return config.getLong("options.async.chat-batch-interval-ms", 100); }
    public int getAsyncWebhookQueueMax() { return config.getInt("options.async.webhook-queue-max", 5000); }

    public int getWsMaxConnections() { return config.getInt("options.ws.max-connections", 500); }
    public long getWsPingInterval() { return config.getLong("options.ws.ping-interval-ms", 30000); }
    public long getWsPongTimeout() { return config.getLong("options.ws.pong-timeout-ms", 10000); }

    public boolean isDashboardEnabled() { return config.getBoolean("options.dashboard.enabled", true); }
    public String getDashboardPath() { return config.getString("options.dashboard.path", "/dashboard"); }
    public long getDashboardSessionExpire() { return config.getLong("options.dashboard.session-expire", 86400000); }
    public String getDashboardSecret() { return config.getString("options.dashboard.secret", "CHANGE_ME_TO_RANDOM_STRING"); }

    public List<String> getAllowedOrigins() { return getStringList("options.dashboard.allowed-origins"); }

    public boolean isCsrfProtectionEnabled() { return config.getBoolean("options.dashboard.csrf-protection", true); }
    public int getLoginMaxAttempts() { return config.getInt("options.dashboard.login-security.max-attempts", 5); }
    public long getLoginWindowMs() { return config.getLong("options.dashboard.login-security.window-ms", 300000); }
    public long getLoginLockoutMs() { return config.getLong("options.dashboard.login-security.lockout-ms", 300000); }
    public int getLoginPermanentBanThreshold() { return config.getInt("options.dashboard.login-security.permanent-ban-threshold", 3); }
    public String getLoginBanScope() { return config.getString("options.dashboard.login-security.ban-scope", "ip-24"); }
    public long getLoginCleanBanInterval() { return config.getLong("options.dashboard.login-security.clean-ban-interval-ms", 86400000); }

    public String getAdminUsername() { return config.getString("options.admin-username", "admin"); }
    public String getAdminPassword() { return config.getString("options.admin-password", "AerServerMonitor"); }

    public String getBindCallbackUrl() { return config.getString("options.bind-callback-url", ""); }
    public String getBindMessage() { return config.getString("options.bind-message", "§f服务器刚刚收到了来自&6Space&f的账号绑定申请. §f你的验证码是: §a{code}§f. 输入 §b/verify {code} §f完成绑定.\n&c&l注意! &r&f如果不是您&e本人操作&f, &c请勿输入该指令对账户进行绑定&f!"); }

    public boolean isPrintChat() { return config.getBoolean("logs.print-chat", false); }
    public boolean isPrintRequest() { return config.getBoolean("logs.print-request", false); }
    public int getLogRetentionDays() { return config.getInt("logs.file-retention-days", 30); }
    public int getLogMaxSizeMb() { return config.getInt("logs.file-max-size-mb", 100); }

    private List<String> getStringList(String path) {
        List<String> result = new ArrayList<>();
        if (config.get(path) instanceof List) {
            for (Object o : config.getList(path)) {
                result.add(o.toString());
            }
        } else {
            String val = config.getString(path, "");
            if (!val.isEmpty()) result.add(val);
        }
        return result;
    }
}
