package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.BlocksRegion;
import eu.kotori.justRTP.utils.CuboidRegion;
import eu.kotori.justRTP.utils.CylinderRegion;
import eu.kotori.justRTP.utils.RTPZone;
import eu.kotori.justRTP.utils.ZoneRegion;
import eu.kotori.justRTP.utils.ZoneShape;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneSetupManager {

    private enum SetupStep {
        AWAITING_POS1,
        AWAITING_POS2,
        AWAITING_CENTER,
        AWAITING_RADIUS,
        AWAITING_Y_RANGE,
        AWAITING_BLOCKS,
        AWAITING_TARGET,
        AWAITING_MIN_RADIUS,
        AWAITING_MAX_RADIUS,
        AWAITING_INTERVAL,
        AWAITING_VIEW_DISTANCE
    }

    private final JustRTP plugin;
    private final Map<UUID, ZoneBuilder> setupSessions = new ConcurrentHashMap<>();

    public ZoneSetupManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    private ItemStack createWand(ZoneShape shape) {
        Material material;
        String displayName;
        List<String> lore = new ArrayList<>();

        switch (shape) {
            case CYLINDER:
                material = Material.END_ROD;
                displayName = "<gradient:#20B2AA:#7FFFD4>RTP Zone Wand</gradient> <gray>(Cylinder)";
                lore.add("<gray>Left-Click: <aqua>Set Center</aqua></gray>");
                lore.add("<gray>Right-Click: <aqua>Set Radius Edge</aqua></gray>");
                lore.add("<dark_gray>Type <red>cancel</red><dark_gray> to abort");
                break;
            case BLOCKS:
                material = Material.BLAZE_ROD;
                displayName = "<gradient:#FF6B35:#F7931E>RTP Zone Wand</gradient> <gray>(Blocks)";
                lore.add("<gray>Left-Click: <green>Add Block</green></gray>");
                lore.add("<gray>Right-Click: <red>Remove Block</red></gray>");
                lore.add("<gray>Shift+Left-Click: <yellow>Clear All</yellow></gray>");
                lore.add("<dark_gray>Type <green>done</green><dark_gray> when finished");
                break;
            case CUBOID:
            default:
                material = Material.BLAZE_ROD;
                displayName = "<gradient:#FF8C00:#FFD700>RTP Zone Wand</gradient> <gray>(Cuboid)";
                lore.add("<gray>Left-Click: <yellow>Set Position 1</yellow></gray>");
                lore.add("<gray>Right-Click: <yellow>Set Position 2</yellow></gray>");
                lore.add("<dark_gray>Type <red>cancel</red><dark_gray> to abort");
                break;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        MiniMessage mm = MiniMessage.miniMessage();
        meta.displayName(mm.deserialize(displayName).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> loreComponents = new ArrayList<>();
        for (String line : lore) {
            loreComponents.add(mm.deserialize(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        meta.lore(loreComponents);
        meta.getPersistentDataContainer().set(plugin.getCommandManager().getWandKey(), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(plugin.getCommandManager().getWandTypeKey(), PersistentDataType.STRING, shape.name());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(plugin.getCommandManager().getWandKey(), PersistentDataType.BYTE);
    }

    public boolean isInSetupMode(Player player) {
        return setupSessions.containsKey(player.getUniqueId());
    }

    public void startSetup(Player player, String zoneId, ZoneShape shape) {
        if (isInSetupMode(player)) {
            plugin.getLocaleManager().sendMessage(player, "zone.error.already_in_setup");
            return;
        }

        if (plugin.getRtpZoneManager().zoneExists(zoneId)) {
            plugin.getLocaleManager().sendMessage(player, "zone.error.already_exists", Placeholder.unparsed("id", zoneId));
            return;
        }

        ZoneBuilder builder = new ZoneBuilder(zoneId, player.getWorld().getName(), shape);
        setupSessions.put(player.getUniqueId(), builder);
        player.getInventory().addItem(createWand(shape));
        plugin.getLocaleManager().sendMessage(player, "zone.setup.started",
                Placeholder.unparsed("id", zoneId),
                Placeholder.unparsed("shape", shape.name().toLowerCase()));

        switch (shape) {
            case CYLINDER:
                builder.step = SetupStep.AWAITING_CENTER;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.center_prompt");
                break;
            case BLOCKS:
                builder.step = SetupStep.AWAITING_BLOCKS;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.add_prompt");
                break;
            case CUBOID:
            default:
                builder.step = SetupStep.AWAITING_POS1;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.pos1_prompt");
                break;
        }
    }

    public void cancelSetup(Player player) {
        ZoneBuilder builder = setupSessions.remove(player.getUniqueId());
        if (builder != null) {
            removeAllWands(player);
            if (plugin.getZoneParticleManager() != null) {
                plugin.getZoneParticleManager().stopSetupPreview(player);
            }
            plugin.getLocaleManager().sendMessage(player, "zone.setup.cancelled");
        }
    }

    private void refreshSetupPreview(Player player, ZoneBuilder builder) {
        if (plugin.getZoneParticleManager() == null) return;
        plugin.getZoneParticleManager().startSetupPreview(player, () -> buildPreviewRegion(builder));
    }

    private ZoneRegion buildPreviewRegion(ZoneBuilder builder) {
        try {
            switch (builder.shape) {
                case CUBOID:
                    if (builder.pos1 != null && builder.pos2 != null) {
                        return new CuboidRegion(builder.pos1, builder.pos2);
                    }
                    break;
                case CYLINDER:
                    if (builder.center != null && builder.radius > 0) {
                        int minY = builder.minY != null ? builder.minY : builder.center.getWorld().getMinHeight();
                        int maxY = builder.maxY != null ? builder.maxY : builder.center.getWorld().getMaxHeight() - 1;
                        return new CylinderRegion(builder.center, builder.radius, minY, maxY);
                    }
                    break;
                case BLOCKS:
                    if (!builder.blocks.isEmpty()) {
                        return new BlocksRegion(builder.worldName, builder.blocks);
                    }
                    break;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void removeAllWands(Player player) {
        player.getInventory().forEach(item -> {
            if (isWand(item)) {
                player.getInventory().remove(item);
            }
        });
    }

    public void handleWandInteraction(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInSetupMode(player)) return;
        if (event.getClickedBlock() == null) return;

        ZoneBuilder builder = setupSessions.get(player.getUniqueId());

        switch (builder.shape) {
            case CUBOID:
                handleCuboidWand(player, builder, event);
                break;
            case CYLINDER:
                handleCylinderWand(player, builder, event);
                break;
            case BLOCKS:
                handleBlocksWand(player, builder, event);
                break;
        }
    }

    private void handleCuboidWand(Player player, ZoneBuilder builder, PlayerInteractEvent event) {
        Action action = event.getAction();
        Location clicked = event.getClickedBlock().getLocation();

        if (action == Action.LEFT_CLICK_BLOCK) {
            builder.pos1 = clicked;
            plugin.getLocaleManager().sendMessage(player, "zone.setup.pos1_set",
                    Placeholder.unparsed("coords", formatLocation(builder.pos1)));
            if (builder.step == SetupStep.AWAITING_POS1) {
                builder.step = SetupStep.AWAITING_POS2;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.pos2_prompt");
            }
            checkCuboidPositionsAndProceed(player, builder);
            refreshSetupPreview(player, builder);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            builder.pos2 = clicked;
            plugin.getLocaleManager().sendMessage(player, "zone.setup.pos2_set",
                    Placeholder.unparsed("coords", formatLocation(builder.pos2)));
            checkCuboidPositionsAndProceed(player, builder);
            refreshSetupPreview(player, builder);
        }
    }

    private void checkCuboidPositionsAndProceed(Player player, ZoneBuilder builder) {
        if (builder.pos1 != null && builder.pos2 != null
                && (builder.step == SetupStep.AWAITING_POS1 || builder.step == SetupStep.AWAITING_POS2)) {
            builder.step = SetupStep.AWAITING_TARGET;
            plugin.getLocaleManager().sendMessage(player, "zone.setup.target_prompt");
        }
    }

    private void handleCylinderWand(Player player, ZoneBuilder builder, PlayerInteractEvent event) {
        Action action = event.getAction();
        Location clicked = event.getClickedBlock().getLocation();

        if (action == Action.LEFT_CLICK_BLOCK) {
            builder.center = clicked;
            plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.center_set",
                    Placeholder.unparsed("coords", formatLocation(clicked)));
            if (builder.step == SetupStep.AWAITING_CENTER) {
                builder.step = SetupStep.AWAITING_RADIUS;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.radius_prompt");
            }
            refreshSetupPreview(player, builder);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            if (builder.center == null) {
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.center_required");
                return;
            }
            double dx = clicked.getBlockX() - builder.center.getBlockX();
            double dz = clicked.getBlockZ() - builder.center.getBlockZ();
            int radius = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (radius <= 0) radius = 1;
            builder.radius = radius;
            plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.radius_set",
                    Placeholder.unparsed("radius", String.valueOf(radius)));
            if (builder.step == SetupStep.AWAITING_RADIUS) {
                builder.step = SetupStep.AWAITING_Y_RANGE;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_prompt");
            }
            refreshSetupPreview(player, builder);
        }
    }

    private void handleBlocksWand(Player player, ZoneBuilder builder, PlayerInteractEvent event) {
        Action action = event.getAction();
        Location clicked = event.getClickedBlock().getLocation();
        int x = clicked.getBlockX();
        int y = clicked.getBlockY();
        int z = clicked.getBlockZ();
        long key = packKey(x, y, z);

        if (action == Action.LEFT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                int cleared = builder.blocks.size();
                builder.blocks.clear();
                builder.blockKeys.clear();
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.cleared",
                        Placeholder.unparsed("count", String.valueOf(cleared)));
                return;
            }
            if (builder.blockKeys.add(key)) {
                builder.blocks.add(new int[]{x, y, z});
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.added",
                        Placeholder.unparsed("coords", formatLocation(clicked)),
                        Placeholder.unparsed("count", String.valueOf(builder.blocks.size())));
                refreshSetupPreview(player, builder);
            } else {
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.already_added",
                        Placeholder.unparsed("coords", formatLocation(clicked)));
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            if (builder.blockKeys.remove(key)) {
                builder.blocks.removeIf(pos -> pos[0] == x && pos[1] == y && pos[2] == z);
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.removed",
                        Placeholder.unparsed("coords", formatLocation(clicked)),
                        Placeholder.unparsed("count", String.valueOf(builder.blocks.size())));
                refreshSetupPreview(player, builder);
            } else {
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.not_in_set",
                        Placeholder.unparsed("coords", formatLocation(clicked)));
            }
        }
    }

    public void finishBlockSelection(Player player) {
        if (!isInSetupMode(player)) return;
        ZoneBuilder builder = setupSessions.get(player.getUniqueId());
        if (builder.shape != ZoneShape.BLOCKS || builder.step != SetupStep.AWAITING_BLOCKS) {
            plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.not_in_block_mode");
            return;
        }
        if (builder.blocks.isEmpty()) {
            plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.empty");
            return;
        }
        builder.step = SetupStep.AWAITING_TARGET;
        plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.finished",
                Placeholder.unparsed("count", String.valueOf(builder.blocks.size())));
        plugin.getLocaleManager().sendMessage(player, "zone.setup.target_prompt");
    }

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0xFFF));
    }

    public void handleChatInput(Player player, String input) {
        if (!isInSetupMode(player)) return;

        if (input.equalsIgnoreCase("cancel")) {
            cancelSetup(player);
            return;
        }

        if (input.equalsIgnoreCase("done")) {
            finishBlockSelection(player);
            return;
        }

        ZoneBuilder builder = setupSessions.get(player.getUniqueId());
        switch (builder.step) {
            case AWAITING_POS1:
            case AWAITING_POS2:
                plugin.getLocaleManager().sendMessage(player, "zone.setup.position_first");
                break;
            case AWAITING_CENTER:
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.center_required");
                break;
            case AWAITING_RADIUS:
                try {
                    int radius = Integer.parseInt(input);
                    if (radius <= 0) {
                        plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                        return;
                    }
                    builder.radius = radius;
                    builder.step = SetupStep.AWAITING_Y_RANGE;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.radius_set",
                            Placeholder.unparsed("radius", input));
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_prompt");
                } catch (NumberFormatException e) {
                    plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                }
                break;
            case AWAITING_Y_RANGE:
                if (parseYRange(player, builder, input)) {
                    builder.step = SetupStep.AWAITING_TARGET;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_set",
                            Placeholder.unparsed("min_y", String.valueOf(builder.minY)),
                            Placeholder.unparsed("max_y", String.valueOf(builder.maxY)));
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.target_prompt");
                }
                break;
            case AWAITING_BLOCKS:
                plugin.getLocaleManager().sendMessage(player, "zone.setup.blocks.use_wand");
                break;
            case AWAITING_TARGET:
                builder.target = input;
                builder.step = SetupStep.AWAITING_MIN_RADIUS;
                plugin.getLocaleManager().sendMessage(player, "zone.setup.target_set", Placeholder.unparsed("target", input));
                plugin.getLocaleManager().sendMessage(player, "zone.setup.min_radius_prompt");
                break;
            case AWAITING_MIN_RADIUS:
                try {
                    int radius = Integer.parseInt(input);
                    if (radius < 0) {
                        plugin.getLocaleManager().sendMessage(player, "command.invalid_radius");
                        return;
                    }
                    builder.minRadius = radius;
                    builder.step = SetupStep.AWAITING_MAX_RADIUS;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.min_radius_set", Placeholder.unparsed("radius", input));
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.max_radius_prompt");
                } catch (NumberFormatException e) {
                    plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                }
                break;
            case AWAITING_MAX_RADIUS:
                try {
                    int radius = Integer.parseInt(input);
                    if (radius <= builder.minRadius) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<prefix> <red>Max radius must be greater than the minimum radius of " + builder.minRadius + ".</red>",
                                Placeholder.unparsed("prefix", plugin.getLocaleManager().getRawMessage("prefix"))));
                        return;
                    }
                    builder.maxRadius = radius;
                    builder.step = SetupStep.AWAITING_INTERVAL;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.max_radius_set", Placeholder.unparsed("radius", input));
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.interval_prompt");
                } catch (NumberFormatException e) {
                    plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                }
                break;
            case AWAITING_INTERVAL:
                try {
                    int interval = Integer.parseInt(input);
                    if (interval <= 0) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<prefix> <red>Interval must be a positive number.</red>",
                                Placeholder.unparsed("prefix", plugin.getLocaleManager().getRawMessage("prefix"))));
                        return;
                    }
                    builder.interval = interval;
                    builder.step = SetupStep.AWAITING_VIEW_DISTANCE;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.interval_set", Placeholder.unparsed("seconds", input));
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.view_distance_prompt");
                } catch (NumberFormatException e) {
                    plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                }
                break;
            case AWAITING_VIEW_DISTANCE:
                try {
                    int distance = Integer.parseInt(input);
                    if (distance < 0) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<prefix> <red>View distance cannot be negative.</red>",
                                Placeholder.unparsed("prefix", plugin.getLocaleManager().getRawMessage("prefix"))));
                        return;
                    }
                    builder.viewDistance = distance;
                    plugin.getLocaleManager().sendMessage(player, "zone.setup.view_distance_set", Placeholder.unparsed("distance", input));
                    finishSetup(player, builder);
                } catch (NumberFormatException e) {
                    plugin.getLocaleManager().sendMessage(player, "zone.error.invalid_number");
                }
                break;
        }
    }

    private boolean parseYRange(Player player, ZoneBuilder builder, String input) {
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("auto") || trimmed.equalsIgnoreCase("all")) {
            builder.minY = player.getWorld().getMinHeight();
            builder.maxY = player.getWorld().getMaxHeight() - 1;
            return true;
        }
        String[] parts = trimmed.split("[\\s,]+");
        if (parts.length != 2) {
            plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_invalid");
            return false;
        }
        try {
            int min = Integer.parseInt(parts[0]);
            int max = Integer.parseInt(parts[1]);
            if (max < min) {
                plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_invalid");
                return false;
            }
            builder.minY = min;
            builder.maxY = max;
            return true;
        } catch (NumberFormatException e) {
            plugin.getLocaleManager().sendMessage(player, "zone.setup.cylinder.y_range_invalid");
            return false;
        }
    }

    private void finishSetup(Player player, ZoneBuilder builder) {
        RTPZone zone = builder.build();
        if (plugin.getZoneParticleManager() != null) {
            zone.setParticleStyle(eu.kotori.justRTP.utils.ZoneParticleStyle.fromString(
                    plugin.getZoneParticleManager().getDefaultStyle()));
        }
        plugin.getRtpZoneManager().saveZone(zone);
        setupSessions.remove(player.getUniqueId());
        removeAllWands(player);
        if (plugin.getZoneParticleManager() != null) {
            plugin.getZoneParticleManager().stopSetupPreview(player);
        }
        plugin.getLocaleManager().sendMessage(player, "zone.setup.complete", Placeholder.unparsed("id", zone.getId()));
    }

    private String formatLocation(Location loc) {
        return String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private class ZoneBuilder {
        final String id;
        final String worldName;
        final ZoneShape shape;
        SetupStep step = SetupStep.AWAITING_POS1;

        Location pos1;
        Location pos2;

        Location center;
        int radius;
        Integer minY;
        Integer maxY;

        final List<int[]> blocks = new ArrayList<>();
        final Set<Long> blockKeys = new HashSet<>();

        String target;
        int minRadius;
        int maxRadius;
        int interval;
        int viewDistance;

        ZoneBuilder(String id, String worldName, ZoneShape shape) {
            this.id = id;
            this.worldName = worldName;
            this.shape = shape;
        }

        RTPZone build() {
            ConfigurationSection section = new YamlConfiguration();
            section.set("world", worldName);
            section.set("shape", shape.name());

            ZoneRegion region;
            switch (shape) {
                case CYLINDER:
                    section.set("center", center);
                    section.set("radius", radius);
                    section.set("min-y", minY);
                    section.set("max-y", maxY);
                    region = new CylinderRegion(center, radius, minY, maxY);
                    break;
                case BLOCKS:
                    List<String> encoded = new ArrayList<>(blocks.size());
                    for (int[] pos : blocks) {
                        encoded.add(pos[0] + "," + pos[1] + "," + pos[2]);
                    }
                    section.set("blocks", encoded);
                    region = new BlocksRegion(worldName, blocks);
                    break;
                case CUBOID:
                default:
                    section.set("pos1", pos1);
                    section.set("pos2", pos2);
                    region = new CuboidRegion(pos1, pos2);
                    break;
            }

            section.set("target", target);
            section.set("min-radius", minRadius);
            section.set("max-radius", maxRadius);
            section.set("interval", interval);

            Location hologramLocation = null;
            int viewDistanceValue = viewDistance;
            Location regionCenter = region.getCenter();
            if (regionCenter != null) {
                double yOffset = JustRTP.getInstance().getHologramManager().getDefaultYOffset();
                hologramLocation = regionCenter.clone().add(0, yOffset, 0);
                section.set("hologram.location", hologramLocation);
                section.set("hologram.view-distance", viewDistanceValue);
            }

            ConfigurationSection effectsSection = section.createSection("effects");
            ConfigurationSection onEnterSection = effectsSection.createSection("on_enter");
            onEnterSection.set("title.enabled", true);
            onEnterSection.set("title.main_title", "<green>Entered Zone</green>");
            onEnterSection.set("sound.enabled", true);
            onEnterSection.set("sound.name", "BLOCK_NOTE_BLOCK_PLING");

            ConfigurationSection onLeaveSection = effectsSection.createSection("on_leave");
            onLeaveSection.set("title.enabled", true);
            onLeaveSection.set("title.main_title", "<red>Left Zone</red>");

            ConfigurationSection waitingSection = effectsSection.createSection("waiting");
            waitingSection.set("title.enabled", true);
            waitingSection.set("title.fade_in", 0);
            waitingSection.set("title.stay", 25);
            waitingSection.set("title.fade_out", 5);
            waitingSection.set("title.main_title", "<gradient:red:gold>RTP ZONE</gradient>");
            waitingSection.set("title.subtitle", "<yellow>Teleporting in <time>s...");
            waitingSection.set("action_bar.enabled", true);
            waitingSection.set("action_bar.text", "<gray>Teleporting in <white><time>s</white>...");
            waitingSection.set("sound.enabled", true);
            waitingSection.set("sound.name", "BLOCK_NOTE_BLOCK_HAT");
            waitingSection.set("sound.volume", 0.5);
            waitingSection.set("sound.pitch", 1.2);

            ConfigurationSection teleportSection = effectsSection.createSection("teleport");
            teleportSection.set("title.enabled", true);
            teleportSection.set("title.main_title", "<dark_red>FIGHT!</dark_red>");
            teleportSection.set("sound.enabled", true);
            teleportSection.set("sound.name", "ENTITY_ENDER_DRAGON_GROWL");

            return new RTPZone(id, section);
        }
    }
}
