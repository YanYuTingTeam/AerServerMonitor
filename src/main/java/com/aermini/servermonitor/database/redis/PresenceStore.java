package com.aermini.servermonitor.database.redis;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class PresenceStore {

    private static final long PRESENCE_TTL_MS = 90000L;
    private static final long HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long CLEANUP_INTERVAL_MS = 60000L;
    private static final int CLEAN_BATCH_SIZE = 500;

    private final PluginConfig config;
    private final RedisManager redis;
    private final Logger logger;
    private final String instanceId;

    private volatile boolean enabled = false;
    private volatile boolean running = false;
    private Thread heartbeatThread;
    private Thread cleanupThread;

    public PresenceStore(PluginConfig config, RedisManager redis, Logger logger) {
        this.config = config;
        this.redis = redis;
        this.logger = logger;
        this.instanceId = config.getRedisInstanceId();
    }

    public void start() {
        if (running) return;
        if (!config.isRedisFeatureEnabled("presence")) {
            enabled = false;
            return;
        }
        enabled = true;
        running = true;
        if (redis.isAvailable()) {
            try {
                clearInstance();
            } catch (Exception e) {
                logger.warning("Presence clear instance failed: " + e.getMessage());
            }
        }

        heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    heartbeat();
                } catch (Exception e) {
                    logger.warning("Presence heartbeat failed: " + e.getMessage());
                }
            }
        }, "AerMonitor-PresenceHeartbeat");
        heartbeatThread.setDaemon(true);

        cleanupThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    cleanup();
                } catch (Exception e) {
                    logger.warning("Presence cleanup failed: " + e.getMessage());
                }
            }
        }, "AerMonitor-PresenceCleanup");
        cleanupThread.setDaemon(true);

        heartbeatThread.start();
        cleanupThread.start();
    }

    public void shutdown() {
        running = false;
        enabled = false;
        if (heartbeatThread != null) heartbeatThread.interrupt();
        if (cleanupThread != null) cleanupThread.interrupt();
    }

    public boolean isEnabled() {
        return enabled && redis.isAvailable();
    }

    public void markOnline(String uuid, String name, String ip, String server) {
        if (!isEnabled() || uuid == null) return;
        try {
            String presenceKey = redis.key("presence:" + uuid);
            Map<String, String> fields = new HashMap<>();
            fields.put("name", safe(name));
            fields.put("ip", safe(ip));
            fields.put("server", safe(server));
            fields.put("proxy_id", safe(instanceId));
            fields.put("last_seen", String.valueOf(System.currentTimeMillis()));
            redis.hset(presenceKey, fields);
            redis.pexpire(presenceKey, PRESENCE_TTL_MS);
            redis.sadd(redis.key("online:all"), uuid);
            if (hasText(server)) {
                redis.sadd(redis.key("online:server:" + server), uuid);
            }
            redis.sadd(redis.key("presence:proxy:" + instanceId), uuid);
        } catch (Exception e) {
            logger.warning("Presence markOnline failed for " + uuid + ": " + e.getMessage());
        }
    }

    public void markOffline(String uuid) {
        if (!isEnabled() || uuid == null) return;
        try {
            String presenceKey = redis.key("presence:" + uuid);
            String server = redis.hget(presenceKey, "server");
            redis.del(presenceKey);
            redis.srem(redis.key("online:all"), uuid);
            if (hasText(server)) {
                redis.srem(redis.key("online:server:" + server), uuid);
            }
            redis.srem(redis.key("presence:proxy:" + instanceId), uuid);
        } catch (Exception e) {
            logger.warning("Presence markOffline failed for " + uuid + ": " + e.getMessage());
        }
    }

    public void changeServer(String uuid, String server) {
        if (!isEnabled() || uuid == null) return;
        try {
            String presenceKey = redis.key("presence:" + uuid);
            String oldServer = redis.hget(presenceKey, "server");
            if (hasText(oldServer) && !oldServer.equals(server)) {
                redis.srem(redis.key("online:server:" + oldServer), uuid);
            }
            if (hasText(server)) {
                redis.sadd(redis.key("online:server:" + server), uuid);
            }
            redis.hset(presenceKey, "server", safe(server));
            redis.hset(presenceKey, "last_seen", String.valueOf(System.currentTimeMillis()));
            redis.pexpire(presenceKey, PRESENCE_TTL_MS);
        } catch (Exception e) {
            logger.warning("Presence changeServer failed for " + uuid + ": " + e.getMessage());
        }
    }

    public void clearInstance() {
        if (!redis.isAvailable()) return;
        Set<String> uuids = redis.smembers(redis.key("presence:proxy:" + instanceId));
        if (uuids != null) {
            int cleared = 0;
            for (String uuid : uuids) {
                if (uuid == null) continue;
                if (cleared >= 200) break;
                String presenceKey = redis.key("presence:" + uuid);
                String server = redis.hget(presenceKey, "server");
                redis.del(presenceKey);
                redis.srem(redis.key("online:all"), uuid);
                if (hasText(server)) {
                    redis.srem(redis.key("online:server:" + server), uuid);
                }
                cleared++;
            }
        }
        redis.del(redis.key("presence:proxy:" + instanceId));
    }

    public Set<String> getOnlineUuids() {
        if (!isEnabled()) return null;
        Set<String> uuids = redis.smembers(redis.key("online:all"));
        return uuids == null ? null : uuids;
    }

    public Set<String> getOnlineUuids(String server) {
        if (!isEnabled() || !hasText(server)) return null;
        Set<String> uuids = redis.smembers(redis.key("online:server:" + server));
        return uuids == null ? null : uuids;
    }

    public int getOnlineCount() {
        if (!isEnabled()) return -1;
        Long count = redis.scard(redis.key("online:all"));
        return count == null ? -1 : count.intValue();
    }

    public int getOnlineCount(String server) {
        if (!isEnabled() || !hasText(server)) return -1;
        Long count = redis.scard(redis.key("online:server:" + server));
        return count == null ? -1 : count.intValue();
    }

    public Map<String, String> getPresence(String uuid) {
        if (!isEnabled() || uuid == null) return null;
        return redis.hgetAllOrNull(redis.key("presence:" + uuid));
    }

    public boolean isOnline(String uuid) {
        if (!isEnabled() || uuid == null) return false;
        return redis.exists(redis.key("presence:" + uuid));
    }

    private void heartbeat() {
        if (!isEnabled()) return;
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            String uuid = player.getUniqueId().toString();
            String server = player.getServer() != null ? player.getServer().getInfo().getName() : null;
            String presenceKey = redis.key("presence:" + uuid);
            Map<String, String> fields = new HashMap<>();
            fields.put("name", safe(player.getName()));
            String ip = "";
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                ip = player.getAddress().getAddress().getHostAddress();
            }
            fields.put("ip", safe(ip));
            fields.put("server", safe(server));
            fields.put("proxy_id", safe(instanceId));
            fields.put("last_seen", String.valueOf(System.currentTimeMillis()));
            redis.hset(presenceKey, fields);
            redis.pexpire(presenceKey, PRESENCE_TTL_MS);
            redis.sadd(redis.key("online:all"), uuid);
            if (hasText(server)) {
                redis.sadd(redis.key("online:server:" + server), uuid);
            }
            redis.sadd(redis.key("presence:proxy:" + instanceId), uuid);
        }
        redis.expire(redis.key("presence:proxy:" + instanceId), 604800);
    }

    private void cleanup() {
        if (!isEnabled()) return;
        for (String server : ProxyServer.getInstance().getServers().keySet()) {
            cleanSet(redis.key("online:server:" + server), server);
        }
        cleanSet(redis.key("online:all"), null);
    }

    private void cleanSet(String setKey, String server) {
        Set<String> members = redis.smembers(setKey);
        if (members == null || members.isEmpty()) return;
        List<String> list = new ArrayList<>(members);
        for (int i = 0; i < list.size(); i += CLEAN_BATCH_SIZE) {
            int end = Math.min(i + CLEAN_BATCH_SIZE, list.size());
            List<String> batch = list.subList(i, end);
            for (String uuid : batch) {
                if (uuid == null) continue;
                Map<String, String> presence = redis.hgetAllOrNull(redis.key("presence:" + uuid));
                if (presence == null) continue;
                if (presence.isEmpty()) {
                    redis.srem(setKey, uuid);
                    continue;
                }
                long lastSeen = parseLong(presence.get("last_seen"));
                if (lastSeen > 0 && System.currentTimeMillis() - lastSeen > PRESENCE_TTL_MS * 2) {
                    redis.del(redis.key("presence:" + uuid));
                    redis.srem(setKey, uuid);
                    continue;
                }
                if (server != null && !server.equals(presence.get("server"))) {
                    redis.srem(setKey, uuid);
                }
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
