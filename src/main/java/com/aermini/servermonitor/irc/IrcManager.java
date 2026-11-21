package com.aermini.servermonitor.irc;

import com.aermini.servermonitor.config.MessageManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class IrcManager {

    public static final String PERM_IRC = "aerservermonitor.irc";
    public static final int MAX_MESSAGE_LENGTH = 240;

    private final MessageManager messages;

    public IrcManager(MessageManager messages) {
        this.messages = messages;
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
            if (player.hasPermission(PERM_IRC)) {
                player.sendMessage(line);
            }
        }
        ProxyServer.getInstance().getConsole().sendMessage("[IRC] " + name + " (" + server + ") » " + trimmed);
        return true;
    }
}
