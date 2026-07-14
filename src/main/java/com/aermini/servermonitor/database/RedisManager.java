package com.aermini.servermonitor.database;

import com.aermini.servermonitor.config.PluginConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.logging.Logger;

public class RedisManager {

    private final PluginConfig config;
    private final Logger logger;
    private JedisPool pool;
    private volatile boolean available = false;
    private volatile boolean degraded = false;
    private int consecutiveFailures = 0;

    public RedisManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void init() {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.getRedisPoolMaxTotal());
            poolConfig.setMaxIdle(config.getRedisPoolMaxIdle());
            poolConfig.setMinIdle(config.getRedisPoolMinIdle());
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);

            String password = config.getRedisPassword();
            if (password != null && !password.isEmpty()) {
                pool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 5000, password, config.getRedisDatabase());
            } else {
                pool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 5000, null, config.getRedisDatabase());
            }

            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
                jedis.set("aermonitor:startup", String.valueOf(System.currentTimeMillis()));
                jedis.expire("aermonitor:startup", 3600);
                available = true;
                degraded = false;
                consecutiveFailures = 0;
                logger.info("Redis connected: " + config.getRedisHost() + ":" + config.getRedisPort());
            }
        } catch (Throwable e) {
            available = false;
            degraded = true;
            logger.warning("Redis connection failed. Running in degraded mode. Cause: " + e.getClass().getSimpleName() + ": " + e.getMessage());

            e.printStackTrace();
        }

        Thread healthCheck = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(config.getRedisHealthCheckInterval());
                    checkHealth();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "AerMonitor-RedisHealth");
        healthCheck.setDaemon(true);
        healthCheck.start();
    }

    public void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    public boolean isAvailable() {
        return available && !degraded;
    }

    public String getStatus() {
        if (pool == null || pool.isClosed()) return "not configured";
        if (degraded) return "degraded";
        if (available) return "connected";
        return "disconnected";
    }

    public Jedis getResource() {
        if (pool == null || pool.isClosed()) return null;
        return pool.getResource();
    }

    public void returnResource(Jedis jedis) {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {}
        }
    }

    public String get(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            String val = jedis.get(key);
            onSuccess();
            return val;
        } catch (Exception e) {
            onFailure();
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public void set(String key, String value, int ttlSeconds) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, value);
            } else {
                jedis.set(key, value);
            }
            onSuccess();
        } catch (Exception e) {
            onFailure();
        } finally {
            returnResource(jedis);
        }
    }

    public void del(String key) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.del(key);
            onSuccess();
        } catch (Exception e) {
            onFailure();
        } finally {
            returnResource(jedis);
        }
    }

    public Long incr(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long val = jedis.incr(key);
            onSuccess();
            return val;
        } catch (Exception e) {
            onFailure();
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Long incrWithExpire(String key, int ttlSeconds) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long val = jedis.incr(key);
            jedis.expire(key, ttlSeconds);
            onSuccess();
            return val;
        } catch (Exception e) {
            onFailure();
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public boolean exists(String key) {
        if (!isAvailable()) return false;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return false;
            boolean val = jedis.exists(key);
            onSuccess();
            return val;
        } catch (Exception e) {
            onFailure();
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    public void sadd(String key, String... members) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.sadd(key, members);
            onSuccess();
        } catch (Exception e) {
            onFailure();
        } finally {
            returnResource(jedis);
        }
    }

    public void srem(String key, String... members) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.srem(key, members);
            onSuccess();
        } catch (Exception e) {
            onFailure();
        } finally {
            returnResource(jedis);
        }
    }

    public Long scard(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long val = jedis.scard(key);
            onSuccess();
            return val;
        } catch (Exception e) {
            onFailure();
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    private void onSuccess() {
        consecutiveFailures = 0;
    }

    private void onFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= config.getRedisDegradedThreshold()) {
            degraded = true;
            logger.warning("Redis degraded mode activated after " + consecutiveFailures + " failures.");
        }
    }

    private void checkHealth() {
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) {
                if (!degraded) {
                    degraded = true;
                    logger.warning("Redis pool unavailable. Degraded mode.");
                }
                return;
            }
            jedis.ping();
            if (degraded) {
                degraded = false;
                consecutiveFailures = 0;
                available = true;
                logger.info("Redis connection restored.");
            }
        } catch (Exception e) {
            onFailure();
        } finally {
            returnResource(jedis);
        }
    }
}
