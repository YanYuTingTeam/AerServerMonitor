package com.aermini.servermonitor.util;

import java.text.SimpleDateFormat;
import java.util.Date;

public class TimeUtil {

    public static long now() {
        return System.currentTimeMillis();
    }

    public static String formatTimestamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(ms));
    }

    public static String formatShort(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    public static String formatDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(ms));
    }

    public static String formatCompact(long ms) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date(ms));
    }
}
