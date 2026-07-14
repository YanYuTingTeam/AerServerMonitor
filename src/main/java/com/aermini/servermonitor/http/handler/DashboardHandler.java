package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.http.HttpApiServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class DashboardHandler {

    private final String dashboardPath;
    private final com.aermini.servermonitor.log.LogManager logManager;

    public DashboardHandler(String dashboardPath, com.aermini.servermonitor.log.LogManager logManager) {
        this.dashboardPath = dashboardPath;
        this.logManager = logManager;
    }

    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = com.aermini.servermonitor.log.LogIdGenerator.generateLogId(ip);
        logManager.logRequest(logId, "GET", path, 200, "", "", null, ip);

        if (path.equals(dashboardPath)) {
            try {
                exchange.getResponseHeaders().set("Location", dashboardPath + "/");
                exchange.sendResponseHeaders(301, -1);
            } catch (Exception ignored) {}
            return;
        }

        String resourcePath;
        if (path.equals(dashboardPath + "/") || path.equals(dashboardPath)) {
            resourcePath = "dashboard/index.html";
        } else {
            resourcePath = "dashboard" + path.substring(dashboardPath.length());
        }

        String contentType = getContentType(resourcePath);
        if (resourcePath.contains("..")) {
            HttpApiServer.sendHtml(exchange, 403, "Forbidden");
            return;
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] data = readAllBytes(is);
                HttpApiServer.sendFile(exchange, 200, data, contentType);
            } else {
                try (InputStream idx = getClass().getClassLoader().getResourceAsStream("dashboard/index.html")) {
                    if (idx != null) {
                        byte[] data = readAllBytes(idx);
                        HttpApiServer.sendFile(exchange, 200, data, "text/html; charset=utf-8");
                    } else {
                        HttpApiServer.sendHtml(exchange, 404, "Not Found");
                    }
                }
            }
        } catch (IOException e) {
            HttpApiServer.sendHtml(exchange, 500, "Internal Server Error");
        }
    }

    public void handleDocs(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = com.aermini.servermonitor.log.LogIdGenerator.generateLogId(ip);
        logManager.logRequest(logId, "GET", path, 200, "", "", null, ip);

        String resourcePath;

        if (path.equals("/docs") || path.equals("/docs/")) {
            resourcePath = "docs/index.html";
        } else {
            resourcePath = "docs" + path.substring(5);
        }

        if (resourcePath.contains("..")) {
            HttpApiServer.sendHtml(exchange, 403, "Forbidden");
            return;
        }

        String contentType = getContentType(resourcePath);

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] data = readAllBytes(is);
                HttpApiServer.sendFile(exchange, 200, data, contentType);
            } else {
                HttpApiServer.sendHtml(exchange, 404, "Not Found");
            }
        } catch (IOException e) {
            HttpApiServer.sendHtml(exchange, 500, "Internal Server Error");
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    public void handleWebhooks(HttpExchange exchange) {
        serveDashboardPage(exchange);
    }

    public void handleWebhookRetry(HttpExchange exchange) {
        serveDashboardPage(exchange);
    }

    private void serveDashboardPage(HttpExchange exchange) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("dashboard/index.html")) {
            if (is != null) {
                byte[] data = readAllBytes(is);
                HttpApiServer.sendFile(exchange, 200, data, "text/html; charset=utf-8");
            } else {
                HttpApiServer.sendHtml(exchange, 404, "Not Found");
            }
        } catch (IOException e) {
            HttpApiServer.sendHtml(exchange, 500, "Internal Server Error");
        }
    }
}
