package com.aermini.servermonitor.http.response;

import com.aermini.servermonitor.util.TimeUtil;
import com.google.gson.JsonObject;

public class ApiResponse {

    public static String success(String logid, JsonObject data) {
        return build(200, 0, "success", logid, data);
    }

    public static String success(int errcode, String logid, JsonObject data) {
        return build(200, errcode, "success", logid, data);
    }

    public static String error(int code, int errcode, String message, String logid) {
        return build(code, errcode, message, logid, new JsonObject());
    }

    public static String error(int code, int errcode, String message, String logid, JsonObject data) {
        return build(code, errcode, message, logid, data);
    }

    private static String build(int code, int errcode, String message, String logid, JsonObject data) {
        JsonObject resp = new JsonObject();
        resp.addProperty("code", code);
        resp.addProperty("errcode", errcode);
        resp.addProperty("message", message);
        resp.addProperty("time", TimeUtil.now());
        resp.addProperty("logid", logid);
        resp.add("data", data);
        return resp.toString();
    }
}
