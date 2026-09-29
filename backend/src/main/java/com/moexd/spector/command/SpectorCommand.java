package com.moexd.spector.command;

import com.moexd.spector.MoexdSpectorPlugin;
import com.moexd.spector.observe.ObservationManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpectorCommand implements TabExecutor {

    private static final String ADMIN_PERMISSION = "moexdspector.admin";

    private final MoexdSpectorPlugin plugin;
    private final ObservationManager manager;
    private final com.moexd.spector.velocity.VelocityBridge bridge;

    public SpectorCommand(MoexdSpectorPlugin plugin, ObservationManager manager,
                          com.moexd.spector.velocity.VelocityBridge bridge) {
        this.plugin = plugin;
        this.manager = manager;
        this.bridge = bridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("moexdspector.use")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("usage"));
            return true;
        }

        String arg = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);

        switch (arg) {
            // ---- 管理侧子指令：控制台可用 ----
            case "reload" -> {
                if (!admin) {
                    sender.sendMessage(plugin.msg("no-permission"));
                    return true;
                }
                plugin.reloadConfigs();
                sender.sendMessage(plugin.msg("reloaded"));
            }
            case "afk" -> {
                if (args.length >= 2) {
                    sender.sendMessage(String.join("\n", manager.afkDetailLines(args[1])));
                } else {
                    sender.sendMessage(String.join("\n", manager.afkReportLines()));
                }
            }
            case "off" -> {
                if (args.length >= 2) {
                    // 强制断开指定观察者的会话（需管理权限，控制台可用）
                    if (!admin) {
                        sender.sendMessage(plugin.msg("no-permission"));
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(plugin.msg("target-not-found"));
                        return true;
                    }
                    boolean existed = manager.stopOther(target.getUniqueId(),
                            sender instanceof Player p ? p : null);
                    sender.sendMessage(plugin.msg(existed ? "admin-stopped" : "target-not-found",
                            "%target%", target.getName()));
                    return true;
                }
                if (!(sender instanceof Player observer)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                manager.stopSelf(observer.getUniqueId());
                observer.sendMessage(plugin.msg("stopped"));
            }
            // ---- 观察侧子指令：仅玩家可用 ----
            case "random" -> {
                if (!(sender instanceof Player observer)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                manager.startRandom(observer);
            }
            case "status" -> {
                if (!(sender instanceof Player observer)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                observer.sendMessage(String.join("\n", manager.statusLines(observer.getUniqueId())));
            }
            case "next", "pre", "prev" -> {
                if (!(sender instanceof Player observer)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                manager.stepObservation(observer, arg.equals("next"));
            }
            default -> {
                if (!(sender instanceof Player observer)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target != null && !target.getUniqueId().equals(observer.getUniqueId())) {
                    if (plugin.isExcluded(target)) {
                        observer.sendMessage(plugin.msg("target-excluded"));
                        return true;
                    }
                    manager.startManual(observer, target);
                    return true;
                }
                if (bridge != null && bridge.enabled() && !args[0].equalsIgnoreCase(sender.getName())) {
                    // 本服找不到该玩家：经代理跨服定位并跟过去观察
                    manager.observeCrossServer(observer, args[0], false);
                    return true;
                }
                observer.sendMessage(plugin.msg("target-not-found"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("moexdspector.use")) {
            return completions;
        }
        if (args.length == 2 && args[0].toLowerCase(Locale.ROOT).equals("afk")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String name : manager.afkKnownPlayers()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(name);
                }
            }
            return completions;
        }
        if (args.length != 1) {
            return completions;
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(List.of("random", "next", "pre", "status", "afk", "off"));
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            options.addFirst("reload");
        }
        for (String option : options) {
            if (option.startsWith(prefix)) {
                completions.add(option);
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                completions.add(online.getName());
            }
        }
        // 跨服开启时补全全网玩家名（代理周期下发的缓存，含本服）
        if (bridge != null && bridge.enabled()) {
            for (String name : bridge.networkPlayerNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)
                        && completions.stream().noneMatch(c -> c.equalsIgnoreCase(name))) {
                    completions.add(name);
                }
            }
        }
        return completions;
    }
}
