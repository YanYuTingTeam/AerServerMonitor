package com.aermini.servermonitor.util;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;

import com.google.gson.JsonObject;

public final class ServerInfoUtil {

    private ServerInfoUtil() {}

    public static String formatAddress(ServerInfo info) {
        if (info == null || info.getAddress() == null) return "";
        String host = info.getAddress().getHostName();
        if (host == null || host.isEmpty()) {
            host = info.getAddress().getAddress() != null
                    ? info.getAddress().getAddress().getHostAddress() : "";
        }
        return host + ":" + info.getAddress().getPort();
    }

    public static String formatIp(ServerInfo info) {
        if (info == null || info.getAddress() == null) return "";
        String host = info.getAddress().getHostName();
        if (host == null || host.isEmpty()) {
            host = info.getAddress().getAddress() != null
                    ? info.getAddress().getAddress().getHostAddress() : "";
        }
        return host;
    }

    public static JsonObject buildServerObject(String serverName) {
        JsonObject obj = new JsonObject();
        if (serverName == null || serverName.isEmpty()) {
            return obj;
        }
        ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
        obj.addProperty("name", serverName);
        obj.addProperty("ip", formatAddress(info));
        obj.addProperty("describe", info != null ? info.getMotd() : "");
        return obj;
    }

    public static JsonObject buildServerObjectWithAddress(String serverName) {
        JsonObject obj = new JsonObject();
        if (serverName == null || serverName.isEmpty()) {
            obj.add("name", com.google.gson.JsonNull.INSTANCE);
            obj.add("address", com.google.gson.JsonNull.INSTANCE);
            obj.add("describe", com.google.gson.JsonNull.INSTANCE);
            return obj;
        }
        ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
        obj.addProperty("name", serverName);
        obj.addProperty("address", formatAddress(info));
        obj.addProperty("describe", info != null ? info.getMotd() : "");
        return obj;
    }
}
