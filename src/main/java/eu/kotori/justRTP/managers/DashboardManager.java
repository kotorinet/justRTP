package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DashboardManager implements Listener {

    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile FileConfiguration config;

    private final Map<UUID, PendingEdit> pendingEdits = new ConcurrentHashMap<>();

    public DashboardManager(JustRTP plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        load();
    }

    private enum ReloadType { LIVE, RESTART }

    private enum SettingType { INT, LONG, DOUBLE, BOOLEAN, STRING }

    private record Setting(String label, String path, SettingType type) {}

    private static final class Module {
        final String id;
        final String label;
        final String togglePath;
        final ReloadType reload;
        final List<Setting> settings;

        Module(String id, String label, String togglePath, ReloadType reload, List<Setting> settings) {
            this.id = id;
            this.label = label;
            this.togglePath = togglePath;
            this.reload = reload;
            this.settings = settings;
        }

        boolean hasToggle() {
            return togglePath != null;
        }
    }

    private static Setting s(String label, String path, SettingType type) {
        return new Setting(label, path, type);
    }

    private static final Map<String, Module> MODULES = new LinkedHashMap<>();

    private static void register(String id, String label, String togglePath, ReloadType reload, Setting... settings) {
        MODULES.put(id, new Module(id, label, togglePath, reload, List.of(settings)));
    }

    static {
        register("location_cache", "Location Cache", "location_cache.enabled", ReloadType.LIVE,
                s("Cache Size", "location_cache.cache_size", SettingType.INT),
                s("Refill Interval (s)", "location_cache.refill_interval_seconds", SettingType.INT),
                s("Max Parallel World Fills", "location_cache.max_parallel_world_fills", SettingType.INT));
        register("teleport_queue", "Teleport Queue", "performance.use_teleport_queue", ReloadType.LIVE,
                s("Queue Processing Rate", "performance.queue_processing_rate", SettingType.INT),
                s("Queue Batch Size", "performance.queue_batch_size", SettingType.INT));
        register("smooth_transition", "Smooth Transition", "smooth_transition.enabled", ReloadType.LIVE,
                s("Chunk Preload Radius", "smooth_transition.chunk_preload_radius", SettingType.INT),
                s("Blindness Ticks", "smooth_transition.blindness_ticks", SettingType.INT));
        register("rtp_gui", "RTP GUI", "rtp_gui.enabled", ReloadType.LIVE,
                s("Auto-Open On /rtp", "rtp_gui.auto_open_gui", SettingType.BOOLEAN),
                s("GUI Size", "rtp_gui.size", SettingType.INT),
                s("Open Cooldown (s)", "rtp_gui.open_cooldown", SettingType.INT));
        register("spectator_switch", "Spectator Switch", "spectator_switch.enabled", ReloadType.LIVE,
                s("Display Radius", "spectator_switch.display_radius", SettingType.DOUBLE),
                s("Open Cooldown (s)", "spectator_switch.open_cooldown", SettingType.INT),
                s("Auto-Close (s)", "spectator_switch.auto_close_seconds", SettingType.INT));
        register("jump_rtp", "Jump RTP", "jump_rtp.enabled", ReloadType.LIVE,
                s("Cooldown (s)", "jump_rtp.cooldown", SettingType.INT),
                s("Jumps Required", "jump_rtp.jumps_required", SettingType.INT),
                s("Jump Time Window (ms)", "jump_rtp.jump_time_window", SettingType.INT));
        register("nearplayer", "Near Player RTP", "nearplayer.enabled", ReloadType.LIVE,
                s("Min Radius", "nearplayer.min_radius", SettingType.INT),
                s("Max Radius", "nearplayer.max_radius", SettingType.INT),
                s("Silent", "nearplayer.silent", SettingType.BOOLEAN),
                s("Allow Self", "nearplayer.allow_self", SettingType.BOOLEAN));
        register("near_claim_rtp", "Near Claim RTP", "near_claim_rtp.enabled", ReloadType.LIVE,
                s("Min Distance", "near_claim_rtp.min_distance", SettingType.INT),
                s("Max Distance", "near_claim_rtp.max_distance", SettingType.INT),
                s("Cooldown (s)", "near_claim_rtp.cooldown", SettingType.INT),
                s("Cost", "near_claim_rtp.cost", SettingType.DOUBLE));
        register("matchmaking", "Matchmaking Queue", "matchmaking.enabled", ReloadType.LIVE,
                s("Team Size", "matchmaking.team_size", SettingType.INT),
                s("Spread Distance", "matchmaking.spread_distance", SettingType.INT),
                s("Tick Interval (s)", "matchmaking.tick_interval", SettingType.INT),
                s("Queue Timeout (s)", "matchmaking.queue_timeout", SettingType.INT));
        register("first_join_rtp", "First Join RTP", "first_join_rtp.enabled", ReloadType.LIVE,
                s("Target World", "first_join_rtp.target_world", SettingType.STRING));
        register("respawn_rtp", "Respawn RTP", "respawn_rtp.enabled", ReloadType.LIVE,
                s("Bypass Delay", "respawn_rtp.bypass_delay", SettingType.BOOLEAN));
        register("spawn_world_redirect", "Spawn Redirect", "spawn_world_redirect.enabled", ReloadType.LIVE,
                s("Spawn World", "spawn_world_redirect.spawn_world", SettingType.STRING),
                s("Target World", "spawn_world_redirect.target_world", SettingType.STRING),
                s("Notify Player", "spawn_world_redirect.notify_player", SettingType.BOOLEAN));
        register("zone_title", "Zone Countdown Title", "zone_title.enabled", ReloadType.LIVE,
                s("Stay (ticks)", "zone_title.stay", SettingType.INT),
                s("Fade In (ticks)", "zone_title.fade_in", SettingType.INT),
                s("Fade Out (ticks)", "zone_title.fade_out", SettingType.INT));
        register("rtpzone_command", "RTP Zone Command", "rtpzone_command.enabled", ReloadType.LIVE);
        register("economy", "Economy", "economy.enabled", ReloadType.LIVE,
                s("Default Cost", "economy.cost", SettingType.DOUBLE),
                s("Require Confirmation", "economy.require_confirmation", SettingType.BOOLEAN),
                s("Refund On Fail", "economy.refund_on_fail", SettingType.BOOLEAN));
        register("proxy", "Cross-Server Proxy", "proxy.enabled", ReloadType.RESTART,
                s("This Server Name", "proxy.this_server_name", SettingType.STRING),
                s("Timeout (s)", "proxy.timeout_seconds", SettingType.INT),
                s("No Delay On Arrival", "proxy.cross_server_rtp_no_delay", SettingType.BOOLEAN));
        register("redis", "Redis", "redis.enabled", ReloadType.RESTART);
        register("update_checker", "Update Checker", "settings.check_for_updates", ReloadType.RESTART,
                s("Notify OPs On Join", "settings.notify_ops_on_update", SettingType.BOOLEAN));
        register("debug", "Debug Logging", "settings.debug", ReloadType.LIVE);
        register("general", "General", null, ReloadType.LIVE,
                s("Cooldown (s)", "settings.cooldown", SettingType.INT),
                s("Delay (s)", "settings.delay", SettingType.INT),
                s("Safety Attempts", "settings.attempts", SettingType.INT),
                s("Respect Region Claims", "settings.respect_regions", SettingType.BOOLEAN));
    }

    public record ModuleStatus(String label, boolean toggleable, boolean enabled, boolean restart) {}

    public static List<ModuleStatus> moduleStatuses(JustRTP plugin) {
        List<ModuleStatus> out = new ArrayList<>();
        for (Module m : MODULES.values()) {
            boolean enabled = m.hasToggle() && plugin.getConfig().getBoolean(m.togglePath, false);
            out.add(new ModuleStatus(m.label, m.hasToggle(), enabled, m.reload == ReloadType.RESTART));
        }
        return out;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "dashboard.yml");
        if (!file.exists()) {
            plugin.saveResource("dashboard.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void reloadConfig() {
        load();
    }

    public void cleanup() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof DashboardHolder) {
                player.closeInventory();
            }
        }
        pendingEdits.clear();
    }

    public boolean isEnabled() {
        return config != null && config.getBoolean("enabled", true);
    }

    public void open(Player player) {
        if (!player.hasPermission("justrtp.command.dashboard") && !player.hasPermission("justrtp.admin")) {
            send(player, "messages.no-permission", "<red>You do not have permission to use the dashboard.");
            return;
        }
        if (!isEnabled()) {
            send(player, "messages.disabled", "<red>The dashboard is disabled in dashboard.yml.");
            return;
        }
        openMain(player);
        playSound(player, "settings.sounds.open");
    }

    private void openMain(Player player) {
        int rows = clampRows(config.getInt("main-menu.rows", 6));
        String title = config.getString("main-menu.title", "<gradient:#20B2AA:#7FFFD4>JustRTP Dashboard</gradient>");

        int closeSlot = config.getBoolean("main-menu.close.enabled", true)
                ? config.getInt("main-menu.close.slot", 49) : -1;

        Map<Integer, String> moduleSlots = new LinkedHashMap<>();
        DashboardHolder holder = new DashboardHolder(ViewType.MAIN, null, moduleSlots,
                Map.of(), -1, -1, closeSlot);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, mm.deserialize(title));
        holder.setInventory(inv);

        fill(inv);

        ConfigurationSection modulesSection = config.getConfigurationSection("main-menu.modules");
        int enabledCount = 0;
        if (modulesSection != null) {
            for (String id : modulesSection.getKeys(false)) {
                Module module = MODULES.get(id);
                if (module == null) continue;
                ConfigurationSection ms = modulesSection.getConfigurationSection(id);
                if (ms == null) continue;
                int slot = ms.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;

                if (module.hasToggle() && plugin.getConfig().getBoolean(module.togglePath, false)) {
                    enabledCount++;
                }
                inv.setItem(slot, buildModuleItem(module, ms));
                moduleSlots.put(slot, id);
            }
        }

        placeInfo(inv, moduleSlots.size(), enabledCount);
        if (closeSlot >= 0 && closeSlot < inv.getSize()) {
            inv.setItem(closeSlot, buildSimpleItem("main-menu.close", "BARRIER", "<red>Close"));
        }

        showInventory(player, inv);
    }

    private void openSettings(Player player, String moduleId) {
        Module module = MODULES.get(moduleId);
        if (module == null) {
            openMain(player);
            return;
        }

        int rows = clampRows(config.getInt("settings-menu.rows", 5));
        String moduleName = moduleDisplayName(moduleId);
        String title = config.getString("settings-menu.title", "<gradient:#20B2AA:#7FFFD4><module_name></gradient>")
                .replace("<module_name>", moduleName);

        int toggleSlot = config.getInt("settings-menu.toggle.slot", 4);
        int backSlot = config.getInt("settings-menu.back.slot", 40);

        Map<Integer, Integer> settingSlots = new LinkedHashMap<>();
        DashboardHolder holder = new DashboardHolder(ViewType.SETTINGS, moduleId, Map.of(),
                settingSlots, toggleSlot, backSlot, -1);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, mm.deserialize(title));
        holder.setInventory(inv);

        fill(inv);

        if (toggleSlot >= 0 && toggleSlot < inv.getSize()) {
            inv.setItem(toggleSlot, module.hasToggle() ? buildToggleItem(module) : buildHeaderItem(module));
        }

        int count = module.settings.size();
        int row = Math.max(1, Math.min(config.getInt("settings-menu.row", 2), rows - 2));
        int base = row * 9 + Math.max(0, (9 - count) / 2);
        for (int i = 0; i < count; i++) {
            int slot = base + i;
            if (slot < 0 || slot >= inv.getSize()) break;
            inv.setItem(slot, buildSettingItem(module.settings.get(i)));
            settingSlots.put(slot, i);
        }

        if (count == 0) {
            int emptySlot = config.getInt("settings-menu.empty.slot", 22);
            if (emptySlot >= 0 && emptySlot < inv.getSize()) {
                inv.setItem(emptySlot, buildSimpleItem("settings-menu.empty", "BARRIER", "<gray>No editable settings"));
            }
        }

        if (backSlot >= 0 && backSlot < inv.getSize()) {
            inv.setItem(backSlot, buildSimpleItem("settings-menu.back", "ARROW", "<gray>« Back"));
        }

        showInventory(player, inv);
    }

    private void showInventory(Player player, Inventory inv) {
        plugin.getFoliaScheduler().runAtEntity(player, () -> player.openInventory(inv));
    }

    private ItemStack buildModuleItem(Module module, ConfigurationSection ms) {
        String material = ms.getString("material", "PAPER");
        String name = ms.getString("name", "<white>" + module.id);
        String description = ms.getString("description", "");

        boolean on = module.hasToggle() && plugin.getConfig().getBoolean(module.togglePath, false);
        String state = module.hasToggle()
                ? (on ? config.getString("main-menu.state-on", "<green>● Enabled")
                        : config.getString("main-menu.state-off", "<red>● Disabled"))
                : "<gray>● Settings only";
        String reloadNote = reloadNote(module);

        List<String> lore = new ArrayList<>();
        if (!description.isEmpty()) {
            lore.add(description);
        }
        lore.addAll(config.getStringList("main-menu.module-lore"));

        ItemStack item = baseItem(material, "PAPER", name);
        applyLore(item, lore, state, reloadNote, module.hasToggle());
        if (on) {
            glow(item);
        }
        return item;
    }

    private ItemStack buildToggleItem(Module module) {
        boolean on = plugin.getConfig().getBoolean(module.togglePath, false);
        String material = on
                ? config.getString("settings-menu.toggle.material-on", "LIME_DYE")
                : config.getString("settings-menu.toggle.material-off", "GRAY_DYE");
        String name = config.getString("settings-menu.toggle.name", "<white>Module: <state>");
        String state = on ? config.getString("main-menu.state-on", "<green>● Enabled")
                : config.getString("main-menu.state-off", "<red>● Disabled");

        ItemStack item = baseItem(material, "GRAY_DYE", name.replace("<state>", state));
        applyLore(item, config.getStringList("settings-menu.toggle.lore"), state, reloadNote(module), true);
        if (on) {
            glow(item);
        }
        return item;
    }

    private ItemStack buildHeaderItem(Module module) {
        ConfigurationSection ms = config.getConfigurationSection("main-menu.modules." + module.id);
        String material = ms != null ? ms.getString("material", "PAPER") : "PAPER";
        String name = ms != null ? ms.getString("name", "<white>" + module.label) : "<white>" + module.label;
        ItemStack item = baseItem(material, "PAPER", name);
        List<String> lore = new ArrayList<>();
        if (ms != null && ms.getString("description") != null) {
            lore.add(ms.getString("description"));
        }
        lore.add("<dark_gray>Adjust the values below.");
        setLore(item, lore);
        return item;
    }

    private ItemStack buildSettingItem(Setting setting) {
        String material = settingMaterial(setting);
        String name = config.getString("settings-menu.setting-item.name", "<white><setting>")
                .replace("<setting>", setting.label());
        String value = currentValueDisplay(setting);

        ItemStack item = baseItem(material, "NAME_TAG", name);
        List<String> lore = new ArrayList<>();
        for (String line : config.getStringList("settings-menu.setting-item.lore")) {

            if (setting.type() == SettingType.BOOLEAN && line.toLowerCase().contains("edit in chat")) {
                line = "<yellow>» Click to toggle";
            }
            lore.add(line.replace("<setting>", setting.label()).replace("<value>", value));
        }
        setLore(item, lore);
        if (setting.type() == SettingType.BOOLEAN && plugin.getConfig().getBoolean(setting.path(), false)) {
            glow(item);
        }
        return item;
    }

    private String settingMaterial(Setting setting) {
        return switch (setting.type()) {
            case BOOLEAN -> plugin.getConfig().getBoolean(setting.path(), false)
                    ? config.getString("settings-menu.icons.boolean-on", "LIME_DYE")
                    : config.getString("settings-menu.icons.boolean-off", "GRAY_DYE");
            case INT -> config.getString("settings-menu.icons.int", "COMPARATOR");
            case LONG -> config.getString("settings-menu.icons.long", "REPEATER");
            case DOUBLE -> config.getString("settings-menu.icons.double", "CLOCK");
            case STRING -> config.getString("settings-menu.icons.string", "NAME_TAG");
        };
    }

    private ItemStack buildSimpleItem(String path, String defMaterial, String defName) {
        String material = config.getString(path + ".material", defMaterial);
        String name = config.getString(path + ".name", defName);
        ItemStack item = baseItem(material, defMaterial, name);
        setLore(item, config.getStringList(path + ".lore"));
        return item;
    }

    private void placeInfo(Inventory inv, int moduleCount, int enabledCount) {
        if (!config.getBoolean("main-menu.info.enabled", true)) return;
        int slot = config.getInt("main-menu.info.slot", 4);
        if (slot < 0 || slot >= inv.getSize()) return;
        String material = config.getString("main-menu.info.material", "COMPASS");
        String name = config.getString("main-menu.info.name", "<gradient:#20B2AA:#7FFFD4>Control Panel</gradient>");
        ItemStack item = baseItem(material, "COMPASS", name);
        List<String> lore = new ArrayList<>();
        for (String line : config.getStringList("main-menu.info.lore")) {
            lore.add(line.replace("<module_count>", String.valueOf(moduleCount))
                    .replace("<enabled_count>", String.valueOf(enabledCount)));
        }
        setLore(item, lore);
        inv.setItem(slot, item);
    }

    private void fill(Inventory inv) {
        String material = config.getString("settings.filler-material", "BLACK_STAINED_GLASS_PANE");
        if (material == null || material.isEmpty() || material.equalsIgnoreCase("AIR")) return;
        Material mat = Material.matchMaterial(material);
        if (mat == null) return;
        String name = config.getString("settings.filler-name", " ");
        ItemStack filler = new ItemStack(mat);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize(name).decoration(TextDecoration.ITALIC, false));
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private ItemStack baseItem(String materialName, String fallback, String name) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || material == Material.AIR) {
            material = Material.matchMaterial(fallback);
        }
        if (material == null) {
            material = Material.PAPER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize(name).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyLore(ItemStack item, List<String> template, String state, String reloadNote, boolean hasToggle) {
        List<String> resolved = new ArrayList<>();
        for (String line : template) {
            String value = line.replace("<state>", state).replace("<reload_note>", reloadNote);

            if (!hasToggle && value.toLowerCase().contains("left-click to toggle")) {
                continue;
            }
            resolved.add(value);
        }
        setLore(item, resolved);
    }

    private void setLore(ItemStack item, List<String> lines) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private void glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
    }

    private String reloadNote(Module module) {
        if (module.reload == ReloadType.RESTART) {
            return config.getString("main-menu.note-restart", "<gold>Restart required to fully apply");
        }
        return config.getString("main-menu.note-live", "<dark_gray>Applies instantly on toggle");
    }

    private String moduleDisplayName(String moduleId) {
        ConfigurationSection ms = config.getConfigurationSection("main-menu.modules." + moduleId);
        String raw = ms != null ? ms.getString("name", moduleId) : moduleId;

        return PlainTextComponentSerializer.plainText().serialize(mm.deserialize(raw));
    }

    private String currentValueDisplay(Setting setting) {
        FileConfiguration c = plugin.getConfig();
        return switch (setting.type()) {
            case INT -> String.valueOf(c.getInt(setting.path()));
            case LONG -> String.valueOf(c.getLong(setting.path()));
            case DOUBLE -> String.valueOf(c.getDouble(setting.path()));
            case BOOLEAN -> c.getBoolean(setting.path()) ? "true" : "false";
            case STRING -> {
                String v = c.getString(setting.path(), "");
                yield (v == null || v.isEmpty()) ? "(empty)" : v;
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DashboardHolder holder)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;

        int slot = event.getRawSlot();
        if (holder.type == ViewType.MAIN) {
            if (slot == holder.closeSlot) {
                player.closeInventory();
                return;
            }
            String moduleId = holder.moduleSlots.get(slot);
            if (moduleId == null) return;
            Module module = MODULES.get(moduleId);
            if (module == null) return;

            if (event.getClick() == ClickType.RIGHT || !module.hasToggle()) {
                openSettings(player, moduleId);
            } else {
                toggleModule(player, module, holder);
            }
        } else if (holder.type == ViewType.SETTINGS) {
            if (slot == holder.backSlot) {
                openMain(player);
                return;
            }
            Module module = MODULES.get(holder.moduleId);
            if (module == null) return;
            if (module.hasToggle() && slot == holder.toggleSlot) {
                toggleModule(player, module, holder);
                return;
            }
            Integer settingIndex = holder.settingSlots.get(slot);
            if (settingIndex != null && settingIndex >= 0 && settingIndex < module.settings.size()) {
                Setting setting = module.settings.get(settingIndex);
                if (setting.type() == SettingType.BOOLEAN) {
                    toggleSetting(player, module, setting, holder);
                } else {
                    beginEdit(player, module, setting);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DashboardHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        pendingEdits.remove(event.getPlayer().getUniqueId());
    }

    private void toggleModule(Player player, Module module, DashboardHolder holder) {
        boolean next = !plugin.getConfig().getBoolean(module.togglePath, false);

        if (!writeValueInPlace(module.togglePath, next ? "true" : "false")) {
            send(player, "messages.write-failed",
                    "<red>Could not write that change to config.yml. Check the console.");
            return;
        }

        String moduleName = moduleDisplayName(module.id);

        reloadAndThen(player, () -> {
            rerender(holder);
            playSound(player, next ? "settings.sounds.toggle-on" : "settings.sounds.toggle-off");
            send(player, next ? "messages.toggled-on" : "messages.toggled-off",
                    next ? "<white><module_name> enabled." : "<white><module_name> disabled.",
                    "<module_name>", moduleName);
            if (next && module.reload == ReloadType.RESTART) {
                send(player, "messages.restart-note",
                        "<gold>Note: a server restart is needed for <module_name> to fully take effect.",
                        "<module_name>", moduleName);
            }
        });
    }

    private void toggleSetting(Player player, Module module, Setting setting, DashboardHolder holder) {
        boolean next = !plugin.getConfig().getBoolean(setting.path(), false);

        if (!writeValueInPlace(setting.path(), next ? "true" : "false")) {
            send(player, "messages.write-failed",
                    "<red>Could not write that change to config.yml. Check the console.");
            return;
        }

        reloadAndThen(player, () -> {
            rerender(holder);
            playSound(player, next ? "settings.sounds.toggle-on" : "settings.sounds.toggle-off");
            send(player, "messages.edit-saved", "<white><setting> set to <green><value><white>.",
                    "<setting>", setting.label(), "<value>", currentValueDisplay(setting));
        });
    }

    private void reloadAndThen(Player player, Runnable afterReload) {
        plugin.getFoliaScheduler().runNow(() -> {
            plugin.reload();
            plugin.getFoliaScheduler().runAtEntity(player, afterReload);
        });
    }

    private void rerender(DashboardHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        if (holder.type == ViewType.MAIN) {
            ConfigurationSection modulesSection = config.getConfigurationSection("main-menu.modules");
            int enabledCount = 0;
            for (Map.Entry<Integer, String> entry : holder.moduleSlots.entrySet()) {
                Module module = MODULES.get(entry.getValue());
                ConfigurationSection ms = modulesSection != null
                        ? modulesSection.getConfigurationSection(entry.getValue()) : null;
                if (module == null || ms == null) continue;
                if (module.hasToggle() && plugin.getConfig().getBoolean(module.togglePath, false)) {
                    enabledCount++;
                }
                inv.setItem(entry.getKey(), buildModuleItem(module, ms));
            }
            placeInfo(inv, holder.moduleSlots.size(), enabledCount);
        } else if (holder.type == ViewType.SETTINGS) {
            Module module = MODULES.get(holder.moduleId);
            if (module == null) return;
            if (module.hasToggle() && holder.toggleSlot >= 0 && holder.toggleSlot < inv.getSize()) {
                inv.setItem(holder.toggleSlot, buildToggleItem(module));
            }
            for (Map.Entry<Integer, Integer> entry : holder.settingSlots.entrySet()) {
                int idx = entry.getValue();
                if (idx >= 0 && idx < module.settings.size()) {
                    inv.setItem(entry.getKey(), buildSettingItem(module.settings.get(idx)));
                }
            }
        }
    }

    private void beginEdit(Player player, Module module, Setting setting) {
        pendingEdits.put(player.getUniqueId(), new PendingEdit(module.id, setting));
        playSound(player, "settings.sounds.edit");
        player.closeInventory();

        send(player, "messages.edit-prompt",
                "<gray>Type the new value for <yellow><setting></yellow> in chat, or <gray>cancel</gray>.",
                "<setting>", setting.label());
        send(player, "messages.edit-current", "<dark_gray>Current value: <gray><value>",
                "<value>", currentValueDisplay(setting));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingEdit edit = pendingEdits.get(player.getUniqueId());
        if (edit == null) return;

        event.setCancelled(true);
        pendingEdits.remove(player.getUniqueId());

        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        plugin.getFoliaScheduler().runAtEntity(player, () -> applyEdit(player, edit, input));
    }

    private void applyEdit(Player player, PendingEdit edit, String input) {
        Module module = MODULES.get(edit.moduleId());
        Setting setting = edit.setting();
        if (module == null) return;

        if (input.equalsIgnoreCase("cancel")) {
            send(player, "messages.edit-cancelled", "<red>Edit cancelled.");
            openSettings(player, module.id);
            return;
        }

        String raw = formatForYaml(setting.type(), input);
        if (raw == null) {
            send(player, "messages.edit-invalid", "<red>'<input>' is not a valid <type> value.",
                    "<input>", input, "<type>", setting.type().name().toLowerCase());
            openSettings(player, module.id);
            return;
        }

        if (!writeValueInPlace(setting.path(), raw)) {
            send(player, "messages.write-failed",
                    "<red>Could not write that change to config.yml. Check the console.");
            openSettings(player, module.id);
            return;
        }

        reloadAndThen(player, () -> {
            playSound(player, "settings.sounds.save");
            send(player, "messages.edit-saved", "<white><setting> set to <green><value><white>.",
                    "<setting>", setting.label(), "<value>", currentValueDisplay(setting));
            openSettings(player, module.id);
        });
    }

    private String formatForYaml(SettingType type, String input) {
        try {
            switch (type) {
                case INT:
                    return String.valueOf(Integer.parseInt(input.trim()));
                case LONG:
                    return String.valueOf(Long.parseLong(input.trim()));
                case DOUBLE:
                    return String.valueOf(Double.parseDouble(input.trim()));
                case BOOLEAN: {
                    String v = input.trim().toLowerCase();
                    if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("enable")
                            || v.equals("enabled")) {
                        return "true";
                    }
                    if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("disable")
                            || v.equals("disabled")) {
                        return "false";
                    }
                    return null;
                }
                case STRING:
                default:

                    String escaped = input.replace("\\", "\\\\").replace("\"", "\\\"");
                    return "\"" + escaped + "\"";
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized boolean writeValueInPlace(String path, String rawValue) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            plugin.getLogger().warning("Dashboard: unsupported config path (expected parent.child): " + path);
            return false;
        }
        String parent = path.substring(0, dot);
        String child = path.substring(dot + 1);

        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            return false;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            Pattern childPattern = Pattern.compile(
                    "^(\\s+)" + Pattern.quote(child) + ":(\\s*)(\\S.*?)?(\\s+#.*)?$");

            boolean inParent = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.strip();
                int indent = indentOf(line);

                if (!inParent) {
                    if (indent == 0 && trimmed.equals(parent + ":")) {
                        inParent = true;
                    }
                    continue;
                }

                if (indent == 0 && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    break;
                }

                if (indent == 2) {
                    Matcher m = childPattern.matcher(line);
                    if (m.matches()) {
                        String leading = m.group(1);
                        String comment = m.group(4) == null ? "" : m.group(4);
                        lines.set(i, leading + child + ": " + rawValue + comment);
                        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
                        return true;
                    }
                }
            }

            plugin.getLogger().warning("Dashboard: could not locate '" + path + "' in config.yml to update.");
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("Dashboard: failed to write '" + path + "' to config.yml: " + e.getMessage());
            return false;
        }
    }

    private int indentOf(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private int clampRows(int rows) {
        if (rows < 1) return 1;
        if (rows > 6) return 6;
        return rows;
    }

    private void playSound(Player player, String path) {
        String key = config.getString(path, "");
        if (key == null || key.isEmpty()) return;
        try {
            player.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 1.0f, 1.0f));
        } catch (Throwable ignored) {
        }
    }

    private void send(Player player, String path, String def, String... replacements) {
        String raw = config.getString(path, def);
        if (raw == null || raw.isEmpty()) return;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        player.sendMessage(mm.deserialize(raw));
    }

    private enum ViewType { MAIN, SETTINGS }

    private static final class DashboardHolder implements InventoryHolder {
        final ViewType type;
        final String moduleId;
        final Map<Integer, String> moduleSlots;
        final Map<Integer, Integer> settingSlots;
        final int toggleSlot;
        final int backSlot;
        final int closeSlot;
        private Inventory inventory;

        DashboardHolder(ViewType type, String moduleId, Map<Integer, String> moduleSlots,
                Map<Integer, Integer> settingSlots, int toggleSlot, int backSlot, int closeSlot) {
            this.type = type;
            this.moduleId = moduleId;
            this.moduleSlots = moduleSlots;
            this.settingSlots = settingSlots;
            this.toggleSlot = toggleSlot;
            this.backSlot = backSlot;
            this.closeSlot = closeSlot;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record PendingEdit(String moduleId, Setting setting) {}
}
