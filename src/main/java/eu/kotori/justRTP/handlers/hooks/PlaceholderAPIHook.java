package eu.kotori.justRTP.handlers.hooks;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.FormatUtils;
import eu.kotori.justRTP.utils.TimeUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlaceholderAPIHook extends PlaceholderExpansion {

    private final JustRTP plugin;

    public PlaceholderAPIHook(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "justrtp";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        if (plugin.getCooldownManager() == null || plugin.getConfigManager() == null) {
            return "";
        }

        try {
            String lowerParams = params.toLowerCase();

            if (lowerParams.startsWith("zone_time_")) {
                if (plugin.getRtpZoneManager() == null) return "N/A";
                String zoneId = params.substring("zone_time_".length());
                int countdown = plugin.getRtpZoneManager().getZoneCountdown(zoneId);
                return countdown >= 0 ? String.valueOf(countdown) : "N/A";
            }

            World targetWorld = null;
            String basePlaceholder = lowerParams;

            if (lowerParams.contains("_")) {
                String[] parts = lowerParams.split("_", 2);
                if (parts.length == 2) {
                    String possibleWorldName = parts[1];
                    World world = Bukkit.getWorld(possibleWorldName);
                    if (world != null) {
                        targetWorld = world;
                        basePlaceholder = parts[0];
                    }
                }
            }

            if (targetWorld == null) {
                targetWorld = player.getWorld();
            }

            final World world = targetWorld;

            switch (basePlaceholder) {
                case "cooldown":
                    if (plugin.getCooldownManager() == null) return "0s";
                    return TimeUtils.formatDuration(plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName()));

                case "cooldown_raw":
                    if (plugin.getCooldownManager() == null) return "0";
                    return String.valueOf(plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName()));

                case "cooldown_total":
                    if (plugin.getConfigManager() == null) return "0";
                    return String.valueOf(plugin.getConfigManager().getCooldown(player, world));

                case "cost":
                    if (plugin.getConfigManager() == null) return "0";
                    return FormatUtils.formatCost(plugin.getConfigManager().getEconomyCost(player, world));

                case "delay":
                    if (plugin.getConfigManager() == null) return "0";
                    return String.valueOf(plugin.getConfigManager().getDelay(player, world));

                case "world_min_radius":
                case "min_radius":
                    return String.valueOf(getWorldMinRadius(player, world));

                case "world_max_radius":
                case "max_radius":
                    return String.valueOf(getWorldMaxRadius(player, world));

                case "has_cooldown":
                    if (plugin.getCooldownManager() == null) return "false";
                    return String.valueOf(plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName()) > 0);

                case "is_delayed":
                    if (plugin.getDelayManager() == null) return "false";
                    return String.valueOf(plugin.getDelayManager().isDelayed(player.getUniqueId()));

                case "world_name":
                case "world":
                    return world.getName();

                case "current_world":
                    return player.getWorld().getName();

                case "permission_group":
                case "group":
                    return getPlayerPermissionGroup(player);

                case "can_rtp":
                    boolean hasPermission = player.hasPermission("justrtp.command.rtp");
                    boolean noCooldown = plugin.getCooldownManager() == null ||
                                        plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName()) <= 0;
                    boolean notDelayed = plugin.getDelayManager() == null ||
                                        !plugin.getDelayManager().isDelayed(player.getUniqueId());
                    return String.valueOf(hasPermission && noCooldown && notDelayed);

                case "cooldown_formatted":
                    if (plugin.getCooldownManager() == null) return "Ready";
                    long remaining = plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName());
                    if (remaining <= 0) return "Ready";
                    return TimeUtils.formatDuration(remaining);

                case "in_zone":
                    if (plugin.getRtpZoneManager() == null) return "false";
                    return String.valueOf(plugin.getRtpZoneManager().getPlayerZone(player) != null);

                case "current_zone":
                case "zone":
                    if (plugin.getRtpZoneManager() == null) return "None";
                    String currentZone = plugin.getRtpZoneManager().getPlayerZone(player);
                    return currentZone != null ? currentZone : "None";

                case "zone_name":
                    if (plugin.getRtpZoneManager() == null) return "None";
                    String zoneName = plugin.getRtpZoneManager().getPlayerZone(player);
                    return zoneName != null ? zoneName : "None";

                default:
                    return null;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing placeholder %" + getIdentifier() + "_" + params +
                                      "% for player " + player.getName() + ": " + e.getMessage());
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            return "Error";
        }
    }

    private int getWorldMinRadius(Player player, World world) {
        String worldName = world.getName();
        if (plugin.getConfigManager() != null) {
            int groupValue = plugin.getConfigManager().getInt(player, world, "min_radius", -1);
            if (groupValue != -1) {
                return groupValue;
            }
        }
        return plugin.getConfig().getInt("custom_worlds." + worldName + ".min_radius",
               plugin.getConfig().getInt("settings.min_radius", 100));
    }

    private int getWorldMaxRadius(Player player, World world) {
        String worldName = world.getName();
        if (plugin.getConfigManager() != null) {
            int groupValue = plugin.getConfigManager().getInt(player, world, "max_radius", -1);
            if (groupValue != -1) {
                return groupValue;
            }
        }
        return plugin.getConfig().getInt("custom_worlds." + worldName + ".max_radius",
               plugin.getConfig().getInt("settings.max_radius", 5000));
    }

    private String getPlayerPermissionGroup(Player player) {
        if (!plugin.getConfig().getBoolean("permission_groups.enabled", false)) {
            return "default";
        }

        var groupsSection = plugin.getConfig().getConfigurationSection("permission_groups.groups");
        if (groupsSection == null) {
            return "default";
        }

        return groupsSection.getKeys(false).stream()
                .filter(groupName -> player.hasPermission("justrtp.group." + groupName))
                .min((g1, g2) -> Integer.compare(
                    groupsSection.getInt(g1 + ".priority", Integer.MAX_VALUE),
                    groupsSection.getInt(g2 + ".priority", Integer.MAX_VALUE)
                ))
                .orElse("default");
    }
}