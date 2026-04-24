package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class UpdateChecker
implements Listener {
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
        if (!this.plugin.getConfig().getBoolean("settings.check_for_updates", true)) {
            this.plugin.getRTPLogger().debug("UPDATE", "Update checker is disabled in config");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create("https://api.deltura.net/v1/version?product=justRTP");
                HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "JustRTP-UpdateChecker");
                if (connection.getResponseCode() == 200) {
                    String line;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    String jsonResponse = response.toString();
                    int versionIndex = jsonResponse.indexOf("\"version\":\"");
                    if (versionIndex != -1) {
                        int startIndex = versionIndex + 11;
                        int endIndex = jsonResponse.indexOf("\"", startIndex);
                        this.latestVersion = jsonResponse.substring(startIndex, endIndex);
                        if (!this.currentVersion.equals(this.latestVersion)) {
                            this.updateAvailable = true;
                            this.sendUpdateNotificationConsole();
                        } else {
                            this.plugin.getRTPLogger().info("UPDATE", "You are running the latest version of JustRTP!");
                        }
                    }
                }
                this.checkComplete = true;
            }
            catch (Exception e) {
                this.plugin.getRTPLogger().debug("UPDATE", "Failed to check for updates: " + e.getMessage());
                this.checkComplete = true;
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!this.plugin.getConfig().getBoolean("settings.check_for_updates", true)) {
            return;
        }
        if (!this.plugin.getConfig().getBoolean("settings.notify_ops_on_update", true)) {
            return;
        }
        if (!player.isOp() && !player.hasPermission("justrtp.admin")) {
            return;
        }
        if (!this.checkComplete || !this.updateAvailable) {
            return;
        }
        this.plugin.getFoliaScheduler().runAtEntityLater((Entity)player, () -> {
            if (player.isOnline()) {
                this.sendUpdateNotificationPlayer(player);
            }
        }, 60L);
    }

    public boolean isUpdateAvailable() {
        return this.updateAvailable;
    }

    public String getLatestVersion() {
        return this.latestVersion;
    }

    private void sendUpdateNotificationConsole() {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        MiniMessage mm = MiniMessage.miniMessage();
        TagResolver placeholders = TagResolver.builder().resolver((TagResolver)Placeholder.unparsed((String)"current_version", (String)this.currentVersion)).resolver((TagResolver)Placeholder.unparsed((String)"latest_version", (String)this.latestVersion)).build();
        String mainColor = "#f39c12";
        String accentColor = "#e67e22";
        String lineSeparator = "<dark_gray><strikethrough>                                                                                ";
        List<String> updateBlock = List.of("  <color:" + mainColor + ">\u2588\u2557  \u2588\u2588\u2557   <white>JustRTP <gray>Update", "  <color:" + mainColor + ">\u2588\u2588\u2551 \u2588\u2588\u2554\u255d   <gray>A new version is available!", "  <color:" + mainColor + ">\u2588\u2588\u2588\u2588\u2588\u2554\u255d", "  <color:" + accentColor + ">\u2588\u2554\u2550\u2588\u2588\u2557    <white>\u1d04\u1d1c\u0280\u0280\u1d07\u0274\u1d1b: <gray><current_version>", "  <color:" + accentColor + ">\u2588\u2551  \u2588\u2588\u2557   <white>\u029f\u1d00\u1d1b\u1d07s\u1d1b: <green><latest_version>", "  <color:" + accentColor + ">\u2588\u2551  \u255a\u2550\u255d   <aqua><click:open_url:'https://www.spigotmc.org/resources/justrtp.118806/'>Click here to download</click>", "");
        console.sendMessage(mm.deserialize(lineSeparator));
        console.sendMessage(Component.empty());
        for (String line : updateBlock) {
            console.sendMessage(mm.deserialize(line, placeholders));
        }
        console.sendMessage(mm.deserialize(lineSeparator));
    }

    private void sendUpdateNotificationPlayer(Player player) {
        MiniMessage mm = MiniMessage.miniMessage();
        String link = "https://builtbybit.com/resources/justrtp-lightweight-fast-randomtp.70322/";
        player.sendMessage(mm.deserialize("<gradient:#20B2AA:#7FFFD4>--------------------------------------------------</gradient>"));
        player.sendMessage(Component.empty());
        player.sendMessage(mm.deserialize("  <gradient:#20B2AA:#7FFFD4>JustRTP</gradient> <gray>Update Available!</gray>"));
        player.sendMessage(mm.deserialize("  <gray>A new version is available: <green>" + this.latestVersion + "</green>"));
        player.sendMessage(mm.deserialize("  <click:open_url:'" + link + "'><hover:show_text:'<green>Click to visit download page!'><#7FFFD4><u>Click here to download the update.</u></hover></click>"));
        player.sendMessage(Component.empty());
        player.sendMessage(mm.deserialize("<gradient:#7FFFD4:#20B2AA>--------------------------------------------------</gradient>"));
    }
}
