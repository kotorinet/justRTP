package eu.kotori.justRTP.commands;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RTPZoneTabCompleter implements TabCompleter {
    private final JustRTP plugin;

    public RTPZoneTabCompleter(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (!plugin.getConfig().getBoolean("rtpzone_command.enabled", true)) {
            return new ArrayList<>();
        }

        List<String> completions = new ArrayList<>();
        String currentArg = args[args.length - 1];

        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>();
            if (sender.hasPermission("justrtp.admin.zone")) {
                subCommands.addAll(Arrays.asList("setup", "delete", "list", "cancel", "sethologram", "delhologram",
                        "sync", "push", "pull", "status"));
            }
            if (sender.hasPermission("justrtp.command.zone.ignore")) {
                subCommands.add("ignore");
            }
            StringUtil.copyPartialMatches(currentArg, subCommands, completions);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("sethologram")
                || args[0].equalsIgnoreCase("delhologram"))) {
            if (sender.hasPermission("justrtp.admin.zone")) {
                List<String> zoneIds = new ArrayList<>(plugin.getRtpZoneManager().getZoneIds());
                StringUtil.copyPartialMatches(currentArg, zoneIds, completions);
            }
        }

        return completions;
    }
}