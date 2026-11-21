package com.aermini.servermonitor.database.redis;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BindStore {

    private static final long BIND_TTL_MS = 300_000L;
    private static final int MAX_ATTEMPTS = 5;

    private static final String PUT_SCRIPT =
            "redis.call('HSET',KEYS[1],'task',ARGV[1],'name',ARGV[2],'code',ARGV[3],'created',ARGV[4]) " +
            "redis.call('PEXPIRE',KEYS[1],ARGV[5]) " +
            "redis.call('DEL',KEYS[2]) return 1";

    private static final String VERIFY_SCRIPT =
            "local code=redis.call('HGET',KEYS[1],'code'); if not code then return {} end " +
            "if code ~= ARGV[1] then " +
            "local f=redis.call('INCR',KEYS[2]); " +
            "if f==1 then redis.call('PEXPIRE',KEYS[2],ARGV[2]) end; " +
            "if f>=tonumber(ARGV[3]) then redis.call('DEL',KEYS[1]) end; " +
            "return {} end " +
            "local data=redis.call('HGETALL',KEYS[1]); redis.call('DEL',KEYS[1]); return data";

    private final PluginConfig config;
    private final RedisManager redis;
    private final Logger logger;
    private final Map<String, Entry> localBinds = new ConcurrentHashMap<>();

    public BindStore(PluginConfig config, RedisManager redis, Logger logger) {
        this.config = config;
        this.redis = redis;
        this.logger = logger;
    }

    public void put(AerServerMonitor.PendingBind bind) {
        if (bind == null || bind.uuid == null) return;
        long now = System.currentTimeMillis();
        cleanupLocal(now);
        localBinds.put(bind.uuid, new Entry(bind, now));

        if (!redisReady()) return;
        String key = redis.key("bind:" + bind.uuid);
        String failKey = redis.key("bind:fail:" + bind.uuid);
        Object result = redis.eval(PUT_SCRIPT, List.of(key, failKey),
                List.of(bind.task == null ? "" : bind.task,
                        bind.playerName == null ? "" : bind.playerName,
                        bind.code == null ? "" : bind.code,
                        String.valueOf(now),
                        String.valueOf(BIND_TTL_MS)));
        if (!(result instanceof Number)) {
            logger.warning("BindStore redis put failed for " + bind.uuid);
        }
    }

    public AerServerMonitor.PendingBind get(String uuid) {
        if (uuid == null) return null;
        long now = System.currentTimeMillis();
        cleanupLocal(now);

        if (redisReady()) {
            Map<String, String> data = redis.hgetAll(redis.key("bind:" + uuid));
            AerServerMonitor.PendingBind bind = parse(uuid, data);
            if (bind != null) return bind;
            if (data != null && !data.isEmpty()) {
                logger.warning("BindStore invalid hash data for " + uuid);
            }
        }

        Entry entry = localBinds.get(uuid);
        return validEntry(uuid, entry, now);
    }

    public void remove(String uuid) {
        if (uuid == null) return;
        localBinds.remove(uuid);
        if (!redisReady()) return;
        redis.del(redis.key("bind:" + uuid));
        redis.del(redis.key("bind:fail:" + uuid));
    }

    public AerServerMonitor.PendingBind verifyAndRemove(String uuid, String code) {
        if (uuid == null || code == null) return null;
        long now = System.currentTimeMillis();
        cleanupLocal(now);

        if (redisReady()) {
            Object result = redis.eval(VERIFY_SCRIPT,
                    List.of(redis.key("bind:" + uuid), redis.key("bind:fail:" + uuid)),
                    List.of(code, String.valueOf(BIND_TTL_MS), String.valueOf(MAX_ATTEMPTS)));
            if (result instanceof List) {
                List<?> list = (List<?>) result;
                if (list.isEmpty()) {
                    return localVerify(uuid, code, now);
                }
                AerServerMonitor.PendingBind bind = parseList(uuid, list);
                if (bind == null) {
                    logger.warning("BindStore invalid verify result for " + uuid);
                }
                localBinds.remove(uuid);
                return bind;
            }
        }

        return localVerify(uuid, code, now);
    }

    private AerServerMonitor.PendingBind parse(String uuid, Map<String, String> data) {
        if (data == null || data.isEmpty()) return null;
        String task = data.get("task");
        String name = data.get("name");
        String code = data.get("code");
        if (task == null || name == null || code == null) return null;
        return new AerServerMonitor.PendingBind(task, name, uuid, code);
    }

    private AerServerMonitor.PendingBind parseList(String uuid, List<?> list) {
        String task = null;
        String name = null;
        String code = null;
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String field = toStringValue(list.get(i));
            String value = toStringValue(list.get(i + 1));
            if ("task".equals(field)) {
                task = value;
            } else if ("name".equals(field)) {
                name = value;
            } else if ("code".equals(field)) {
                code = value;
            }
        }
        if (task == null || name == null || code == null) return null;
        return new AerServerMonitor.PendingBind(task, name, uuid, code);
    }

    private String toStringValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[]) return new String((byte[]) value, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    private AerServerMonitor.PendingBind validEntry(String uuid, Entry entry, long now) {
        if (entry == null) return null;
        if (now - entry.created >= BIND_TTL_MS) {
            localBinds.remove(uuid, entry);
            return null;
        }
        return entry.bind;
    }

    private AerServerMonitor.PendingBind localVerify(String uuid, String code, long now) {
        Entry entry = localBinds.get(uuid);
        if (entry == null) return null;
        if (now - entry.created >= BIND_TTL_MS) {
            localBinds.remove(uuid, entry);
            return null;
        }
        if (!code.equals(entry.bind.code)) {
            entry.attempts++;
            if (entry.attempts >= MAX_ATTEMPTS) {
                localBinds.remove(uuid, entry);
            }
            return null;
        }
        localBinds.remove(uuid, entry);
        return entry.bind;
    }

    private void cleanupLocal(long now) {
        localBinds.entrySet().removeIf(entry -> now - entry.getValue().created >= BIND_TTL_MS);
    }

    private boolean redisReady() {
        return redis.isAvailable() && config.isRedisFeatureEnabled("bind-store");
    }

    private static class Entry {
        final AerServerMonitor.PendingBind bind;
        final long created;
        int attempts;

        Entry(AerServerMonitor.PendingBind bind, long created) {
            this.bind = bind;
            this.created = created;
        }
    }
}
