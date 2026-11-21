package com.aermini.servermonitor.config;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class MessageManager {

    private static final List<String> DEFAULT_MONITOR_INFO = Arrays.asList(
            "§4§m=============§r§6 §lAerServerMonitor §r§4§m=============",
            "        §6§lAerServerMonitor §r§fv1.0 §8| §b§lYanYuTing",
            " §7Projects:",
            "    §bhttps://github.com/YanYuTingTeam/AerServerMonitor",
            " §7Authors:",
            "    §eAerMini §8| §dhttps://github.com/AerMini2024",
            " §7Usage:",
            "    §f/asm reload",
            "§4§m=============================================="
    );

    private final File dataFolder;
    private final Logger logger;
    private volatile Configuration config;

    public MessageManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public void load() {
        try {
            if (!dataFolder.exists()) dataFolder.mkdirs();
            File file = new File(dataFolder, "message.yml");
            if (!file.exists()) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("message.yml")) {
                    if (in != null) Files.copy(in, file.toPath());
                }
            }
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } catch (Exception e) {
            logger.warning("message.yml 加载失败, 沿用上一次或默认文案: " + e.getMessage());
        }
    }

    private String translate(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String get(String path, String def) {
        if (config == null) return translate(def);
        return translate(config.getString(path, def));
    }

    public List<String> getList(String path, List<String> def) {
        try {
            if (config == null) return defList(def);
            List<?> raw = config.getList(path);
            if (raw == null) return defList(def);
            List<String> out = new ArrayList<>(raw.size());
            for (Object o : raw) {
                out.add(translate(o == null ? "" : o.toString()));
            }
            return out;
        } catch (Exception e) {
            return defList(def);
        }
    }

    private List<String> defList(List<String> def) {
        List<String> out = new ArrayList<>();
        if (def != null) {
            for (String s : def) out.add(translate(s));
        }
        return out;
    }

    public List<String> monitorInfo() {
        return getList("monitor.info", DEFAULT_MONITOR_INFO);
    }

    public String monitorReloadSuccess() {
        return get("monitor.reload-success", "§7§l▎ §6§lAerServerMonitor §r§a● §7配置已重载");
    }

    public String monitorReloadFail() {
        return get("monitor.reload-fail", "§7§l▎ §6§lAerServerMonitor §r§c● §7重载失败: {error}");
    }

    public String monitorUnknownCommand() {
        return get("monitor.unknown-command", "§7§l▎ §6§lAerServerMonitor §r§e● §7命令未知. Usage: §f/asm §breload");
    }

    public String monitorNoPermission() {
        return get("monitor.no-permission", "§7§l▎ §6§lAerServerMonitor §r§c● §7你没有权限使用这个命令");
    }

    public String verifyUsage() {
        return get("verify.usage", "§7正确的用法是 §b/verify §f<验证码> §7. 请留意你输入的内容");
    }

    public String verifyConsoleOnly() {
        return get("verify.console-only", "§7该命令只能由玩家使用");
    }

    public String verifyNoPending() {
        return get("verify.no-pending", "§7你还没有待验证的绑定请求, 无法使用该命令");
    }

    public String verifyWrongCode() {
        return get("verify.wrong-code", "§c验证码错误");
    }

    public String verifyCallbackMissing() {
        return get("verify.callback-missing", "§7绑定回调未配置, 请联系管理员");
    }

    public String verifySuccess() {
        return get("verify.success", "§f恭喜你 &b{player}&f, 你已成功绑定你的&6Space&f账号");
    }

    public String verifyFailStatus() {
        return get("verify.fail-status", "&f绑定失败 &7(&b{status}&7)");
    }

    public String verifyFailError() {
        return get("verify.fail-error", "&c绑定失败, 请稍后重试");
    }

    public String ircNoPermission() {
        return get("irc.no-permission", "§7§l▎ §6§lIRC §r§c● §7你没有权限使用这个命令");
    }

    public String ircUsage() {
        return get("irc.usage", "§7§l▎ §6§lIRC §r§e● §7用法: §b/irc §f<消息>");
    }

    public String ircTooLong() {
        return get("irc.too-long", "§7§l▎ §6§lIRC §r§c● §7消息过长 (最多 {max} 字符)");
    }

    public String ircEnabled() {
        return get("irc.enabled", "§7§l▎ §6§lIRC §r§a● §7IRC 已启用, 你将接收内部通讯");
    }

    public String ircAlreadyEnabled() {
        return get("irc.already-enabled", "§7§l▎ §6§lIRC §r§e● §7IRC 已处于启用状态");
    }

    public String ircDisabled() {
        return get("irc.disabled", "§7§l▎ §6§lIRC §r§c● §7IRC 已禁用, 不再接收内部通讯");
    }

    public String ircNotEnabled() {
        return get("irc.not-enabled", "§7§l▎ §6§lIRC §r§e● §7IRC 尚未启用");
    }

    public String ircConsoleOnly() {
        return get("irc.console-only", "§7§l▎ §6§lIRC §r§c● §7该命令只能由玩家使用");
    }

    public String ircMissingIrcPerm() {
        return get("irc.missing-irc-perm", "§7§l▎ §6§lIRC §r§c● §7你缺少 aerservermonitor.irc 权限, 启用后也不会收到消息");
    }

    public String ircFormat() {
        return get("irc.format", "§8[§6§lIRC§8] §e{name} §8(§7{server}§8) §8» §f{message}");
    }
}
