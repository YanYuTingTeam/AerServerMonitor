package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.database.DatabaseManager;
import com.aermini.servermonitor.database.RedisManager;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

public class SystemHandler {

    private final LogManager logManager;
    private final RedisManager redisManager;
    private final DatabaseManager databaseManager;

    public SystemHandler(LogManager logManager, RedisManager redisManager, DatabaseManager databaseManager) {
        this.logManager = logManager;
        this.redisManager = redisManager;
        this.databaseManager = databaseManager;
    }

    public void handleStatus(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        JsonObject data = new JsonObject();
        data.addProperty("os_name", System.getProperty("os.name"));
        data.addProperty("os_version", System.getProperty("os.version"));
        data.addProperty("os_arch", System.getProperty("os.arch"));
        data.addProperty("available_processors", osBean.getAvailableProcessors());

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunBean = (com.sun.management.OperatingSystemMXBean) osBean;
            data.addProperty("total_physical_memory", sunBean.getTotalPhysicalMemorySize());
            data.addProperty("free_physical_memory", sunBean.getFreePhysicalMemorySize());
            data.addProperty("cpu_load", sunBean.getSystemCpuLoad());
        }

        data.addProperty("total_memory", runtime.totalMemory());
        data.addProperty("free_memory", runtime.freeMemory());
        data.addProperty("max_memory", runtime.maxMemory());
        data.addProperty("used_memory", runtime.totalMemory() - runtime.freeMemory());
        data.addProperty("jvm_uptime", runtimeBean.getUptime());
        data.addProperty("jvm_version", runtimeBean.getVmVersion());
        data.addProperty("jvm_name", runtimeBean.getVmName());

        JsonObject services = new JsonObject();
        services.addProperty("redis", redisManager.getStatus());
        services.addProperty("database", "connected");
        data.add("services", services);

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }
}
