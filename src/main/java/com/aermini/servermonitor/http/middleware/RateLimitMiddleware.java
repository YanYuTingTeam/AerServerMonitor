package com.aermini.servermonitor.http.middleware;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimitMiddleware {

    private static final String RATE_LIMIT_SCRIPT =
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end " +
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
            "if c > tonumber(ARGV[2]) then " +
            "redis.call('SET', KEYS[2], '1') " +
            "redis.call('PEXPIRE', KEYS[2], ARGV[3]) " +
            "return 0 end " +
            "return 1";

    private final PluginConfig config;
    private final RedisManager redis;
    private final Map<String, Long> banMap = new ConcurrentHashMap<>();
    private final Map<String, long[]> localWindow = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(0L);

    public RateLimitMiddleware(PluginConfig config, RedisManager redis) {
        this.config = config;
        this.redis = redis;
    }

    public boolean check(String ip) {
        long now = System.currentTimeMillis();
        cleanup(now);

        Long banUntil = banMap.get(ip);
        if (banUntil != null) {
            if (now < banUntil) return false;
            banMap.remove(ip, banUntil);
        }

        long windowMs = Math.max(1000L, config.getPerTime());
        int max = Math.max(1, config.getMaxRequestPerTime());
        long banMs = Math.max(1L, config.getBannedTime());

        if (redis.isAvailable() && config.isRedisFeatureEnabled("rate-limit")) {
            Boolean allowed = redisCheck(ip, now, windowMs, max, banMs);
            if (allowed != null) return allowed;
        }

        return localCheck(ip, now, windowMs, max, banMs);
    }

    public void banIp(String ip, long durationMs) {
        long ms = Math.max(1L, durationMs);
        banMap.put(ip, System.currentTimeMillis() + ms);
        if (redis.isAvailable()) {
            redis.setPx(redis.key("rl:ban:" + ip), "1", ms);
        }
    }

    public boolean isBanned(String ip) {
        Long banUntil = banMap.get(ip);
        if (banUntil == null) return false;
        if (System.currentTimeMillis() < banUntil) return true;
        banMap.remove(ip, banUntil);
        return false;
    }

    private Boolean redisCheck(String ip, long now, long windowMs, int max, long banMs) {
        long bucket = now / windowMs;
        String counterKey = redis.key("rl:" + ip + ":" + bucket);
        String banKey = redis.key("rl:ban:" + ip);
        Object result = redis.eval(RATE_LIMIT_SCRIPT, List.of(counterKey, banKey),
                List.of(String.valueOf(windowMs), String.valueOf(max), String.valueOf(banMs)));
        if (!(result instanceof Number)) return null;
        if (((Number) result).longValue() == 0L) {
            banMap.put(ip, now + banMs);
            return false;
        }
        return true;
    }

    private boolean localCheck(String ip, long now, long windowMs, int max, long banMs) {
        long[] entry = localWindow.compute(ip, (key, value) -> {
            if (value == null || now >= value[1]) {
                return new long[]{1L, now + windowMs};
            }
            value[0]++;
            return value;
        });
        if (entry[0] > max) {
            banMap.put(ip, now + banMs);
            return false;
        }
        return true;
    }

    private void cleanup(long now) {
        long last = lastCleanup.get();
        if (now - last < 60000L) return;
        if (!lastCleanup.compareAndSet(last, now)) return;
        banMap.entrySet().removeIf(entry -> entry.getValue() <= now);
        localWindow.entrySet().removeIf(entry -> entry.getValue()[1] <= now);
    }
}
