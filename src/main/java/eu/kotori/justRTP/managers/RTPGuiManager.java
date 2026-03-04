package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RTPGuiManager implements Listener {
    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, String> activeGuis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> guiCooldowns = new ConcurrentHashMap<>();

    public RTPGuiManager(JustRTP plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isGuiEnabled() {
        return plugin.getConfig().getBoolean("rtp_gui.enabled", false);
    }

    public void openMainGui(Player player) {
        if (!isGuiEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "gui.feature_disabled");
            return;
        }

        if (!player.hasPermission("justrtp.command.rtp.gui")) {
            plugin.getLocaleManager().sendMessage(player, "command.no_permission");
            return;
        }

        long cooldown = guiCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long now = System.currentTimeMillis();
        if (cooldown > now) {
            long remaining = (cooldown - now) / 1000;
            plugin.getLocaleManager().sendMessage(player, "gui.cooldown",
                    Placeholder.unparsed("time", String.valueOf(remaining)));
            return;
        }

        ConfigurationSection guiConfig = plugin.getConfig().getConfigurationSection("rtp_gui");
        if (guiConfig == null) {
            plugin.getRTPLogger().warn("GUI", "RTP GUI configuration section not found!");
            return;
        }

        String title = guiConfig.getString("title", "<gradient:#20B2AA:#7FFFD4>Random Teleport</gradient>");
        int size = guiConfig.getInt("size", 54);
        size = Math.min(54, Math.max(9, (size / 9) * 9));

        Inventory gui = Bukkit.createInventory(null, size, mm.deserialize(title));

        ConfigurationSection worldsSection = guiConfig.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String worldKey : worldsSection.getKeys(false)) {
                ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldKey);
                if (worldConfig == null) continue;

                String worldName = worldConfig.getString("world_name", worldKey);
                World world = Bukkit.getWorld(worldName);
                
                if (world == null || !plugin.getRtpService().isRtpEnabled(world)) {
                    continue;
                }

                if (!player.hasPermission("justrtp.command.rtp.world") && 
                    !player.hasPermission("justrtp.command.rtp.world." + worldName)) {
                    continue;
                }

                int slot = worldConfig.getInt("slot", -1);
                if (slot < 0 || slot >= size) continue;

                ItemStack item = createWorldItem(player, world, worldConfig);
                gui.setItem(slot, item);
            }
        }

        ConfigurationSection decorationSection = guiConfig.getConfigurationSection("decoration");
        if (decorationSection != null) {
            for (String key : decorationSection.getKeys(false)) {
                ConfigurationSection itemConfig = decorationSection.getConfigurationSection(key);
                if (itemConfig == null) continue;

                List<Integer> slots = itemConfig.getIntegerList("slots");
                ItemStack decorItem = createDecorationItem(itemConfig);

                for (int slot : slots) {
                    if (slot >= 0 && slot < size) {
                        gui.setItem(slot, decorItem);
                    }
                }
            }
        }

        ConfigurationSection closeButtonSection = guiConfig.getConfigurationSection("close_button");
        if (closeButtonSection != null && closeButtonSection.getBoolean("enabled", true)) {
            int slot = closeButtonSection.getInt("slot", size - 1);
            ItemStack closeItem = createCloseButton(closeButtonSection);
            gui.setItem(slot, closeItem);
        }

        activeGuis.put(player.getUniqueId(), "main");
        
        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            player.openInventory(gui);
            plugin.getRTPLogger().debug("GUI", "Opened RTP GUI for player: " + player.getName());
        });

        int cooldownSeconds = guiConfig.getInt("open_cooldown", 1);
        if (cooldownSeconds > 0) {
            guiCooldowns.put(player.getUniqueId(), now + (cooldownSeconds * 1000L));
        }
    }

    private ItemStack createWorldItem(Player player, World world, ConfigurationSection config) {
        String materialName = config.getString("material", "GRASS_BLOCK");
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.GRASS_BLOCK;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String safeWorldName = world.getName().replaceAll("[<>]", "");
        
        String displayName = config.getString("display_name", "<green>" + safeWorldName);
        Component nameComponent = mm.deserialize(displayName,
                Placeholder.unparsed("world", safeWorldName))
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(nameComponent);

        List<String> loreTemplate = config.getStringList("lore");
        List<Component> lore = new ArrayList<>();

        long cooldownRemaining = plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName());
        String cooldownStatus = cooldownRemaining > 0 
                ? plugin.getLocaleManager().getRawMessage("gui.cooldown_active", "<red>Cooldown: <time>s")
                        .replace("<time>", String.valueOf(cooldownRemaining / 1000))
                : plugin.getLocaleManager().getRawMessage("gui.cooldown_ready", "<green>Ready!");

        double cost = plugin.getConfigManager().getEconomyCost(player, world);
        String costStr = cost > 0 
                ? plugin.getLocaleManager().getRawMessage("gui.cost_display", "<gold>Cost: $<cost>")
                        .replace("<cost>", String.format("%.2f", cost))
                : plugin.getLocaleManager().getRawMessage("gui.cost_free", "<green>Free!");

        ConfigurationSection worldSettings = plugin.getConfig().getConfigurationSection("custom_worlds." + world.getName());
        int maxRadius = worldSettings != null ? worldSettings.getInt("max_radius", 5000) : 5000;
        int minRadius = worldSettings != null ? worldSettings.getInt("min_radius", 100) : 100;
        
        String serverName = config.getString("server_name", "");
        String serverDisplay = serverName.isEmpty() ? "<green>Local</green>" : "<aqua>" + serverName + "</aqua>";

        for (String line : loreTemplate) {
            String processed = line
                    .replace("<world>", safeWorldName)
                    .replace("<server>", serverDisplay)
                    .replace("<cooldown>", cooldownStatus)
                    .replace("<cost>", costStr)
                    .replace("<max_radius>", String.valueOf(maxRadius))
                    .replace("<min_radius>", String.valueOf(minRadius));
            lore.add(mm.deserialize(processed).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);

        if (config.getBoolean("enchanted", false)) {
            meta.setEnchantmentGlintOverride(true);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDecorationItem(ConfigurationSection config) {
        String materialName = config.getString("material", "GRAY_STAINED_GLASS_PANE");
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.GRAY_STAINED_GLASS_PANE;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String displayName = config.getString("display_name", " ");
        meta.displayName(mm.deserialize(displayName).decoration(TextDecoration.ITALIC, false));

        List<String> loreTemplate = config.getStringList("lore");
        if (!loreTemplate.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreTemplate) {
                lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseButton(ConfigurationSection config) {
        String materialName = config.getString("material", "BARRIER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.BARRIER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String displayName = config.getString("display_name", "<red>Close");
        meta.displayName(mm.deserialize(displayName).decoration(TextDecoration.ITALIC, false));

        List<String> loreTemplate = config.getStringList("lore");
        if (!loreTemplate.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreTemplate) {
                lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!activeGuis.containsKey(player.getUniqueId())) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ConfigurationSection guiConfig = plugin.getConfig().getConfigurationSection("rtp_gui");
        if (guiConfig == null) return;

        ConfigurationSection closeButtonSection = guiConfig.getConfigurationSection("close_button");
        if (closeButtonSection != null && closeButtonSection.getBoolean("enabled", true)) {
            int closeSlot = closeButtonSection.getInt("slot", event.getInventory().getSize() - 1);
            if (event.getSlot() == closeSlot) {
                player.closeInventory();
                return;
            }
        }

        ConfigurationSection worldsSection = guiConfig.getConfigurationSection("worlds");
        if (worldsSection == null) return;

        for (String worldKey : worldsSection.getKeys(false)) {
            ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldKey);
            if (worldConfig == null) continue;

            int slot = worldConfig.getInt("slot", -1);
            if (slot != event.getSlot()) continue;

            String worldName = worldConfig.getString("world_name", worldKey);
            String serverName = worldConfig.getString("server_name", null);
            
            if (serverName != null && !serverName.isEmpty()) {
                player.closeInventory();
                
                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                    plugin.getRTPLogger().debug("GUI", "Player " + player.getName() + " selected cross-server: " + serverName + ":" + worldName);
                    plugin.getServer().dispatchCommand(player, "rtp " + serverName + ":" + worldName);
                });
                
                return;
            }
            
            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                plugin.getLocaleManager().sendMessage(player, "command.world_not_found",
                        Placeholder.unparsed("world", worldName));
                player.closeInventory();
                return;
            }

            player.closeInventory();

            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                plugin.getRTPLogger().debug("GUI", "Player " + player.getName() + " selected world: " + worldName);
                plugin.getServer().dispatchCommand(player, "rtp " + worldName);
            });

            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            activeGuis.remove(player.getUniqueId());
        }
    }

    public void reload() {
        activeGuis.clear();
        guiCooldowns.clear();
        plugin.getRTPLogger().info("GUI", "RTP GUI Manager reloaded");
    }

    public void cleanup() {
        for (UUID uuid : activeGuis.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }
        activeGuis.clear();
        guiCooldowns.clear();
    }
}
