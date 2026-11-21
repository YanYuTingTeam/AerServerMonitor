package com.aermini.servermonitor.command;

import com.aermini.servermonitor.config.MessageManager;
import com.aermini.servermonitor.irc.IrcManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;

public class IrcChatCommand extends Command {

    private final IrcManager ircManager;
    private final MessageManager messages;

    public IrcChatCommand(IrcManager ircManager, MessageManager messages) {
        super("irc", IrcManager.PERM_IRC);
        this.ircManager = ircManager;
        this.messages = messages;
    }

    @Override
    public String getPermissionMessage() {
        return messages.ircNoPermission();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            sender.sendMessage(new TextComponent(messages.ircUsage()));
            return;
        }
        if (message.length() > IrcManager.MAX_MESSAGE_LENGTH) {
            sender.sendMessage(new TextComponent(messages.ircTooLong().replace("{max}", String.valueOf(IrcManager.MAX_MESSAGE_LENGTH))));
            return;
        }
        if (!ircManager.sendMessage(sender, message)) {
            sender.sendMessage(new TextComponent(messages.ircUsage()));
        }
    }
}
