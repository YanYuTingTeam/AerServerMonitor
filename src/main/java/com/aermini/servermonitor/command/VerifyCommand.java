package com.aermini.servermonitor.command;

import com.aermini.servermonitor.AerServerMonitor;
import com.aermini.servermonitor.config.PluginConfig;
import com.aermini.servermonitor.database.dao.FastLoginDatabaseManager;
import com.aermini.servermonitor.database.dao.PlayerDAO;
import com.aermini.servermonitor.database.dao.SkinDatabaseManager;
import com.aermini.servermonitor.database.redis.BindStore;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class VerifyCommand extends Command {

    private final AerServerMonitor plugin;
    private final PluginConfig config;
    private final PlayerDAO playerDAO;
    private final SkinDatabaseManager skinDB;
    private final FastLoginDatabaseManager fastLoginDB;
    private final BindStore bindStore;
    private final Logger logger;

    public VerifyCommand(AerServerMonitor plugin, PluginConfig config, PlayerDAO playerDAO,
                         SkinDatabaseManager skinDB, FastLoginDatabaseManager fastLoginDB,
                         BindStore bindStore, Logger logger) {
        super("verify");
        this.plugin = plugin;
        this.config = config;
        this.playerDAO = playerDAO;
        this.skinDB = skinDB;
        this.fastLoginDB = fastLoginDB;
        this.bindStore = bindStore;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyUsage()));
            return;
        }
        if (!(sender instanceof ProxiedPlayer)) {
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyConsoleOnly()));
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        String uuid = player.getUniqueId().toString();
        AerServerMonitor.PendingBind bind = bindStore.get(uuid);
        if (bind == null) {
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyNoPending()));
            return;
        }
        bind = bindStore.verifyAndRemove(uuid, args[0]);
        if (bind == null) {
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyWrongCode()));
            return;
        }
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
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyCallbackMissing()));
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
                sender.sendMessage(new TextComponent(plugin.getMessages().verifySuccess().replace("{player}", bind.playerName)));
            } else {
                sender.sendMessage(new TextComponent(plugin.getMessages().verifyFailStatus().replace("{status}", String.valueOf(status))));
            }
        } catch (Exception e) {
            logger.warning("[Bind] callback error: " + e.getMessage());
            sender.sendMessage(new TextComponent(plugin.getMessages().verifyFailError()));
        }
    }
}
