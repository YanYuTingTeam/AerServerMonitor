package com.aermini.servermonitor.http.middleware;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.http.handler.AuthHandler;
import com.aermini.servermonitor.util.HmacUtil;
import com.sun.net.httpserver.HttpExchange;

import java.util.List;
import java.util.logging.Logger;

public class AuthMiddleware {

    private final PluginConfig config;
    private final Logger logger;
    private AuthHandler authHandler;

    public AuthMiddleware(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void setAuthHandler(AuthHandler authHandler) {
        this.authHandler = authHandler;
    }

    public boolean verify(HttpExchange exchange) {

        if (!checkOrigin(exchange)) {
            logger.warning("[AUTH] rejected: Origin/Referer not allowed, origin="
                    + exchange.getRequestHeaders().getFirst("Origin")
                    + ", referer=" + exchange.getRequestHeaders().getFirst("Referer")
                    + ", uri=" + exchange.getRequestURI());
            return false;
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");

        if (auth != null && auth.startsWith("Bearer ")) {
            return verifyDashboardRequest(exchange, auth);
        }

        return verifyExternalApiRequest(exchange);
    }

    private boolean verifyDashboardRequest(HttpExchange exchange, String authHeader) {
        if (authHandler == null) {
            logger.warning("[AUTH] rejected: AuthHandler not wired, uri=" + exchange.getRequestURI());
            return false;
        }

        if (authHandler.verifyToken(exchange) == null) {
            logger.info("[AUTH] rejected: invalid/expired JWT, uri=" + exchange.getRequestURI());
            return false;
        }

        String headerA = exchange.getRequestHeaders().getFirst("a");
        String headerB = exchange.getRequestHeaders().getFirst("b");
        if (headerA == null || headerB == null) {
            logger.info("[AUTH] rejected: dashboard request missing a/b headers, uri=" + exchange.getRequestURI());
            return false;
        }

        long now = System.currentTimeMillis();
        long requestTime;
        try {
            requestTime = Long.parseLong(headerA);
        } catch (NumberFormatException e) {
            logger.info("[AUTH] rejected: invalid a header (not a number): " + headerA);
            return false;
        }
        if (Math.abs(now - requestTime) > config.getHeaderATimeOffset()) {
            logger.info("[AUTH] rejected: a header time skew too large, skew=" + (now - requestTime) + "ms");
            return false;
        }

        String rawJwt = authHeader.substring(7);
        String body = "";
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            body = com.aermini.servermonitor.http.HttpApiServer.readBody(exchange);
            exchange.setAttribute("rawBody", body);
        }

        String expectedB = HmacUtil.hmac("HmacSHA256", rawJwt, body);
        if (!expectedB.equalsIgnoreCase(headerB)) {
            logger.warning("[AUTH] rejected: dashboard b header HMAC mismatch, uri=" + exchange.getRequestURI()
                    + " (expected=" + expectedB.substring(0, 8) + "..., got=" + headerB.substring(0, Math.min(8, headerB.length())) + "...)");
            return false;
        }

        return true;
    }

    private boolean verifyExternalApiRequest(HttpExchange exchange) {
        String headerA = exchange.getRequestHeaders().getFirst("a");
        String headerB = exchange.getRequestHeaders().getFirst("b");

        if (headerA == null || headerB == null) {
            logger.info("[AUTH] rejected: no Authorization Bearer and no a/b headers, auth="
                    + exchange.getRequestHeaders().getFirst("Authorization")
                    + ", uri=" + exchange.getRequestURI());
            return false;
        }

        long now = System.currentTimeMillis();
        long requestTime;
        try {
            requestTime = Long.parseLong(headerA);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now - requestTime) > config.getHeaderATimeOffset()) return false;

        String method = exchange.getRequestMethod();
        String data;
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            data = config.getHmacOrigin();
        } else {
            data = com.aermini.servermonitor.http.HttpApiServer.readBody(exchange);
            exchange.setAttribute("rawBody", data);
        }

        String computed = HmacUtil.hmac(config.getHmacAlgorithm(), config.getHmacSalt(), data);
        return computed.equalsIgnoreCase(headerB);
    }

    private boolean checkOrigin(HttpExchange exchange) {
        String method = exchange.getRequestMethod();

        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (!config.isCsrfProtectionEnabled()) {
            return true;
        }

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String host = exchange.getRequestHeaders().getFirst("Host");

        String checkedOrigin = origin;
        if (checkedOrigin == null || checkedOrigin.isEmpty()) {
            if (referer != null && !referer.isEmpty()) {
                checkedOrigin = extractOrigin(referer);
            }
        }

        if (checkedOrigin == null || checkedOrigin.isEmpty()) {

            return true;
        }

        if (host != null && !host.isEmpty()) {
            String originHost = extractHost(checkedOrigin);
            if (host.equalsIgnoreCase(originHost)) {
                return true;
            }
        }

        List<String> allowed = config.getAllowedOrigins();
        if (allowed != null) {
            for (String a : allowed) {
                if (a != null && a.equalsIgnoreCase(checkedOrigin)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String extractOrigin(String referer) {
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

    private static String extractHost(String origin) {
        int schemeEnd = origin.indexOf("://");
        if (schemeEnd < 0) return origin;
        return origin.substring(schemeEnd + 3);
    }
}
