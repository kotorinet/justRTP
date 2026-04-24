package eu.kotori.justRTP.managers;
import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LocaleManager {
    private final JustRTP plugin;
    private FileConfiguration messagesConfig;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private File messagesFile;
    private final Set<String> warnedMissingKeys = Collections.synchronizedSet(new HashSet<>());

    public LocaleManager(JustRTP plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        warnedMissingKeys.clear();
    }
    public void sendMessage(CommandSender sender, String path, TagResolver... resolvers) {
        String message = messagesConfig.getString(path);

        if (message != null && message.isEmpty()) {
            return;
        }

        if (message == null) {
            if (!warnedMissingKeys.contains(path)) {
                warnedMissingKeys.add(path);
                plugin.getLogger().warning("Missing message key: '" + path + "' - Please check messages.yml");
            }

            message = "<red>Message not found: " + path + "</red>";
        }

        if (message.isBlank()) return;

        String prefix = getRawMessage("prefix", "");
        message = message.replace("%prefix%", prefix);
        Component parsedComponent = miniMessage.deserialize(message, resolvers);
        sender.sendMessage(parsedComponent);
    }

    public void sendMessage(CommandSender sender, String path, Map<String, String> placeholders) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolvers.add(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        sendMessage(sender, path, resolvers.toArray(new TagResolver[0]));
    }

    public String getRawMessage(String path, String def) {
        return messagesConfig.getString(path, def);
    }

    public String getRawMessage(String path) {
        return getRawMessage(path, "");
    }
}