package com.aermini.servermonitor.irc;

import com.aermini.servermonitor.config.MessageManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IrcManager {

    public static final String PERM_IRC = "aerservermonitor.irc";
    public static final String PERM_IRC_CON = "aerservermonitor.irccon";
    public static final int MAX_MESSAGE_LENGTH = 240;

    private final Set<String> enabled = ConcurrentHashMap.newKeySet();
    private final MessageManager messages;

    public IrcManager(MessageManager messages) {
        this.messages = messages;
    }

    public boolean isEnabled(ProxiedPlayer player) {
        return enabled.contains(player.getUniqueId().toString());
    }

    public void setEnabled(ProxiedPlayer player, boolean value) {
        if (value) {
            enabled.add(player.getUniqueId().toString());
        } else {
            enabled.remove(player.getUniqueId().toString());
        }
    }

    public boolean sendMessage(CommandSender sender, String message) {
        if (message == null) return false;
        String trimmed = message.trim().replace("§", "");
        if (trimmed.isEmpty() || trimmed.length() > MAX_MESSAGE_LENGTH) return false;
        String name = "CONSOLE";
        String server = "-";
        if (sender instanceof ProxiedPlayer) {
            ProxiedPlayer player = (ProxiedPlayer) sender;
            name = player.getName();
            if (player.getServer() != null) {
                server = player.getServer().getInfo().getName();
            }
        }
        TextComponent line = new TextComponent(messages.ircFormat().replace("{name}", name).replace("{server}", server).replace("{message}", trimmed));
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player.hasPermission(PERM_IRC) && enabled.contains(player.getUniqueId().toString())) {
                player.sendMessage(line);
            }
        }
        ProxyServer.getInstance().getConsole().sendMessage("[IRC] " + name + " (" + server + ") » " + trimmed);
        return true;
    }
}
