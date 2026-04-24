package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.commands.RTPCommand;
import eu.kotori.justRTP.commands.RTPTabCompleter;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

public class CommandManager {

    private static final String[] ALIASES = {"rtp", "jrtp", "wild", "randomtp"};

    private final JustRTP plugin;
    private FileConfiguration cmdConfig;
    private final NamespacedKey wandKey;

    public CommandManager(JustRTP plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "rtp_zone_wand");
        loadCommandConfig();
    }

    private void loadCommandConfig() {
        File cmdFile = new File(plugin.getDataFolder(), "commands.yml");
        if (!cmdFile.exists()) {
            plugin.saveResource("commands.yml", false);
        }
        cmdConfig = YamlConfiguration.loadConfiguration(cmdFile);
    }

    public boolean isAliasEnabled(String alias) {
        String cleanAlias = alias;
        int colonIndex = alias.indexOf(':');
        if (colonIndex >= 0) {
            cleanAlias = alias.substring(colonIndex + 1);
        }

        if (cleanAlias.equalsIgnoreCase("justrtp")) {
            return true;
        }

        String configKey = "enabled-aliases." + cleanAlias.toLowerCase();

        return cmdConfig.getBoolean(configKey, true);
    }

    public void registerCommands() {
        loadCommandConfig();

        RTPCommand rtpExecutor = new RTPCommand(plugin);
        RTPTabCompleter rtpCompleter = new RTPTabCompleter(plugin);

        PluginCommand mainCommand = plugin.getCommand("justrtp");
        if (mainCommand != null) {
            mainCommand.setExecutor(rtpExecutor);
            mainCommand.setTabCompleter(rtpCompleter);
        }

        updateCommandAliases();
    }

    private void updateCommandAliases() {
        try {
            Map<String, Command> knownCommands = plugin.getServer().getCommandMap().getKnownCommands();
            String pluginPrefix = plugin.getName().toLowerCase();
            PluginCommand mainCommand = plugin.getCommand("justrtp");

            for (String alias : ALIASES) {
                String lowerAlias = alias.toLowerCase();
                String namespacedAlias = pluginPrefix + ":" + lowerAlias;

                if (!isAliasEnabled(alias)) {

                    Command cmd = knownCommands.get(lowerAlias);
                    if (cmd instanceof PluginCommand pluginCmd && pluginCmd.getPlugin() == plugin) {
                        knownCommands.remove(lowerAlias);
                    }
                    knownCommands.remove(namespacedAlias);

                    plugin.getRTPLogger().debug("COMMAND",
                            "Unregistered disabled alias '/" + alias + "' from command map");
                } else if (mainCommand != null) {

                    Command existing = knownCommands.get(lowerAlias);
                    if (existing == null) {
                        knownCommands.put(lowerAlias, mainCommand);
                    }
                    knownCommands.putIfAbsent(namespacedAlias, mainCommand);
                }
            }

            try {
                plugin.getServer().getClass().getDeclaredMethod("syncCommands").invoke(plugin.getServer());
            } catch (Exception ignored) {

            }
        } catch (Exception e) {
            plugin.getRTPLogger().debug("COMMAND",
                    "Could not update command aliases: " + e.getMessage());
        }
    }

    public NamespacedKey getWandKey() {
        return wandKey;
    }
}
