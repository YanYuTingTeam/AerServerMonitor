package com.aermini.servermonitor.log;

import com.aermini.servermonitor.util.HmacUtil;
import com.aermini.servermonitor.util.TimeUtil;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

public class LogIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final ConcurrentHashMap<String, AtomicLong> DAILY_COUNTERS = new ConcurrentHashMap<>();

    public static String generateLogId(String uaOrIdentifier) {
        return generate(uaOrIdentifier);
    }

    public static String generateMsgId(String content) {
        return generate(content);
    }

    public static String generateEventId(String eventName, String serverName, String playerUuid) {
        return generate(eventName + serverName + playerUuid);
    }

    private static String generate(String hashSource) {
        long now = System.currentTimeMillis();
        String compact = TimeUtil.formatCompact(now);
        String md5Part = HmacUtil.md5(hashSource).substring(0, 8).toUpperCase();
        int dayCount = getDailyCount();
        String randomPart = randomChars(8);
        return compact + md5Part + dayCount + "A" + randomPart;
    }

    private static int getDailyCount() {
        String today = TimeUtil.formatDate(System.currentTimeMillis()).replace("-", "");
        AtomicLong counter = DAILY_COUNTERS.computeIfAbsent(today, k -> {
            DAILY_COUNTERS.keySet().removeIf(k2 -> !k2.equals(k));
            return new AtomicLong(0);
        });
        return (int) counter.incrementAndGet();
    }

    private static String randomChars(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
