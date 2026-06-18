package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.managers.DashboardManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;

public class StartupMessage {

    public static void sendStartupMessage(JustRTP plugin) {
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();

        String check = "<green>✔</green>";
        String cross = "<red>✖</red>";

        String proxyStatus = plugin.getConfigManager().getProxyEnabled() ? check : cross;
        String papiStatus = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ? check : cross;
        String worldGuardStatus = Bukkit.getPluginManager().isPluginEnabled("WorldGuard") ? check : cross;

        String redisStatus = "<gray>-</gray>";
        if (plugin.getConfigManager().isRedisEnabled()) {
            redisStatus = check;
        }

        String engine;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            engine = "Folia";
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                engine = "Paper";
            } catch (ClassNotFoundException e2) {
                engine = "Spigot/Bukkit";
            }
        }

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()))
                .resolver(Placeholder.unparsed("author", String.join(", ", plugin.getPluginMeta().getAuthors())))
                .build();

        String mainColor = "#20B2AA";
        String accentColor = "#7FFFD4";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";

        console.sendMessage(mm.deserialize(lineSeparator));
        console.sendMessage(Component.empty());
        console.sendMessage(
                mm.deserialize("  <color:" + mainColor + ">█╗  ██╗   <white>JustRTP <gray>v<version>", placeholders));
        console.sendMessage(
                mm.deserialize("  <color:" + mainColor + ">██║ ██╔╝   <gray>ʙʏ <white><author>", placeholders));
        console.sendMessage(
                mm.deserialize("  <color:" + mainColor + ">█████╔╝    <white>sᴛᴀᴛᴜs: <color:#2ecc71>Active"));
        console.sendMessage(mm.deserialize("  <color:" + accentColor + ">█╔═██╗"));
        console.sendMessage(
                mm.deserialize("  <color:" + accentColor + ">█║  ██╗   <white>ᴘʀᴏxʏ sᴜᴘᴘᴏʀᴛ: " + proxyStatus));
        console.sendMessage(mm.deserialize(
                "  <color:" + accentColor + ">█║  ╚═╝   <white>ʀᴇᴅɪs ᴄᴀᴄʜᴇ: " + redisStatus + " <gray>(optional)"));
        console.sendMessage(Component.empty());
        console.sendMessage(mm.deserialize("  <white>ᴡᴏʀʟᴅɢᴜᴀʀᴅ: " + worldGuardStatus + " <gray>| <white>ᴘᴀᴘɪ: "
                + papiStatus + " <gray>| <white>ᴇɴɢɪɴᴇ: <gray>" + engine));
        console.sendMessage(Component.empty());
        console.sendMessage(mm.deserialize(lineSeparator));
    }

    public static void sendModuleStatus(JustRTP plugin) {
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";

        List<DashboardManager.ModuleStatus> modules = DashboardManager.moduleStatuses(plugin);
        int enabled = 0;
        int toggleable = 0;
        for (DashboardManager.ModuleStatus module : modules) {
            if (module.toggleable()) {
                toggleable++;
                if (module.enabled()) {
                    enabled++;
                }
            }
        }

        boolean dashboardOn = plugin.getDashboardManager() != null && plugin.getDashboardManager().isEnabled();
        String dashboardState = dashboardOn ? "<green>enabled" : "<red>disabled";

        console.sendMessage(mm.deserialize(lineSeparator));
        console.sendMessage(Component.empty());
        console.sendMessage(mm.deserialize("  <color:#7FFFD4>ᴅᴀsʜʙᴏᴀʀᴅ</color> <dark_gray>·</dark_gray> "
                + "<white>/rtp dashboard <dark_gray>(" + dashboardState + "<dark_gray>)"));
        console.sendMessage(mm.deserialize("  <gray>ᴍᴏᴅᴜʟᴇs ᴇɴᴀʙʟᴇᴅ: <white>" + enabled
                + "<dark_gray>/<white>" + toggleable + " <dark_gray>(<gold>⟳<dark_gray> = restart needed)"));
        console.sendMessage(Component.empty());

        for (int i = 0; i < modules.size(); i += 2) {
            String left = formatModuleEntry(modules.get(i));
            String row = "  " + left;
            if (i + 1 < modules.size()) {
                row += "   " + formatModuleEntry(modules.get(i + 1));
            }
            console.sendMessage(mm.deserialize(row));
        }

        console.sendMessage(Component.empty());
        console.sendMessage(mm.deserialize(lineSeparator));
    }

    private static String formatModuleEntry(DashboardManager.ModuleStatus module) {
        String icon;
        if (!module.toggleable()) {
            icon = "<gray>⚙";
        } else if (module.enabled()) {
            icon = "<green>✔";
        } else {
            icon = "<red>✖";
        }
        String restart = module.restart() ? " <gold>⟳" : "";
        return icon + " <gray>" + padRight(module.label(), 22) + restart;
    }

    private static String padRight(String text, int length) {
        if (text.length() >= length) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
