package com.aermini.servermonitor.database;

import com.aermini.servermonitor.config.PluginConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.args.ListDirection;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RedisManager {

    private final PluginConfig config;
    private final Logger logger;
    private volatile JedisPool pool;
    private volatile boolean available = false;
    private volatile boolean degraded = false;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Thread healthCheck;
    private volatile long nextPoolRetryAt = 0L;
    private volatile boolean stopped = false;
    private int rebuildAttempts = 0;

    public RedisManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void init() {
        stopped = false;
        if (!config.isRedisEnabled()) {
            available = false;
            degraded = true;
            pool = null;
            logger.info("Redis disabled in config, running without Redis.");
            return;
        }

        try {
            pool = createPool();
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
                available = true;
                degraded = false;
                consecutiveFailures.set(0);
                logger.info("Redis connected: " + config.getRedisHost() + ":" + config.getRedisPort());
            }
        } catch (Throwable e) {
            available = false;
            degraded = true;
            JedisPool current = pool;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {
                }
            }
            pool = null;
            logger.warning("Redis connection failed. Running in degraded mode. Cause: " + describe(e));
        }

        startHealthCheck();
    }

    public void shutdown() {
        stopped = true;
        Thread thread = healthCheck;
        if (thread != null) {
            thread.interrupt();
            healthCheck = null;
        }
        JedisPool current = pool;
        if (current != null && !current.isClosed()) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        available = false;
        degraded = true;
    }

    public boolean isAvailable() {
        return available && !degraded;
    }

    public String getStatus() {
        if (!config.isRedisEnabled()) return "disabled";
        if (pool == null || pool.isClosed()) return "not configured";
        if (degraded) return "degraded";
        if (available) return "connected";
        return "disconnected";
    }

    public String key(String suffix) {
        return config.getRedisKeyPrefix() + ":v2:" + suffix;
    }

    public String get(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            String value = jedis.get(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
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
            handleError(e, key);
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
            handleError(e, key);
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
            Long value = jedis.incr(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
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
            Long value = jedis.incr(key);
            jedis.expire(key, ttlSeconds);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
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
            boolean value = jedis.exists(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    public void expire(String key, int seconds) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.expire(key, seconds);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public void pexpire(String key, long ms) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.pexpire(key, ms);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public boolean setNx(String key, String value, int ttlSeconds) {
        return Boolean.TRUE.equals(setNxOrNull(key, value, ttlSeconds));
    }

    public Boolean setNxOrNull(String key, String value, int ttlSeconds) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            long ttlMs = Math.max(1L, ttlSeconds * 1000L);
            String result = jedis.set(key, value, SetParams.setParams().nx().px(ttlMs));
            onSuccess();
            return "OK".equals(result);
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public void setPx(String key, String value, long ttlMs) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.set(key, value, SetParams.setParams().px(Math.max(1L, ttlMs)));
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public void hset(String key, String field, String value) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.hset(key, field, value);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public void hset(String key, Map<String, String> fields) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.hset(key, fields);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public String hget(String key, String field) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            String value = jedis.hget(key, field);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Map<String, String> hgetAll(String key) {
        Map<String, String> value = hgetAllOrNull(key);
        return value == null ? Collections.emptyMap() : value;
    }

    public Map<String, String> hgetAllOrNull(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Map<String, String> value = jedis.hgetAll(key);
            onSuccess();
            return value != null ? value : Collections.emptyMap();
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public void hdel(String key, String... fields) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.hdel(key, fields);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public Long hincrBy(String key, String field, long value) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long result = jedis.hincrBy(key, field, value);
            onSuccess();
            return result;
        } catch (Exception e) {
            handleError(e, key);
            return null;
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
            handleError(e, key);
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
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public Set<String> smembers(String key) {
        if (!isAvailable()) return Collections.emptySet();
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return Collections.emptySet();
            Set<String> value = jedis.smembers(key);
            onSuccess();
            return value != null ? value : Collections.emptySet();
        } catch (Exception e) {
            handleError(e, key);
            return Collections.emptySet();
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
            Long value = jedis.scard(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public boolean sismember(String key, String member) {
        if (!isAvailable()) return false;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return false;
            boolean value = jedis.sismember(key, member);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    public boolean lpush(String key, String... values) {
        if (!isAvailable()) return false;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return false;
            jedis.lpush(key, values);
            onSuccess();
            return true;
        } catch (Exception e) {
            handleError(e, key);
            return false;
        } finally {
            returnResource(jedis);
        }
    }

    public List<String> lrange(String key, long start, long stop) {
        if (!isAvailable()) return Collections.emptyList();
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return Collections.emptyList();
            List<String> value = jedis.lrange(key, start, stop);
            onSuccess();
            return value != null ? value : Collections.emptyList();
        } catch (Exception e) {
            handleError(e, key);
            return Collections.emptyList();
        } finally {
            returnResource(jedis);
        }
    }

    public void ltrim(String key, long start, long stop) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.ltrim(key, start, stop);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public String lmove(String src, String dst, boolean fromLeft, boolean toLeft) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            ListDirection from = fromLeft ? ListDirection.LEFT : ListDirection.RIGHT;
            ListDirection to = toLeft ? ListDirection.LEFT : ListDirection.RIGHT;
            String value = jedis.lmove(src, dst, from, to);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, src);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Long llen(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long value = jedis.llen(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Long lrem(String key, long count, String value) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long result = jedis.lrem(key, count, value);
            onSuccess();
            return result;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public void zadd(String key, double score, String member) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.zadd(key, score, member);
            onSuccess();
        } catch (Exception e) {
            handleError(e, key);
        } finally {
            returnResource(jedis);
        }
    }

    public List<String> zrangeByScore(String key, double min, double max) {
        if (!isAvailable()) return Collections.emptyList();
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return Collections.emptyList();
            List<String> value = jedis.zrangeByScore(key, min, max);
            onSuccess();
            return value != null ? value : Collections.emptyList();
        } catch (Exception e) {
            handleError(e, key);
            return Collections.emptyList();
        } finally {
            returnResource(jedis);
        }
    }

    public List<String> zrangeByScore(String key, double min, double max, int offset, int count) {
        if (!isAvailable()) return Collections.emptyList();
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return Collections.emptyList();
            List<String> value = jedis.zrangeByScore(key, min, max, offset, count);
            onSuccess();
            return value != null ? value : Collections.emptyList();
        } catch (Exception e) {
            handleError(e, key);
            return Collections.emptyList();
        } finally {
            returnResource(jedis);
        }
    }

    public Long zrem(String key, String... members) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long value = jedis.zrem(key, members);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Long zcard(String key) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Long value = jedis.zcard(key);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, key);
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public Object eval(String script, List<String> keys, List<String> args) {
        if (!isAvailable()) return null;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return null;
            Object value = jedis.eval(script, keys, args);
            onSuccess();
            return value;
        } catch (Exception e) {
            handleError(e, keys != null && !keys.isEmpty() ? keys.get(0) : "eval");
            return null;
        } finally {
            returnResource(jedis);
        }
    }

    public void publish(String channel, String message) {
        if (!isAvailable()) return;
        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.publish(channel, message);
            onSuccess();
        } catch (Exception e) {
            handleError(e, channel);
        } finally {
            returnResource(jedis);
        }
    }

    public Jedis getResource() {
        JedisPool current = pool;
        if (current == null || current.isClosed()) return null;
        return current.getResource();
    }

    public void returnResource(Jedis jedis) {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
            }
        }
    }

    private JedisPool createPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getRedisPoolMaxTotal());
        poolConfig.setMaxIdle(config.getRedisPoolMaxIdle());
        poolConfig.setMinIdle(config.getRedisPoolMinIdle());
        poolConfig.setMaxWait(Duration.ofMillis(Math.max(1, config.getRedisMaxWaitMs())));
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestWhileIdle(true);

        String password = config.getRedisPassword();
        if (password != null && password.isEmpty()) password = null;

        return new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(),
                config.getRedisTimeoutMs(), password, config.getRedisDatabase());
    }

    private void startHealthCheck() {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(Math.max(1000L, config.getRedisHealthCheckInterval()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    checkHealth();
                } catch (Throwable e) {
                    logger.warning("Redis health check error: " + describe(e));
                }
            }
        }, "AerMonitor-RedisHealth");
        thread.setDaemon(true);
        healthCheck = thread;
        thread.start();
    }

    private void checkHealth() {
        if (stopped) return;
        if (!ensurePool()) return;

        Jedis jedis = null;
        try {
            jedis = getResource();
            if (jedis == null) return;
            jedis.ping();
            boolean wasDegraded = degraded;
            available = true;
            degraded = false;
            consecutiveFailures.set(0);
            if (wasDegraded) {
                logger.info("Redis connection restored.");
            }
        } catch (Exception e) {
            handleError(e, "health-check");
        } finally {
            returnResource(jedis);
        }
    }

    private boolean ensurePool() {
        if (stopped) return false;
        JedisPool current = pool;
        if (current != null && !current.isClosed()) return true;

        long now = System.currentTimeMillis();
        if (now < nextPoolRetryAt) return false;

        try {
            pool = createPool();
            rebuildAttempts = 0;
            nextPoolRetryAt = 0L;
            logger.info("Redis pool recreated for " + config.getRedisHost() + ":" + config.getRedisPort());
            return true;
        } catch (Throwable e) {
            rebuildAttempts++;
            long base = Math.max(1000L, config.getRedisHealthCheckInterval());
            long wait = Math.min(base * rebuildAttempts, 60000L);
            nextPoolRetryAt = now + wait;
            onFailure();
            logger.warning("Redis pool recreate failed, retry after " + wait + "ms. Cause: " + describe(e));
            return false;
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= Math.max(1, config.getRedisDegradedThreshold())) {
            if (!degraded) {
                logger.warning("Redis degraded mode activated after " + failures + " failures.");
            }
            degraded = true;
            available = false;
        }
    }

    private void handleError(Exception e, String key) {
        if (e instanceof JedisDataException) {
            onSuccess();
            logger.warning("Redis data error on " + key + ": " + e.getMessage());
            return;
        }
        if (isConnectionError(e)) {
            onFailure();
        } else {
            logger.warning("Redis command error on " + key + ": " + describe(e));
        }
    }

    private boolean isConnectionError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof JedisDataException) return false;
            if (current instanceof JedisConnectionException) return true;
            if (current instanceof SocketTimeoutException
                    || current instanceof SocketException
                    || current instanceof TimeoutException
                    || current instanceof IOException) return true;
            current = current.getCause();
        }
        return e instanceof JedisException;
    }

    private String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
