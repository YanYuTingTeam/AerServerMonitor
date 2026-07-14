package com.aermini.servermonitor.http.middleware;

import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.util.IpUtil;

import java.util.List;

public class WhitelistMiddleware {

    private final PluginConfig config;
    private final boolean enabled;
    private final String mode;
    private final List<String> whitelist;
    private final List<String> blacklist;

    public WhitelistMiddleware(PluginConfig config) {
        this.config = config;
        this.enabled = config.isSsrfEnabled();
        this.mode = config.getSsrfMode();
        this.whitelist = config.getSsrfWhitelist();
        this.blacklist = config.getSsrfBlacklist();
    }

    public boolean checkIp(String ip) {
        if (!enabled) return true;

        if (IpUtil.isInRangeList(ip, blacklist)) return false;

        if ("whitelist".equals(mode) || "both".equals(mode)) {
            return IpUtil.isInRangeList(ip, whitelist);
        }

        return true;
    }

    public boolean checkServerIp(String ip) {
        return checkIp(ip);
    }
}
