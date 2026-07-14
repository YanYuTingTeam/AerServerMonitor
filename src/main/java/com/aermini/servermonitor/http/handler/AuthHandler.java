package com.aermini.servermonitor.http.handler;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.AdminDAO;
import com.aermini.servermonitor.http.HttpApiServer;
import com.aermini.servermonitor.http.response.ApiResponse;
import com.aermini.servermonitor.log.LogIdGenerator;
import com.aermini.servermonitor.log.LogManager;
import com.aermini.servermonitor.util.IpUtil;
import com.aermini.servermonitor.util.TimeUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuthHandler {

    private final PluginConfig config;
    private final AdminDAO adminDAO;
    private final LogManager logManager;

    private final Map<String, Integer> failCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockouts = new ConcurrentHashMap<>();
    private final Map<String, Integer> lockoutCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> permanentBans = new ConcurrentHashMap<>();

    public AuthHandler(PluginConfig config, AdminDAO adminDAO, LogManager logManager) {
        this.config = config;
        this.adminDAO = adminDAO;
        this.logManager = logManager;
    }

    public void handleLogin(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        String body = HttpApiServer.readBody(exchange);

        String banTarget = IpUtil.normalizeBanTarget(ip, config.getLoginBanScope());
        if (permanentBans.containsKey(banTarget)) {
            logManager.log("[SECURITY] Login attempt from permanently banned IP: " + ip);
            HttpApiServer.sendWithLog(exchange, 403, ApiResponse.error(403, 50003, "forbidden", logId), logManager);
            return;
        }

        Long lockoutUntil = lockouts.get(ip);
        if (lockoutUntil != null) {
            if (System.currentTimeMillis() < lockoutUntil) {
                HttpApiServer.sendWithLog(exchange, 429, ApiResponse.error(429, 50003, "too many attempts", logId), logManager);
                return;
            }
            lockouts.remove(ip);
        }

        JsonObject req;
        try {
            req = com.aermini.servermonitor.util.JsonUtil.parse(body);
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid json", logId), logManager);
            return;
        }

        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";

        if (!username.matches("^[a-zA-Z0-9_]{3,32}$")) {
            recordFailure(ip, banTarget);
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "invalid credentials", logId), logManager);
            return;
        }

        JsonObject admin = adminDAO.authenticate(username, password);
        if (admin == null) {
            recordFailure(ip, banTarget);
            logManager.log("[AUTH] Login failed from " + ip + " (" + failCounts.getOrDefault(ip, 0) + "/" + config.getLoginMaxAttempts() + " attempts)");
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "invalid credentials", logId), logManager);
            return;
        }

        failCounts.remove(ip);
        lockouts.remove(ip);
        adminDAO.updateLastLogin(admin.get("id").getAsInt(), ip);

        boolean needChange = admin.get("is_default").getAsBoolean();
        int tokenVersion = admin.get("token_version").getAsInt();

        String token = JWT.create()
                .withClaim("admin_id", admin.get("id").getAsInt())
                .withClaim("username", username)
                .withClaim("role", admin.get("role").getAsString())
                .withClaim("token_version", tokenVersion)
                .withExpiresAt(new java.util.Date(System.currentTimeMillis() + config.getDashboardSessionExpire()))
                .sign(Algorithm.HMAC256(config.getDashboardSecret()));

        logManager.log("[AUTH] Login success: " + username + " from " + ip);

        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("need_password_change", needChange);
        data.addProperty("username", username);
        data.addProperty("role", admin.get("role").getAsString());

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleChangePassword(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);

        DecodedJWT jwt = verifyToken(exchange);
        if (jwt == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }

        JsonObject req;
        try {
            req = com.aermini.servermonitor.util.JsonUtil.parse(body);
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid json: [" + body + "] " + e.getMessage(), logId), logManager);
            return;
        }

        int adminId = jwt.getClaim("admin_id").asInt();
        JsonObject admin = adminDAO.getById(adminId);
        if (admin == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }

        String oldPassword = req.has("old") ? req.get("old").getAsString() : "";
        String newPassword = req.has("new") ? req.get("new").getAsString() : "";

        if (adminDAO.authenticate(admin.get("username").getAsString(), oldPassword) == null) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 50001, "invalid old password", logId), logManager);
            return;
        }

        if (newPassword.length() < 6) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "password too short", logId), logManager);
            return;
        }

        if (adminDAO.changePassword(adminId, newPassword)) {
            logManager.log("[AUTH] Password changed for " + admin.get("username").getAsString());
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, new JsonObject()), logManager);
        } else {
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 0, "password change failed", logId), logManager);
        }
    }

    public void handleVerify(HttpExchange exchange) {
        String logId = LogIdGenerator.generateLogId("");
        DecodedJWT jwt = verifyToken(exchange);
        if (jwt == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }

        int adminId = jwt.getClaim("admin_id").asInt();
        JsonObject admin = adminDAO.getById(adminId);
        if (admin == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("username", admin.get("username").getAsString());
        data.addProperty("role", admin.get("role").getAsString());
        data.addProperty("need_password_change", admin.get("is_default").getAsBoolean());

        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public DecodedJWT verifyToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return verifyTokenString(auth.substring(7));
    }

    public DecodedJWT verifyTokenString(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            DecodedJWT jwt = JWT.require(Algorithm.HMAC256(config.getDashboardSecret()))
                    .build()
                    .verify(token);

            int adminId = jwt.getClaim("admin_id").asInt();
            int currentVersion = adminDAO.getTokenVersion(adminId);
            int tokenVersion = jwt.getClaim("token_version").asInt();
            if (tokenVersion != currentVersion) return null;

            return jwt;
        } catch (Exception e) {
            return null;
        }
    }

    private void recordFailure(String ip, String banTarget) {
        int count = failCounts.compute(ip, (k, v) -> v == null ? 1 : v + 1);
        if (count >= config.getLoginMaxAttempts()) {
            failCounts.remove(ip);
            int lockoutCount = lockoutCounts.compute(ip, (k, v) -> v == null ? 1 : v + 1);
            lockouts.put(ip, System.currentTimeMillis() + config.getLoginLockoutMs());

            if (lockoutCount >= config.getLoginPermanentBanThreshold()) {
                permanentBans.put(banTarget, System.currentTimeMillis());
                lockoutCounts.remove(ip);
                logManager.log("[SECURITY] IP " + banTarget + " permanently banned. Reason: " + lockoutCount + " lockouts exceeded.");
            }
        }
    }

    public void handleAdminList(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        JsonObject data = new JsonObject();
        data.add("admins", adminDAO.list());
        data.addProperty("has_default", adminDAO.hasDefaultAdmin());
        HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, data), logManager);
    }

    public void handleAdminCreate(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        DecodedJWT jwt = verifyToken(exchange);
        if (jwt == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }
        String operator = jwt.getClaim("username").asString();

        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);
        JsonObject req;
        try {
            req = com.aermini.servermonitor.util.JsonUtil.parse(body);
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid json", logId), logManager);
            return;
        }

        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        String role = req.has("role") ? req.get("role").getAsString() : "admin";

        if (!username.matches("^[a-zA-Z0-9_]{3,32}$")) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid username (3-32 chars, [a-zA-Z0-9_])", logId), logManager);
            return;
        }
        if (password.length() < 6) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "password too short (min 6)", logId), logManager);
            return;
        }

        if (adminDAO.create(username, password, role)) {
            logManager.log("[AUTH] Admin created: " + username + " (role=" + role + ") by " + operator + " from " + ip);
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, new JsonObject()), logManager);
        } else {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "username already exists or create failed", logId), logManager);
        }
    }

    public void handleAdminDelete(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        DecodedJWT jwt = verifyToken(exchange);
        if (jwt == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }
        int operatorId = jwt.getClaim("admin_id").asInt();
        String operator = jwt.getClaim("username").asString();

        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);
        JsonObject req;
        try {
            req = com.aermini.servermonitor.util.JsonUtil.parse(body);
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid json", logId), logManager);
            return;
        }

        int targetId;
        try {
            targetId = req.get("id").getAsInt();
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid id", logId), logManager);
            return;
        }

        if (targetId == operatorId) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "cannot delete yourself", logId), logManager);
            return;
        }
        if (adminDAO.count() <= 1) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "cannot delete the last admin", logId), logManager);
            return;
        }

        JsonObject target = adminDAO.getById(targetId);
        if (target == null) {
            HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 0, "admin not found", logId), logManager);
            return;
        }

        if (adminDAO.delete(targetId)) {
            logManager.log("[AUTH] Admin deleted: " + target.get("username").getAsString() + " (id=" + targetId + ") by " + operator + " from " + ip);
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, new JsonObject()), logManager);
        } else {
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 0, "delete failed", logId), logManager);
        }
    }

    public void handleAdminResetPassword(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        String logId = LogIdGenerator.generateLogId(ip);
        DecodedJWT jwt = verifyToken(exchange);
        if (jwt == null) {
            HttpApiServer.sendWithLog(exchange, 401, ApiResponse.error(401, 50001, "unauthorized", logId), logManager);
            return;
        }
        String operator = jwt.getClaim("username").asString();

        String body = (String) exchange.getAttribute("rawBody");
        if (body == null) body = HttpApiServer.readBody(exchange);
        JsonObject req;
        try {
            req = com.aermini.servermonitor.util.JsonUtil.parse(body);
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid json", logId), logManager);
            return;
        }

        int targetId;
        String newPassword;
        try {
            targetId = req.get("id").getAsInt();
            newPassword = req.get("new_password").getAsString();
        } catch (Exception e) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "invalid id or new_password", logId), logManager);
            return;
        }

        if (newPassword.length() < 6) {
            HttpApiServer.sendWithLog(exchange, 400, ApiResponse.error(400, 0, "password too short (min 6)", logId), logManager);
            return;
        }

        JsonObject target = adminDAO.getById(targetId);
        if (target == null) {
            HttpApiServer.sendWithLog(exchange, 404, ApiResponse.error(404, 0, "admin not found", logId), logManager);
            return;
        }

        if (adminDAO.resetPassword(targetId, newPassword)) {
            logManager.log("[AUTH] Password reset for " + target.get("username").getAsString() + " (id=" + targetId + ") by " + operator + " from " + ip);
            HttpApiServer.sendWithLog(exchange, 200, ApiResponse.success(logId, new JsonObject()), logManager);
        } else {
            HttpApiServer.sendWithLog(exchange, 500, ApiResponse.error(500, 0, "reset failed", logId), logManager);
        }
    }
}
