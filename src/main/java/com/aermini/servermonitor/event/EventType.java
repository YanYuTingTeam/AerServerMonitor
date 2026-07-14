package com.aermini.servermonitor.event;

public enum EventType {
    PLAYER_JOIN("playerJoin"),
    PLAYER_LEAVE("playerLeave"),
    PLAYER_JOIN_SERVER("playerJoinServer"),
    PLAYER_CHAT("playerChat"),
    PLAYER_EXECUTE_CMD("playerExecuteCmd"),
    PLAYER_WAS_KICKED("playerWasKicked"),
    PLAYER_CLICK_CHAT("playerClickChat"),
    PLAYER_BRAND_RECEIVED("playerBrandReceived"),
    PROXY_PING("proxyPing");

    private final String key;

    EventType(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
