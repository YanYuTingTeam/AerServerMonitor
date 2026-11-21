package com.aermini.servermonitor.command;

import com.aermini.servermonitor.config.MessageManager;
import com.aermini.servermonitor.irc.IrcManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class IrcToggleCommand extends Command {

    private final IrcManager ircManager;
    private final MessageManager messages;
    private final boolean enable;

    public IrcToggleCommand(IrcManager ircManager, MessageManager messages, boolean enable) {
        super(enable ? "ircon" : "ircoff", IrcManager.PERM_IRC_CON);
        this.ircManager = ircManager;
        this.messages = messages;
        this.enable = enable;
    }

    @Override
    public String getPermissionMessage() {
        return messages.ircNoPermission();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            sender.sendMessage(new TextComponent(messages.ircConsoleOnly()));
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (enable) {
            if (ircManager.isEnabled(player)) {
                player.sendMessage(new TextComponent(messages.ircAlreadyEnabled()));
                return;
            }
            ircManager.setEnabled(player, true);
            player.sendMessage(new TextComponent(messages.ircEnabled()));
            if (!player.hasPermission(IrcManager.PERM_IRC)) {
                player.sendMessage(new TextComponent(messages.ircMissingIrcPerm()));
            }
        } else {
            if (!ircManager.isEnabled(player)) {
                player.sendMessage(new TextComponent(messages.ircNotEnabled()));
                return;
            }
            ircManager.setEnabled(player, false);
            player.sendMessage(new TextComponent(messages.ircDisabled()));
        }
    }
}
