package eu.kotori.justRTP.commands;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.addons.JustRTPAddon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class AddonCommand implements CommandExecutor {

    private final JustRTP plugin;

    public AddonCommand(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("justrtp.admin.addons")) {
            sender.sendMessage(Component.text("You don't have permission to use this command!")
                .color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            showAddonsList(sender);
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("disable")) {
            String addonName = args[1];
            disableAddon(sender, addonName);
            return true;
        }

        sender.sendMessage(Component.text("Usage: /justrtp addons [disable <addon>]")
            .color(NamedTextColor.RED));
        return true;
    }

    private void showAddonsList(CommandSender sender) {
        if (plugin.getAddonManager().getLoadedAddons().isEmpty()) {
            sender.sendMessage(Component.text("╔════════════════════════════════════════╗")
                .color(NamedTextColor.GOLD));
            sender.sendMessage(Component.text("║        JustRTP Addons System         ║")
                .color(NamedTextColor.GOLD));
            sender.sendMessage(Component.text("╠════════════════════════════════════════╣")
                .color(NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  No addons loaded.")
                .color(NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Place addon JARs in: plugins/JustRTP/addons/")
                .color(NamedTextColor.GRAY));
            sender.sendMessage(Component.text("╚════════════════════════════════════════╝")
                .color(NamedTextColor.GOLD));
            return;
        }

        sender.sendMessage(Component.text("╔════════════════════════════════════════╗")
            .color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║        JustRTP Loaded Addons         ║")
            .color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠════════════════════════════════════════╣")
            .color(NamedTextColor.GOLD));

        for (JustRTPAddon addon : plugin.getAddonManager().getLoadedAddons()) {
            Component addonInfo = Component.text()
                .append(Component.text("  ✓ ", NamedTextColor.GREEN))
                .append(Component.text(addon.getName(), NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" v" + addon.getVersion(), NamedTextColor.GRAY))
                .append(Component.text(" by ", NamedTextColor.DARK_GRAY))
                .append(Component.text(addon.getAuthor(), NamedTextColor.YELLOW))
                .build();
            sender.sendMessage(addonInfo);
        }

        sender.sendMessage(Component.text("╚════════════════════════════════════════╝")
            .color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Total: " + plugin.getAddonManager().getLoadedAddons().size() + " addon(s)")
            .color(NamedTextColor.GRAY));
    }

    private void disableAddon(CommandSender sender, String addonName) {
        if (!plugin.getAddonManager().isAddonLoaded(addonName)) {
            sender.sendMessage(Component.text("Addon '" + addonName + "' is not loaded!")
                .color(NamedTextColor.RED));
            return;
        }

        boolean success = plugin.getAddonManager().disableAddon(addonName);

        if (success) {
            sender.sendMessage(Component.text("✓ Successfully disabled addon: " + addonName)
                .color(NamedTextColor.GREEN));
            sender.sendMessage(Component.text("  Restart server to re-enable it.")
                .color(NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("✗ Failed to disable addon: " + addonName)
                .color(NamedTextColor.RED));
            sender.sendMessage(Component.text("  Check console for errors.")
                .color(NamedTextColor.GRAY));
        }
    }
}
