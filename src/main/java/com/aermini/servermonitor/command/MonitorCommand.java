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
        super("aerservermonitor", "aermonitor.info", "asmonitor", "asm");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendInfo(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("aermonitor.admin")) {
                sender.sendMessage(new TextComponent("§7§l▎ §6§lAerServerMonitor §r§c● §7你没有权限使用这个命令"));
                return;
            }
            try {
                plugin.reloadPluginConfig();
                sender.sendMessage(new TextComponent("§7§l▎ §6§lAerServerMonitor §r§a● §7配置已重载"));
            } catch (Exception e) {
                sender.sendMessage(new TextComponent("§7§l▎ §6§lAerServerMonitor §r§c● §7重载失败: "+e.getMessage()));
            }
            return;
        }
        sender.sendMessage(new TextComponent("§7§l▎ §6§lAerServerMonitor §r§e● §7命令未知. Usage: §f/asm §breload"));
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage(new TextComponent("§4§m=============§r§6 §lAerServerMonitor §r§4§m============="));
        sender.sendMessage(new TextComponent("        §6§lAerServerMonitor §r§fv1.0 §8| §b§lYanYuTing"));
        sender.sendMessage(new TextComponent(" §7Projects:"));
        sender.sendMessage(new TextComponent("    §bhttps://github.com/YanYuTingTeam/AerServerMonitor"));
        sender.sendMessage(new TextComponent(" §7Authors:"));
        sender.sendMessage(new TextComponent("    §eAerMini §8| §dhttps://github.com/AerMini2024"));
        sender.sendMessage(new TextComponent(" §7Usage:"));
        sender.sendMessage(new TextComponent("    §f/asm reload"));
        sender.sendMessage(new TextComponent("§4§m=============================================="));
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) completions.add("reload");
        return completions;
    }
}
