package com.aermini.servermonitor.command;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.FastLoginDatabaseManager;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class VerifyCommand extends Command {

    private final AerServerMonitor plugin;
    private final PluginConfig config;
    private final PlayerDAO playerDAO;
    private final SkinDatabaseManager skinDB;
    private final FastLoginDatabaseManager fastLoginDB;
    private final ConcurrentHashMap<String, AerServerMonitor.PendingBind> pendingBinds;
    private final Logger logger;

    public VerifyCommand(AerServerMonitor plugin, PluginConfig config, PlayerDAO playerDAO,
                         SkinDatabaseManager skinDB, FastLoginDatabaseManager fastLoginDB,
                         ConcurrentHashMap<String, AerServerMonitor.PendingBind> pendingBinds, Logger logger) {
        super("verify");
        this.plugin = plugin;
        this.config = config;
        this.playerDAO = playerDAO;
        this.skinDB = skinDB;
        this.fastLoginDB = fastLoginDB;
        this.pendingBinds = pendingBinds;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(new TextComponent("§7正确的用法是 §b/verify §f<验证码> §7. 请留意你输入的内容"));
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        String uuid = player.getUniqueId().toString();
        AerServerMonitor.PendingBind bind = pendingBinds.get(uuid);
        if (bind == null) {
            sender.sendMessage(new TextComponent("§7你还没有待验证的绑定请求, 无法使用该命令"));
            return;
        }
        if (!args[0].equals(bind.code)) {
            sender.sendMessage(new TextComponent("§c验证码错误"));
            return;
        }
        pendingBinds.remove(uuid);
        String skin = skinDB.getSkinUuid(uuid);
        String playerIp = player.getAddress() != null ? player.getAddress().getHostString() : "";
        // offline_uuid由名字推导, premium_uuid查FastLogin的premium表, 给网站做身份归一
        String offlineUuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + bind.playerName).getBytes(StandardCharsets.UTF_8)).toString();
        String premiumUuid = fastLoginDB.getPremiumUuid(bind.playerName);
        JsonObject body = new JsonObject();
        body.addProperty("task", bind.task);
        body.addProperty("name", bind.playerName);
        body.addProperty("uuid", uuid);
        body.addProperty("offline_uuid", offlineUuid);
        body.addProperty("premium_uuid", premiumUuid != null ? premiumUuid : "");
        body.addProperty("ip", playerIp);
        body.addProperty("skin", skin != null ? skin : "");
        String callbackUrl = config.getBindCallbackUrl();
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            sender.sendMessage(new TextComponent("§7绑定回调未配置, 请联系管理员"));
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(callbackUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int status = conn.getResponseCode();
            conn.disconnect();

            if (status >= 200 && status < 300) {
                sender.sendMessage(new TextComponent("§f恭喜你 &b"+bind.playerName+"&f, 你已成功绑定你的&6Space&f账号"));
            } else {
                sender.sendMessage(new TextComponent("&f绑定失败 &7(&b" + status + "&7)"));
            }
        } catch (Exception e) {
            logger.warning("[Bind] callback error: " + e.getMessage());
            sender.sendMessage(new TextComponent("&c绑定失败, 请稍后重试"));
        }
    }
}
