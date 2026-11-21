package com.aermini.servermonitor.command;

import com.aermini.servermonitor.AerServerMonitor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import java.util.ArrayList;
import java.util.List;

public class MonitorCommand extends Command implements TabExecutor {
    private final AerServerMonitor plugin;
    public MonitorCommand(AerServerMonitor plugin) {
        super("aerservermonitor", "aerservermonitor.info", "asmonitor", "asm");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendInfo(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("aerservermonitor.admin")) {
                sender.sendMessage(new TextComponent(plugin.getMessages().monitorNoPermission()));
                return;
            }
            try {
                plugin.reloadPluginConfig();
                sender.sendMessage(new TextComponent(plugin.getMessages().monitorReloadSuccess()));
            } catch (Exception e) {
                sender.sendMessage(new TextComponent(plugin.getMessages().monitorReloadFail().replace("{error}", String.valueOf(e.getMessage()))));
            }
            return;
        }
        sender.sendMessage(new TextComponent(plugin.getMessages().monitorUnknownCommand()));
    }

    private void sendInfo(CommandSender sender) {
        for (String line : plugin.getMessages().monitorInfo()) {
            sender.sendMessage(new TextComponent(line));
        }
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) completions.add("reload");
        return completions;
    }
}
