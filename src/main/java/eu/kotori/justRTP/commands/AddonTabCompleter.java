package eu.kotori.justRTP.commands;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class AddonTabCompleter implements TabCompleter {

    private final JustRTP plugin;

    public AddonTabCompleter(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("justrtp.admin.addons")) {
            return completions;
        }

        if (args.length == 1) {
            completions.add("disable");
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("disable")) {
            return plugin.getAddonManager().getAddonNames();
        }

        return completions;
    }
}
