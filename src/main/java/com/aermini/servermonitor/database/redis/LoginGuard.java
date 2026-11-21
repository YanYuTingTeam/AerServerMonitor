package com.aermini.servermonitor.database.redis;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LoginGuard {

    private static final long LOCKCOUNT_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private static final String FAILURE_SCRIPT =
            "if redis.call('EXISTS', KEYS[4]) == 1 then return 4 end " +
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end " +
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
            "if c >= tonumber(ARGV[2]) then " +
            "redis.call('DEL', KEYS[1]) " +
            "redis.call('SET', KEYS[2], '1') " +
            "redis.call('PEXPIRE', KEYS[2], ARGV[3]) " +
            "local lc = redis.call('INCR', KEYS[3]) " +
            "if lc == 1 then redis.call('PEXPIRE', KEYS[3], ARGV[5]) end " +
            "if lc >= tonumber(ARGV[4]) then " +
            "redis.call('SET', KEYS[4], '1') " +
            "return 3 end " +
            "return 1 end " +
            "return 0";

    private final PluginConfig config;
    private final RedisManager redis;
    private final Logger logger;

    private final Map<String, long[]> failCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> localLocks = new ConcurrentHashMap<>();
    private final Map<String, Integer> localLockCounts = new ConcurrentHashMap<>();
    private final Set<String> localPerms = ConcurrentHashMap.newKeySet();

    public LoginGuard(PluginConfig config, RedisManager redis, Logger logger) {
        this.config = config;
        this.redis = redis;
        this.logger = logger;
    }

    public boolean isPermanentlyBanned(String banTarget) {
        if (banTarget == null) return false;
        cleanupLocal(System.currentTimeMillis());
        if (localPerms.contains(banTarget)) return true;
        if (!redisReady()) return false;
        return redis.exists(redis.key("login:perm:" + banTarget));
    }

    public boolean isLocked(String ip) {
        if (ip == null) return false;
        long now = System.currentTimeMillis();
        cleanupLocal(now);
        Long until = localLocks.get(ip);
        if (until != null && now < until) return true;
        if (!redisReady()) return false;
        return redis.exists(redis.key("login:lock:" + ip));
    }

    public int recordFailure(String ip, String banTarget) {
        if (ip == null || banTarget == null) return 0;
        cleanupLocal(System.currentTimeMillis());

        if (redisReady()) {
            Object result = redis.eval(FAILURE_SCRIPT,
                    List.of(redis.key("login:fail:" + ip),
                            redis.key("login:lock:" + ip),
                            redis.key("login:lockcount:" + ip),
                            redis.key("login:perm:" + banTarget)),
                    List.of(String.valueOf(Math.max(1000L, config.getLoginWindowMs())),
                            String.valueOf(Math.max(1, config.getLoginMaxAttempts())),
                            String.valueOf(Math.max(1000L, config.getLoginLockoutMs())),
                            String.valueOf(Math.max(1, config.getLoginPermanentBanThreshold())),
                            String.valueOf(LOCKCOUNT_TTL_MS)));
            if (result instanceof Number) {
                int code = ((Number) result).intValue();
                if (code == 1 || code == 2) {
                    localLocks.put(ip, System.currentTimeMillis() + Math.max(1000L, config.getLoginLockoutMs()));
                } else if (code == 3 || code == 4) {
                    localPerms.add(banTarget);
                }
                return code;
            }
        }

        return localRecordFailure(ip, banTarget);
    }

    public void recordSuccess(String ip) {
        if (ip == null) return;
        cleanupLocal(System.currentTimeMillis());
        failCounts.remove(ip);
        localLocks.remove(ip);
        if (!redisReady()) return;
        redis.del(redis.key("login:fail:" + ip));
        redis.del(redis.key("login:lock:" + ip));
    }

    private synchronized int localRecordFailure(String ip, String banTarget) {
        long now = System.currentTimeMillis();
        cleanupLocal(now);

        if (localPerms.contains(banTarget)) return 4;
        Long lockUntil = localLocks.get(ip);
        if (lockUntil != null && now < lockUntil) return 2;

        long windowMs = Math.max(1000L, config.getLoginWindowMs());
        int maxAttempts = Math.max(1, config.getLoginMaxAttempts());
        long[] fail = failCounts.compute(ip, (key, value) -> {
            if (value == null || now >= value[1]) {
                return new long[]{1L, now + windowMs};
            }
            value[0]++;
            return value;
        });
        if (fail[0] < maxAttempts) return 0;

        failCounts.remove(ip);
        localLocks.put(ip, now + Math.max(1000L, config.getLoginLockoutMs()));
        int lockouts = localLockCounts.merge(ip, 1, Integer::sum);
        if (lockouts >= Math.max(1, config.getLoginPermanentBanThreshold())) {
            localPerms.add(banTarget);
            logger.warning("[SECURITY] Login guard local fallback banned " + banTarget
                    + " after " + lockouts + " lockouts.");
            return 3;
        }
        return 1;
    }

    private boolean redisReady() {
        return redis.isAvailable() && config.isRedisFeatureEnabled("login-guard");
    }

    private void cleanupLocal(long now) {
        failCounts.entrySet().removeIf(entry -> now >= entry.getValue()[1]);
        localLocks.entrySet().removeIf(entry -> now >= entry.getValue());
    }
}
