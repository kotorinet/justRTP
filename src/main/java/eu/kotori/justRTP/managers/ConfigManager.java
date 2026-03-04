package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class ConfigManager {
    private final JustRTP plugin;
    private final Map<String, String> worldAliases = new HashMap<>();

    public ConfigManager(JustRTP plugin) {
        this.plugin = plugin;
        loadWorldAliases();
    }

    public void reload() {
        plugin.reloadConfig();
        loadWorldAliases();
    }

    private void loadWorldAliases() {
        worldAliases.clear();
        ConfigurationSection aliasSection = plugin.getConfig().getConfigurationSection("rtp_settings.world_aliases");
        if (aliasSection != null) {
            for (String alias : aliasSection.getKeys(false)) {
                worldAliases.put(alias.toLowerCase(), aliasSection.getString(alias));
            }
        }
    }

    public String resolveWorldAlias(String input) {
        if (input == null)
            return null;
        return worldAliases.getOrDefault(input.toLowerCase(), input);
    }

    public Map<String, String> getWorldAliases() {
        return Collections.unmodifiableMap(worldAliases);
    }

    private Optional<String> getHighestPriorityGroup(Player player) {
        if (player == null) {
            return Optional.empty();
        }

        ConfigurationSection groupsSection = plugin.getConfig().getConfigurationSection("permission_groups.groups");
        if (!plugin.getConfig().getBoolean("permission_groups.enabled") || groupsSection == null) {
            return Optional.empty();
        }
        return groupsSection.getKeys(false).stream()
                .filter(groupName -> player.hasPermission("justrtp.group." + groupName))
                .min(Comparator
                        .comparingInt(groupName -> groupsSection.getInt(groupName + ".priority", Integer.MAX_VALUE)));
    }

    private Optional<String> getHighestPriorityGroupWithWorldConfig(Player player, String worldName, String path) {
        if (player == null) {
            return Optional.empty();
        }

        ConfigurationSection groupsSection = plugin.getConfig().getConfigurationSection("permission_groups.groups");
        if (!plugin.getConfig().getBoolean("permission_groups.enabled") || groupsSection == null) {
            return Optional.empty();
        }

        return groupsSection.getKeys(false).stream()
                .filter(groupName -> player.hasPermission("justrtp.group." + groupName))
                .filter(groupName -> plugin.getConfig().contains(
                        "permission_groups.groups." + groupName + ".worlds." + worldName + "." + path))
                .min(Comparator
                        .comparingInt(groupName -> groupsSection.getInt(groupName + ".priority", Integer.MAX_VALUE)));
    }

    public int getInt(Player player, World world, String path, int def) {
        Optional<String> groupOpt = getHighestPriorityGroupWithWorldConfig(player, world.getName(), path);
        if (groupOpt.isPresent()) {
            String group = groupOpt.get();
            String groupPath = "permission_groups.groups." + group + ".worlds." + world.getName() + "." + path;
            return plugin.getConfig().getInt(groupPath);
        }
        String customWorldPath = "custom_worlds." + world.getName() + "." + path;
        String generalPath = "settings." + path;
        return plugin.getConfig().getInt(customWorldPath, plugin.getConfig().getInt(generalPath, def));
    }

    public int getIntByWorldName(Player player, String worldName, String path, int def) {
        Optional<String> groupOpt = getHighestPriorityGroupWithWorldConfig(player, worldName, path);
        if (groupOpt.isPresent()) {
            String group = groupOpt.get();
            String groupPath = "permission_groups.groups." + group + ".worlds." + worldName + "." + path;
            return plugin.getConfig().getInt(groupPath);
        }
        String customWorldPath = "custom_worlds." + worldName + "." + path;
        String generalPath = "settings." + path;
        return plugin.getConfig().getInt(customWorldPath, plugin.getConfig().getInt(generalPath, def));
    }

    public double getDouble(Player player, World world, String path, double def) {
        Optional<String> groupOpt = getHighestPriorityGroupWithWorldConfig(player, world.getName(), path);
        if (groupOpt.isPresent()) {
            String group = groupOpt.get();
            String groupPath = "permission_groups.groups." + group + ".worlds." + world.getName() + "." + path;
            return plugin.getConfig().getDouble(groupPath);
        }
        String customWorldPath = "custom_worlds." + world.getName() + "." + path;
        String generalPath = "settings." + path;
        String economyPath = "economy." + path;

        if (plugin.getConfig().contains(customWorldPath)) {
            return plugin.getConfig().getDouble(customWorldPath);
        }
        if (plugin.getConfig().contains(generalPath)) {
            return plugin.getConfig().getDouble(generalPath);
        }
        return plugin.getConfig().getDouble(economyPath, def);
    }

    public int getCooldown(Player player, World world) {
        if (player.hasPermission("justrtp.cooldown.bypass"))
            return 0;
        return getInt(player, world, "cooldown", 30);
    }

    public int getDelay(Player player, World world) {
        if (player.hasPermission("justrtp.delay.bypass"))
            return 0;
        return getInt(player, world, "delay", 3);
    }

    public double getEconomyCost(Player player, World world) {
        if (player.hasPermission("justrtp.cost.bypass"))
            return 0.0;
        return getDouble(player, world, "cost", 100.0);
    }

    public double getRadiusBasedCost(World world, int maxRadius) {
        String worldPath = "custom_worlds." + world.getName() + ".radius_pricing";

        if (!plugin.getConfig().getBoolean(worldPath + ".enabled", false)) {
            return 0.0;
        }

        ConfigurationSection tiersSection = plugin.getConfig().getConfigurationSection(worldPath + ".tiers");
        if (tiersSection == null) {
            return 0.0;
        }

        double tierCost = 0.0;
        int matchedMaxRadius = Integer.MAX_VALUE;

        for (String tierKey : tiersSection.getKeys(false)) {
            ConfigurationSection tier = tiersSection.getConfigurationSection(tierKey);
            if (tier == null)
                continue;

            int tierMaxRadius = tier.getInt("max_radius", 0);
            double cost = tier.getDouble("cost", 0.0);

            if (maxRadius <= tierMaxRadius && tierMaxRadius < matchedMaxRadius) {
                matchedMaxRadius = tierMaxRadius;
                tierCost = cost;
            }
        }

        return tierCost;
    }

    public boolean getProxyEnabled() {
        return plugin.getConfig().getBoolean("proxy.enabled", false);
    }

    public String getProxyThisServerName() {
        return plugin.getConfig().getString("proxy.this_server_name", "");
    }

    public String getProxyServerAlias(String serverName) {
        return plugin.getConfig().getString("proxy.aliases." + serverName, serverName);
    }

    public boolean getCrossServerNoDelay() {
        return plugin.getConfig().getBoolean("proxy.cross_server_rtp_no_delay", true);
    }

    public List<String> getProxyServers() {
        return plugin.getConfig().getStringList("proxy.servers");
    }

    public boolean isProxyMySqlEnabled() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "mysql.yml")).getBoolean("enabled",
                false);
    }

    public boolean isDebugMode() {
        return plugin.getConfig().getBoolean("settings.debug", false);
    }

    public boolean isCacheEnabledForWorld(World world) {
        return plugin.getConfig().isConfigurationSection("location_cache.worlds." + world.getName());
    }

    public boolean shouldGenerateChunks(World world) {
        return plugin.getConfig().getBoolean("location_cache.worlds." + world.getName() + ".generate_chunks", false);
    }

    public boolean isZoneSyncEnabled() {
        return plugin.getConfig().getBoolean("proxy.zone_sync.enabled", false);
    }

    public boolean isRedisPubSubEnabled() {
        return plugin.getConfig().getBoolean("redis.performance.enable-pubsub", false);
    }

    public int getZoneSyncInterval() {
        return plugin.getConfig().getInt("proxy.zone_sync.sync_interval", 10);
    }

    public String getZoneSyncMode() {
        return plugin.getConfig().getString("proxy.zone_sync.mode", "PULL");
    }

    public boolean isZoneSyncAutoReload() {
        return plugin.getConfig().getBoolean("proxy.zone_sync.auto_reload", true);
    }

    public boolean isZoneSyncNotifyEnabled() {
        return plugin.getConfig().getBoolean("proxy.zone_sync.notify_sync", true);
    }

    public String getZoneSyncStorage() {
        return plugin.getConfig().getString("proxy.zone_sync.storage", "BOTH");
    }

    public boolean isRedisEnabled() {
        return plugin.getConfig().getBoolean("redis.enabled", false);
    }

    public boolean isSpawnRedirectEnabled() {
        return plugin.getConfig().getBoolean("spawn_world_redirect.enabled", false);
    }

    public String getSpawnWorldName() {
        return plugin.getConfig().getString("spawn_world_redirect.spawn_world", "spawn");
    }

    public String getSpawnRedirectTargetWorld() {
        return plugin.getConfig().getString("spawn_world_redirect.target_world", "world");
    }

    public boolean shouldNotifySpawnRedirect() {
        return plugin.getConfig().getBoolean("spawn_world_redirect.notify_player", true);
    }

    public boolean isJumpRtpEnabled() {
        return plugin.getConfig().getBoolean("jump_rtp.enabled", false);
    }

    public int getJumpRtpCooldown() {
        return plugin.getConfig().getInt("jump_rtp.cooldown", 60);
    }

    public List<String> getJumpRtpEnabledWorlds() {
        return plugin.getConfig().getStringList("jump_rtp.enabled_worlds");
    }

    public int getJumpsRequired() {
        return plugin.getConfig().getInt("jump_rtp.jumps_required", 2);
    }

    public long getJumpTimeWindow() {
        return plugin.getConfig().getLong("jump_rtp.jump_time_window", 500);
    }
}
