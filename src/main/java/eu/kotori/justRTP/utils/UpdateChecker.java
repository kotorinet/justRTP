package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker implements Listener {
    private final JustRTP plugin;
    private final String currentVersion;
    private String latestVersion = null;
    private boolean updateAvailable = false;
    private boolean checkComplete = false;

    public UpdateChecker(JustRTP plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    public void checkForUpdates() {
        if (!plugin.getConfig().getBoolean("settings.check_for_updates", true)) {
            plugin.getRTPLogger().debug("UPDATE", "Update checker is disabled in config");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create("https://api.kotori.ink/v1/version?product=justRTP");
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "JustRTP-UpdateChecker");

                if (connection.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String jsonResponse = response.toString();
                    int versionIndex = jsonResponse.indexOf("\"version\":\"");
                    if (versionIndex != -1) {
                        int startIndex = versionIndex + 11;
                        int endIndex = jsonResponse.indexOf("\"", startIndex);
                        latestVersion = jsonResponse.substring(startIndex, endIndex);

                        if (!currentVersion.equals(latestVersion)) {
                            updateAvailable = true;
                            sendUpdateNotificationConsole();
                        } else {
                            plugin.getRTPLogger().info("UPDATE", "You are running the latest version of JustRTP!");
                        }
                    }
                }
                checkComplete = true;
            } catch (Exception e) {
                plugin.getRTPLogger().debug("UPDATE", "Failed to check for updates: " + e.getMessage());
                checkComplete = true;
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        if (!plugin.getConfig().getBoolean("settings.check_for_updates", true)) {
            return;
        }

        if (!plugin.getConfig().getBoolean("settings.notify_ops_on_update", true)) {
            return;
        }

        if (!player.isOp() && !player.hasPermission("justrtp.admin")) {
            return;
        }

        if (!checkComplete || !updateAvailable) {
            return;
        }

        plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
            if (player.isOnline()) {
                sendUpdateNotificationPlayer(player);
            }
        }, 60L);
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    private void sendUpdateNotificationConsole() {
        CommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("current_version", currentVersion))
                .resolver(Placeholder.unparsed("latest_version", latestVersion))
                .build();

        String mainColor = "#f39c12";
        String accentColor = "#e67e22";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";

        List<String> updateBlock = List.of(
                "  <color:" + mainColor + ">█╗  ██╗   <white>JustRTP <gray>Update",
                "  <color:" + mainColor + ">██║ ██╔╝   <gray>A new version is available!",
                "  <color:" + mainColor + ">█████╔╝",
                "  <color:" + accentColor + ">█╔═██╗    <white>ᴄᴜʀʀᴇɴᴛ: <gray><current_version>",
                "  <color:" + accentColor + ">█║  ██╗   <white>ʟᴀᴛᴇsᴛ: <green><latest_version>",
                "  <color:" + accentColor + ">█║  ╚═╝   <aqua><click:open_url:'https://builtbybit.com/resources/justrtp-lightweight-fast-randomtp.70322/'>Click here to download</click>",
                ""
        );

        console.sendMessage(mm.deserialize(lineSeparator));
        console.sendMessage(Component.empty());
        for (String line : updateBlock) {
            console.sendMessage(mm.deserialize(line, placeholders));
        }
        console.sendMessage(mm.deserialize(lineSeparator));
    }

    private void sendUpdateNotificationPlayer(Player player) {
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://www.spigotmc.org/resources/justrtp.118806/";
        
        player.sendMessage(mm.deserialize("<gradient:#20B2AA:#7FFFD4>--------------------------------------------------</gradient>"));
        player.sendMessage(Component.empty());
        player.sendMessage(mm.deserialize("  <gradient:#20B2AA:#7FFFD4>JustRTP</gradient> <gray>Update Available!</gray>"));
        player.sendMessage(mm.deserialize("  <gray>A new version is available: <green>" + latestVersion + "</green>"));
        player.sendMessage(mm.deserialize("  <click:open_url:'" + link + "'><hover:show_text:'<green>Click to visit download page!'><#7FFFD4><u>Click here to download the update.</u></hover></click>"));
        player.sendMessage(Component.empty());
        player.sendMessage(mm.deserialize("<gradient:#7FFFD4:#20B2AA>--------------------------------------------------</gradient>"));
    }
}
