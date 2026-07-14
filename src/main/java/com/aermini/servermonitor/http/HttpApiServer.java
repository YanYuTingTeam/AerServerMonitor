package com.aermini.servermonitor.http;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.http.middleware.AuthMiddleware;
import com.aermini.servermonitor.http.middleware.RateLimitMiddleware;
import com.aermini.servermonitor.http.middleware.WhitelistMiddleware;
import com.aermini.servermonitor.http.handler.*;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class HttpApiServer {

    private final AerServerMonitor plugin;
    private final PluginConfig config;
    private final LogManager logManager;
    private final Logger logger;
    private HttpServer server;
    private final AuthMiddleware authMiddleware;
    private final RateLimitMiddleware rateLimitMiddleware;
    private final WhitelistMiddleware whitelistMiddleware;

    private PlayerHandler playerHandler;
    private ServerHandler serverHandler;
    private ChatHandler chatHandler;
    private SystemHandler systemHandler;
    private LogHandler logHandler;
    private AuthHandler authHandler;
    private DashboardHandler dashboardHandler;
    private SnapshotHandler snapshotHandler;

    public HttpApiServer(AerServerMonitor plugin, PluginConfig config, LogManager logManager, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logManager = logManager;
        this.logger = logger;
        this.authMiddleware = new AuthMiddleware(config, logger);
        this.rateLimitMiddleware = new RateLimitMiddleware(config, plugin.getRedisManager());
        this.whitelistMiddleware = new WhitelistMiddleware(config);
    }

    public void setHandlers(PlayerHandler ph, ServerHandler sh, ChatHandler ch, SystemHandler syh,
                            LogHandler lh, AuthHandler ah, DashboardHandler dh) {
        this.playerHandler = ph;
        this.serverHandler = sh;
        this.chatHandler = ch;
        this.systemHandler = syh;
        this.logHandler = lh;
        this.authHandler = ah;
        this.dashboardHandler = dh;
        this.authMiddleware.setAuthHandler(ah);
    }

    public void setSnapshotHandler(SnapshotHandler sh) {
        this.snapshotHandler = sh;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.getHttpPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "AerMonitor-HTTP");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/player/info", this::handlePlayerInfo);
        server.createContext("/player/list", this::handlePlayerList);
        server.createContext("/player/kick", this::handlePlayerKick);
        server.createContext("/server/list", this::handleServerList);
        server.createContext("/server/info", this::handleServerInfo);
        server.createContext("/chat/command", this::handleChatCommand);
        server.createContext("/chat/history", this::handleChatHistory);
        server.createContext("/system/status", this::handleSystemStatus);
        server.createContext("/log/get", this::handleLogGet);
        server.createContext("/log/getlog", this::handleLogGetLog);
        server.createContext("/log/getmsg", this::handleLogGetMsg);
        server.createContext("/log/getevent", this::handleLogGetEvent);

        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/auth/change-password", this::handleChangePassword);
        server.createContext("/api/auth/verify", this::handleVerify);
        server.createContext("/api/admins", this::handleAdmins);
        server.createContext("/api/admins/create", this::handleAdminCreate);
        server.createContext("/api/admins/delete", this::handleAdminDelete);
        server.createContext("/api/admins/reset-password", this::handleAdminResetPassword);
        server.createContext("/api/webhooks/history", this::handleWebhookHistory);
        server.createContext("/api/webhooks/retry", this::handleWebhookRetry);
        server.createContext("/api/snapshot", this::handleSnapshot);

        if (config.isDashboardEnabled()) {
            String path = config.getDashboardPath();
            server.createContext(path, this::handleDashboard);
            server.createContext(path + "/", this::handleDashboard);
        }

        server.createContext("/docs", this::handleDocs);
        server.createContext("/docs/", this::handleDocs);

        server.createContext("/", this::handleNotFound);

        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handlePlayerInfo(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            playerHandler.handleInfo(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handlePlayerKick(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            playerHandler.handleKick(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handlePlayerList(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            playerHandler.handleList(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleServerList(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            serverHandler.handleList(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleServerInfo(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            serverHandler.handleInfo(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleChatCommand(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            chatHandler.handleCommand(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleChatHistory(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            chatHandler.handleHistory(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleSystemStatus(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            systemHandler.handleStatus(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleLogGet(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            logHandler.handleGet(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleLogGetLog(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            logHandler.handleGetLog(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleLogGetMsg(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            logHandler.handleGetMsg(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleLogGetEvent(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            logHandler.handleGetEvent(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleLogin(HttpExchange exchange) {
        handleCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            try { exchange.sendResponseHeaders(204, -1); } catch (Exception ignored) {}
            return;
        }

        if (!checkOriginOnly(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            authHandler.handleLogin(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleChangePassword(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            authHandler.handleChangePassword(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleVerify(HttpExchange exchange) {
        handleCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            try { exchange.sendResponseHeaders(204, -1); } catch (Exception ignored) {}
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            authHandler.handleVerify(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleNotFound(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        logManager.logRequest(logId, exchange.getRequestMethod(), exchange.getRequestURI().toString(), 404, "", "", "", ip);
        send(exchange, 404, ApiResponse.error(404, 0, "not found", logId));
    }

    private void handleAdmins(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            authHandler.handleAdminList(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleAdminCreate(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            authHandler.handleAdminCreate(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleAdminDelete(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            authHandler.handleAdminDelete(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleAdminResetPassword(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            authHandler.handleAdminResetPassword(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleWebhookHistory(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            dashboardHandler.handleWebhooks(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleWebhookRetry(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("POST".equals(exchange.getRequestMethod())) {
            dashboardHandler.handleWebhookRetry(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleSnapshot(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        if ("GET".equals(exchange.getRequestMethod())) {
            snapshotHandler.handleSnapshot(exchange);
        } else {
            sendMethodNotAllowed(exchange);
        }
    }

    private void handleDashboard(HttpExchange exchange) {
        dashboardHandler.handle(exchange);
    }

    private void handleDocs(HttpExchange exchange) {
        dashboardHandler.handleDocs(exchange);
    }

    private boolean checkAuth(HttpExchange exchange) {
        handleCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            try { exchange.sendResponseHeaders(204, -1); } catch (Exception ignored) {}
            return false;
        }

        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();

        if (!rateLimitMiddleware.check(ip)) {
            String logId = LogIdGenerator.generateLogId(ip);
            logManager.logRequest(logId, exchange.getRequestMethod(), exchange.getRequestURI().toString(), 429,
                    exchange.getRequestHeaders().getFirst("a"), exchange.getRequestHeaders().getFirst("b"), null, ip);
            logManager.logResponse(LogIdGenerator.generateLogId(ip), logId, "{\"code\":500,\"errcode\":50003,\"message\":\"rate limited\"}");
            send(exchange, 429, ApiResponse.error(500, 50003, "rate limited", logId));
            return false;
        }

        if (!authMiddleware.verify(exchange)) {
            String logId = LogIdGenerator.generateLogId(ip);
            logManager.logRequest(logId, exchange.getRequestMethod(), exchange.getRequestURI().toString(), 500,
                    exchange.getRequestHeaders().getFirst("a"), exchange.getRequestHeaders().getFirst("b"), null, ip);
            logManager.logResponse(LogIdGenerator.generateLogId(ip), logId, "{\"code\":500,\"errcode\":50001,\"message\":\"网络故障\"}");
            send(exchange, 500, ApiResponse.error(500, 50001, "网络故障", logId));
            return false;
        }

        String logId = LogIdGenerator.generateLogId(ip);
        exchange.setAttribute("logId", logId);
        String postBody = null;
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            postBody = (String) exchange.getAttribute("rawBody");
            if (postBody == null) {
                postBody = readBody(exchange);
            }
        }
        logManager.logRequest(logId, exchange.getRequestMethod(), exchange.getRequestURI().toString(), 0,
                exchange.getRequestHeaders().getFirst("a"), exchange.getRequestHeaders().getFirst("b"), postBody, ip);

        return true;
    }

    private void sendMethodNotAllowed(HttpExchange exchange) {
        String logId = LogIdGenerator.generateLogId("");
        sendWithLog(exchange, 405, ApiResponse.error(405, 0, "不支持的请求", logId), logManager);
    }

    private void handleCors(HttpExchange exchange) {

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String allow = computeAllowedOrigin(exchange, origin);
        if (allow != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allow);
            exchange.getResponseHeaders().set("Vary", "Origin");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, a, b");

        }
    }

    private String computeAllowedOrigin(HttpExchange exchange, String origin) {
        if (origin == null || origin.isEmpty()) {

            return null;
        }

        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isEmpty()) {
            String originHost = extractHostFromOrigin(origin);
            if (host.equalsIgnoreCase(originHost)) {
                return origin;
            }
        }

        List<String> allowed = config.getAllowedOrigins();
        if (allowed != null) {
            for (String a : allowed) {
                if (a != null && a.equalsIgnoreCase(origin)) {
                    return origin;
                }
            }
        }
        return null;
    }

    private static String extractHostFromOrigin(String origin) {
        int schemeEnd = origin.indexOf("://");
        if (schemeEnd < 0) return origin;
        return origin.substring(schemeEnd + 3);
    }

    private boolean checkOriginOnly(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (!config.isCsrfProtectionEnabled()) return true;

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String host = exchange.getRequestHeaders().getFirst("Host");

        String checkedOrigin = origin;
        if (checkedOrigin == null || checkedOrigin.isEmpty()) {
            if (referer != null && !referer.isEmpty()) {
                checkedOrigin = extractOriginFromReferer(referer);
            }
        }
        if (checkedOrigin == null || checkedOrigin.isEmpty()) {

            return true;
        }

        if (host != null && !host.isEmpty()) {
            String originHost = extractHostFromOrigin(checkedOrigin);
            if (host.equalsIgnoreCase(originHost)) return true;
        }

        List<String> allowed = config.getAllowedOrigins();
        if (allowed != null) {
            for (String a : allowed) {
                if (a != null && a.equalsIgnoreCase(checkedOrigin)) return true;
            }
        }

        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        logManager.logRequest(logId, method, exchange.getRequestURI().toString(), 403,
                null, null, null, ip);
        send(exchange, 403, ApiResponse.error(403, 50001, "origin not allowed", logId));
        return false;
    }

    private static String extractOriginFromReferer(String referer) {
        try {
            int schemeEnd = referer.indexOf("://");
            if (schemeEnd < 0) return null;
            int pathStart = referer.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) return referer;
            return referer.substring(0, pathStart);
        } catch (Exception e) {
            return null;
        }
    }

    public static void send(HttpExchange exchange, int statusCode, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

            exchange.getResponseHeaders().add("Vary", "Origin");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {}
    }

    public static void sendWithLog(HttpExchange exchange, int statusCode, String body, LogManager logMgr) {
        String logId = (String) exchange.getAttribute("logId");
        if (logId != null && logMgr != null) {
            logMgr.logResponse(LogIdGenerator.generateLogId(""), logId, body);
        }
        send(exchange, statusCode, body);
    }

    public static void sendHtml(HttpExchange exchange, int statusCode, String html) {
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {}
    }

    public static void sendFile(HttpExchange exchange, int statusCode, byte[] data, String contentType) {
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException ignored) {}
    }

    public static String readBody(HttpExchange exchange) {
        try {
            String cached = (String) exchange.getAttribute("rawBody");
            if (cached != null) return cached;

            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            String body = baos.toString("UTF-8");
            exchange.setAttribute("rawBody", body);
            return body;
        } catch (IOException e) {
            return "";
        }
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
            } else {
                params.put(urlDecode(pair), "");
            }
        }
        return params;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}