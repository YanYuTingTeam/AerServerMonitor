package com.aermini.servermonitor.http.middleware;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.RedisManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitMiddleware {

    private final PluginConfig config;
    private final RedisManager redis;
    private final Map<String, Long> banMap = new ConcurrentHashMap<>();

    public RateLimitMiddleware(PluginConfig config, RedisManager redis) {
        this.config = config;
        this.redis = redis;
    }

    public boolean check(String ip) {
        Long banUntil = banMap.get(ip);
        if (banUntil != null) {
            if (System.currentTimeMillis() < banUntil) return false;
            banMap.remove(ip);
        }

        if (redis.isAvailable()) {
            String key = "aermonitor:ratelimit:" + ip;
            Long count = redis.incrWithExpire(key, (int) (config.getPerTime() / 1000));
            if (count != null && count > config.getMaxRequestPerTime()) {
                banMap.put(ip, System.currentTimeMillis() + config.getBannedTime());
                return false;
            }
        }
        return true;
    }

    public void banIp(String ip, long durationMs) {
        banMap.put(ip, System.currentTimeMillis() + durationMs);
    }

    public boolean isBanned(String ip) {
        Long banUntil = banMap.get(ip);
        return banUntil != null && System.currentTimeMillis() < banUntil;
    }
}
