package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

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
}
