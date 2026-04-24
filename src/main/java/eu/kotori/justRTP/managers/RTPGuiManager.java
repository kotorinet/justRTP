package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.FormatUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public class RTPGuiManager
implements Listener {
    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, String> activeGuis = new ConcurrentHashMap<UUID, String>();
    private final Map<UUID, Long> guiCooldowns = new ConcurrentHashMap<UUID, Long>();

    public RTPGuiManager(JustRTP plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents((Listener)this, (Plugin)plugin);
    }

    public boolean isGuiEnabled() {
        return this.plugin.getConfig().getBoolean("rtp_gui.enabled", false);
    }

    public void openMainGui(Player player) {
        ConfigurationSection closeButtonSection;
        ConfigurationSection decorationSection;
        long now;
        if (!this.isGuiEnabled()) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "gui.feature_disabled", new TagResolver[0]);
            return;
        }
        if (!player.hasPermission("justrtp.command.rtp.gui")) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "command.no_permission", new TagResolver[0]);
            return;
        }
        long cooldown = this.guiCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (cooldown > (now = System.currentTimeMillis())) {
            long remaining = (cooldown - now) / 1000L;
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "gui.cooldown", new TagResolver[]{Placeholder.unparsed((String)"time", (String)String.valueOf(remaining))});
            return;
        }
        ConfigurationSection guiConfig = this.plugin.getConfig().getConfigurationSection("rtp_gui");
        if (guiConfig == null) {
            this.plugin.getRTPLogger().warn("GUI", "RTP GUI configuration section not found!");
            return;
        }
        String title = guiConfig.getString("title", "<gradient:#20B2AA:#7FFFD4>Random Teleport</gradient>");
        int size = guiConfig.getInt("size", 54);
        size = Math.min(54, Math.max(9, size / 9 * 9));
        Inventory gui = Bukkit.createInventory(null, (int)size, (Component)this.mm.deserialize(title));
        ConfigurationSection worldsSection = guiConfig.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (Object worldKey : worldsSection.getKeys(false)) {
                int slot;
                String worldName;
                World world;
                ConfigurationSection worldConfig = worldsSection.getConfigurationSection((String)worldKey);
                if (worldConfig == null || (world = Bukkit.getWorld((String)(worldName = worldConfig.getString("world_name", (String)worldKey)))) == null || !this.plugin.getRtpService().isRtpEnabled(world) || !player.hasPermission("justrtp.command.rtp.world") && !player.hasPermission("justrtp.command.rtp.world." + worldName) || (slot = worldConfig.getInt("slot", -1)) < 0 || slot >= size) continue;
                ItemStack item = this.createWorldItem(player, world, worldConfig);
                gui.setItem(slot, item);
            }
        }
        if ((decorationSection = guiConfig.getConfigurationSection("decoration")) != null) {
            for (String key : decorationSection.getKeys(false)) {
                ConfigurationSection itemConfig = decorationSection.getConfigurationSection(key);
                if (itemConfig == null) continue;
                List slots = itemConfig.getIntegerList("slots");
                ItemStack decorItem = this.createDecorationItem(itemConfig);
                Iterator iterator = slots.iterator();
                while (iterator.hasNext()) {
                    int slot = (Integer)iterator.next();
                    if (slot < 0 || slot >= size) continue;
                    gui.setItem(slot, decorItem);
                }
            }
        }
        if ((closeButtonSection = guiConfig.getConfigurationSection("close_button")) != null && closeButtonSection.getBoolean("enabled", true)) {
            int slot = closeButtonSection.getInt("slot", size - 1);
            ItemStack closeItem = this.createCloseButton(closeButtonSection);
            gui.setItem(slot, closeItem);
        }
        this.activeGuis.put(player.getUniqueId(), "main");
        this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
            player.openInventory(gui);
            this.plugin.getRTPLogger().debug("GUI", "Opened RTP GUI for player: " + player.getName());
        });
        int cooldownSeconds = guiConfig.getInt("open_cooldown", 1);
        if (cooldownSeconds > 0) {
            this.guiCooldowns.put(player.getUniqueId(), now + (long)cooldownSeconds * 1000L);
        }
    }

    private ItemStack createWorldItem(Player player, World world, ConfigurationSection config) {
        ItemStack item;
        ItemMeta meta;
        String materialName = config.getString("material", "GRASS_BLOCK");
        Material material = Material.matchMaterial((String)materialName);
        if (material == null) {
            material = Material.GRASS_BLOCK;
        }
        if ((meta = (item = new ItemStack(material)).getItemMeta()) == null) {
            return item;
        }
        String safeWorldName = world.getName().replaceAll("[<>]", "");
        String displayName = config.getString("display_name", "<green>" + safeWorldName);
        Component nameComponent = this.mm.deserialize(displayName, (TagResolver)Placeholder.unparsed((String)"world", (String)safeWorldName)).decoration(TextDecoration.ITALIC, false);
        meta.displayName(nameComponent);
        List<String> loreTemplate = config.getStringList("lore");
        ArrayList<Component> lore = new ArrayList<Component>();
        long cooldownRemaining = this.plugin.getCooldownManager().getRemaining(player.getUniqueId(), world.getName());
        String cooldownStatus = cooldownRemaining > 0L ? this.plugin.getLocaleManager().getRawMessage("gui.cooldown_active", "<red>Cooldown: <time>s").replace("<time>", String.valueOf(cooldownRemaining / 1000L)) : this.plugin.getLocaleManager().getRawMessage("gui.cooldown_ready", "<green>Ready!");
        double cost = this.plugin.getConfigManager().getEconomyCost(player, world);
        String costStr = cost > 0.0 ? this.plugin.getLocaleManager().getRawMessage("gui.cost_display", "<gold>Cost: $<cost>").replace("<cost>", FormatUtils.formatCost(cost)) : this.plugin.getLocaleManager().getRawMessage("gui.cost_free", "<green>Free!");
        ConfigurationSection worldSettings = this.plugin.getConfig().getConfigurationSection("custom_worlds." + world.getName());
        int maxRadius = worldSettings != null ? worldSettings.getInt("max_radius", 5000) : 5000;
        int minRadius = worldSettings != null ? worldSettings.getInt("min_radius", 100) : 100;
        for (String line : loreTemplate) {
            String processed = line.replace("<world>", safeWorldName).replace("<cooldown>", cooldownStatus).replace("<cost>", costStr).replace("<max_radius>", String.valueOf(maxRadius)).replace("<min_radius>", String.valueOf(minRadius));
            lore.add(this.mm.deserialize(processed).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (config.getBoolean("enchanted", false)) {
            meta.setEnchantmentGlintOverride(Boolean.valueOf(true));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDecorationItem(ConfigurationSection config) {
        ItemStack item;
        ItemMeta meta;
        String materialName = config.getString("material", "GRAY_STAINED_GLASS_PANE");
        Material material = Material.matchMaterial((String)materialName);
        if (material == null) {
            material = Material.GRAY_STAINED_GLASS_PANE;
        }
        if ((meta = (item = new ItemStack(material)).getItemMeta()) == null) {
            return item;
        }
        String displayName = config.getString("display_name", " ");
        meta.displayName(this.mm.deserialize(displayName).decoration(TextDecoration.ITALIC, false));
        List<String> loreTemplate = config.getStringList("lore");
        if (!loreTemplate.isEmpty()) {
            ArrayList<Component> lore = new ArrayList<Component>();
            for (String line : loreTemplate) {
                lore.add(this.mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseButton(ConfigurationSection config) {
        ItemStack item;
        ItemMeta meta;
        String materialName = config.getString("material", "BARRIER");
        Material material = Material.matchMaterial((String)materialName);
        if (material == null) {
            material = Material.BARRIER;
        }
        if ((meta = (item = new ItemStack(material)).getItemMeta()) == null) {
            return item;
        }
        String displayName = config.getString("display_name", "<red>Close");
        meta.displayName(this.mm.deserialize(displayName).decoration(TextDecoration.ITALIC, false));
        List<String> loreTemplate = config.getStringList("lore");
        if (!loreTemplate.isEmpty()) {
            ArrayList<Component> lore = new ArrayList<Component>();
            for (String line : loreTemplate) {
                lore.add(this.mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        ConfigurationSection worldsSection;
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player player = (Player)humanEntity;
        if (!this.activeGuis.containsKey(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.CHEST) {
            event.setCancelled(true);
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (event.getHotbarButton() >= 0) {
            event.setCancelled(true);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        ConfigurationSection guiConfig = this.plugin.getConfig().getConfigurationSection("rtp_gui");
        if (guiConfig == null) {
            return;
        }
        ConfigurationSection closeButtonSection = guiConfig.getConfigurationSection("close_button");
        if (closeButtonSection != null && closeButtonSection.getBoolean("enabled", true)) {
            int closeSlot = closeButtonSection.getInt("slot", event.getInventory().getSize() - 1);
            if (event.getSlot() == closeSlot) {
                player.closeInventory();
                return;
            }
        }
        if ((worldsSection = guiConfig.getConfigurationSection("worlds")) == null) {
            return;
        }
        for (String worldKey : worldsSection.getKeys(false)) {
            int slot;
            ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldKey);
            if (worldConfig == null || (slot = worldConfig.getInt("slot", -1)) != event.getSlot()) continue;
            String worldName = worldConfig.getString("world_name", worldKey);
            World world = Bukkit.getWorld((String)worldName);
            if (world == null) {
                this.plugin.getLocaleManager().sendMessage((CommandSender)player, "command.world_not_found", new TagResolver[]{Placeholder.unparsed((String)"world", (String)worldName)});
                player.closeInventory();
                return;
            }
            player.closeInventory();
            this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
                this.plugin.getRTPLogger().debug("GUI", "Player " + player.getName() + " selected world: " + worldName);
                this.plugin.getServer().dispatchCommand((CommandSender)player, "rtp " + worldName);
            });
            return;
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player player = (Player)humanEntity;

        if (this.activeGuis.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.getRTPLogger().debug("GUI-SECURITY",
                "Blocked drag event for " + player.getName() + " in RTP GUI");
            return;
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity humanEntity = event.getPlayer();
        if (humanEntity instanceof Player) {
            Player player = (Player)humanEntity;
            this.activeGuis.remove(player.getUniqueId());
        }
    }

    public void reload() {
        this.activeGuis.clear();
        this.guiCooldowns.clear();
        this.plugin.getRTPLogger().info("GUI", "RTP GUI Manager reloaded");
    }

    public void cleanup() {
        for (UUID uuid : this.activeGuis.keySet()) {
            Player player = Bukkit.getPlayer((UUID)uuid);
            if (player == null || !player.isOnline()) continue;
            player.closeInventory();
        }
        this.activeGuis.clear();
        this.guiCooldowns.clear();
    }
}
