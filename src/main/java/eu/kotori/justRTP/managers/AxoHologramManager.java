package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AxoHologramManager {

    private static final String ID_PREFIX = "justrtp_zone_";

    private static final String IDLE_PLACEHOLDER = "⏳";

    private final JustRTP plugin;
    private FileConfiguration hologramsConfig;
    private boolean available = false;

    private Object api;

    private Method mExists;
    private Method mCreateHologram;
    private Method mUpdateLines;
    private Method mTeleport;
    private Method mDelete;
    private Method mGetHologram;
    private Method mGetHolograms;

    private Method mGetId;
    private Method mSetViewDistance;
    private Method mSetScale;
    private Method mRefreshViewers;

    private final Map<String, List<String>> hologramTemplates = new ConcurrentHashMap<>();

    private final Set<String> activeZones = ConcurrentHashMap.newKeySet();

    private final Map<String, String> idToZone = new ConcurrentHashMap<>();

    public AxoHologramManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("AxoHologram")) {
                this.available = false;
                return;
            }

            Class<?> apiClass = Class.forName("org.axostudio.axohologram.api.AxoHologramAPI");
            Class<?> hologramClass = Class.forName("org.axostudio.axohologram.hologram.Hologram");

            this.api = hookApi(apiClass);
            if (api == null) {
                this.available = false;
                plugin.getLogger().warning("AxoHologram is enabled but its API could not be obtained.");
                return;
            }

            mExists = apiClass.getMethod("exists", String.class);
            mCreateHologram = apiClass.getMethod("createHologram",
                    String.class, Location.class, List.class, boolean.class);
            mUpdateLines = apiClass.getMethod("updateLines", String.class, List.class);
            mTeleport = apiClass.getMethod("teleportHologram", String.class, Location.class);
            mDelete = apiClass.getMethod("deleteHologram", String.class);
            mGetHologram = apiClass.getMethod("getHologram", String.class);
            mGetHolograms = apiClass.getMethod("getHolograms");

            mGetId = hologramClass.getMethod("getId");
            mSetViewDistance = hologramClass.getMethod("setViewDistance", int.class);
            mSetScale = hologramClass.getMethod("setScale", float.class);
            mRefreshViewers = hologramClass.getMethod("refreshViewers");

            this.available = true;
            plugin.getLogger().info("AxoHologram detected! Using AxoHologram for zone holograms.");
        } catch (Throwable t) {
            this.available = false;
            plugin.getLogger().warning("Failed to initialize AxoHologram support: " + t.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "AxoHologram error: " + t);
        }
    }

    private Object hookApi(Class<?> apiClass) {

        try {
            Class<?> main = Class.forName("org.axostudio.axohologram.AxoHologram");
            Object direct = main.getMethod("getAPI").invoke(null);
            if (direct != null) {
                return direct;
            }
        } catch (Throwable ignored) {

        }

        try {
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(apiClass);
            if (provider != null) {
                return provider.getProvider();
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public void setHologramsConfig(FileConfiguration hologramsConfig) {
        this.hologramsConfig = hologramsConfig;
    }

    public boolean isAvailable() {
        return available && api != null;
    }

    private String hologramId(String zoneId) {

        return (ID_PREFIX + zoneId).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public void refreshForPlayer(Player player) {
        if (!isAvailable() || player == null || !player.isOnline()) return;

        try {
            forEachJustRtpHologram((id, holo) -> {
                try {
                    mRefreshViewers.invoke(holo);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Error refreshing AxoHolograms for player " + player.getName() + ": " + t.getMessage());
        }
    }

    public void createOrUpdateHologram(String zoneId, Location location, int viewDistance) {
        if (!isAvailable() || location == null || location.getWorld() == null) return;

        try {
            String id = hologramId(zoneId);
            idToZone.put(id, zoneId);

            List<String> template = loadTemplateLines(zoneId);
            if (template.isEmpty()) {
                plugin.getRTPLogger().debug("HOLOGRAM", "No hologram lines configured for zone: " + zoneId);
                return;
            }

            hologramTemplates.put(zoneId.toLowerCase(), new ArrayList<>(template));
            List<String> rendered = renderLines(template, zoneId, IDLE_PLACEHOLDER);
            float scale = (float) hologramsConfig.getDouble("hologram-settings.scale", 1.0);

            if (exists(id)) {
                mUpdateLines.invoke(api, id, rendered);
                mTeleport.invoke(api, id, location);
                Object holo = getHologramObject(id);
                if (holo != null) {
                    applyDisplaySettings(holo, viewDistance, scale);
                }
                activeZones.add(zoneId.toLowerCase());
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "Updated existing AxoHologram for zone: " + zoneId + " (" + template.size() + " lines)");
                return;
            }

            Object holo = mCreateHologram.invoke(api, id, location, rendered, false);
            if (holo != null) {
                applyDisplaySettings(holo, viewDistance, scale);
                activeZones.add(zoneId.toLowerCase());
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "Created new AxoHologram for zone: " + zoneId + " with " + template.size() + " lines");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to create AxoHologram for zone " + zoneId + ": " + t.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "AxoHologram creation error: " + t);
        }
    }

    private void applyDisplaySettings(Object hologram, int viewDistance, float scale) {
        try {
            mSetViewDistance.invoke(hologram, viewDistance);
        } catch (Throwable ignored) {
        }
        try {
            mSetScale.invoke(hologram, scale);
        } catch (Throwable ignored) {
        }
        try {
            mRefreshViewers.invoke(hologram);
        } catch (Throwable ignored) {
        }
    }

    public void updateHologramTime(String zoneId, String time) {
        if (!isAvailable()) return;
        pushLines(zoneId, time);
    }

    public void updateHologramProgress(String zoneId) {
        if (!isAvailable()) return;
        pushLines(zoneId, IDLE_PLACEHOLDER);
    }

    private void pushLines(String zoneId, String timeValue) {
        try {
            String id = hologramId(zoneId);
            if (!exists(id)) {
                if (activeZones.remove(zoneId.toLowerCase())) {
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "AxoHologram for zone " + zoneId + " was removed externally. Clearing cache.");
                }
                return;
            }

            List<String> template = hologramTemplates.get(zoneId.toLowerCase());
            if (template == null || template.isEmpty()) {
                template = loadTemplateLines(zoneId);
                if (template.isEmpty()) return;
                hologramTemplates.put(zoneId.toLowerCase(), new ArrayList<>(template));
            }

            mUpdateLines.invoke(api, id, renderLines(template, zoneId, timeValue));
            activeZones.add(zoneId.toLowerCase());
        } catch (Throwable t) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Failed to update AxoHologram for zone " + zoneId + ": " + t.getMessage());
        }
    }

    public void removeHologram(String zoneId) {
        if (!isAvailable()) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "AxoHologram not available, skipping hologram removal for: " + zoneId);
            return;
        }

        try {
            String id = hologramId(zoneId);
            boolean removed = false;
            if (exists(id)) {
                Object result = mDelete.invoke(api, id);
                removed = (result instanceof Boolean) && (Boolean) result;
            }

            activeZones.remove(zoneId.toLowerCase());
            hologramTemplates.remove(zoneId.toLowerCase());
            idToZone.remove(id);

            if (removed) {
                plugin.getLogger().info("Successfully removed AxoHologram for zone: " + zoneId);
            } else {
                plugin.getRTPLogger().debug("HOLOGRAM", "No AxoHologram found to remove for zone: " + zoneId);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to remove AxoHologram for zone " + zoneId + ": " + t.getMessage());
        }
    }

    public void removeAllHolograms() {
        if (!isAvailable()) return;

        try {
            forEachJustRtpHologram((id, holo) -> {
                try {
                    mDelete.invoke(api, id);
                } catch (Throwable ignored) {
                }
            });
            activeZones.clear();
            hologramTemplates.clear();
            idToZone.clear();
            plugin.getRTPLogger().debug("HOLOGRAM", "Removed all AxoHolograms and cleared template cache");
        } catch (Throwable t) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Failed to remove all AxoHolograms: " + t.getMessage());
        }
    }

    public boolean isHologramActive(String zoneId) {
        if (!isAvailable()) return false;
        try {
            return exists(hologramId(zoneId));
        } catch (Throwable t) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Error checking AxoHologram existence: " + t.getMessage());
            return activeZones.contains(zoneId.toLowerCase());
        }
    }

    public void loadExistingHolograms() {
        if (!isAvailable()) return;

        try {
            int[] loadedCount = {0};
            forEachJustRtpHologram((id, holo) -> {
                String zoneId = idToZone.getOrDefault(id, id.substring(ID_PREFIX.length()));
                activeZones.add(zoneId.toLowerCase());

                List<String> template = loadTemplateLines(zoneId);
                if (!template.isEmpty()) {
                    hologramTemplates.put(zoneId.toLowerCase(), new ArrayList<>(template));
                    try {
                        mUpdateLines.invoke(api, id, renderLines(template, zoneId, IDLE_PLACEHOLDER));
                    } catch (Throwable ignored) {
                    }
                }
                loadedCount[0]++;
            });

            if (loadedCount[0] > 0) {
                plugin.getLogger().info("Loaded " + loadedCount[0]
                        + " existing AxoHologram(s) and applied config templates");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to load existing AxoHolograms: " + t.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "Load AxoHolograms error: " + t);
        }
    }

    public void reloadTemplates() {
        if (!isAvailable()) return;

        plugin.getRTPLogger().debug("HOLOGRAM", "Reloading AxoHologram templates from config...");
        try {
            forEachJustRtpHologram((id, holo) -> {
                String zoneId = idToZone.getOrDefault(id, id.substring(ID_PREFIX.length()));
                List<String> template = loadTemplateLines(zoneId);
                if (!template.isEmpty()) {
                    hologramTemplates.put(zoneId.toLowerCase(), new ArrayList<>(template));
                    try {
                        mUpdateLines.invoke(api, id, renderLines(template, zoneId, IDLE_PLACEHOLDER));
                        plugin.getRTPLogger().debug("HOLOGRAM",
                                "Applied updated config template to AxoHologram for zone: " + zoneId);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Failed to reload AxoHologram templates: " + t.getMessage());
        }
    }

    public void reload() {
        activeZones.clear();
        hologramTemplates.clear();
        initialize();
        loadExistingHolograms();
    }

    public void shutdown() {
        activeZones.clear();
        hologramTemplates.clear();
        idToZone.clear();
        available = false;
    }

    private boolean exists(String id) throws Exception {
        Object result = mExists.invoke(api, id);
        return (result instanceof Boolean) && (Boolean) result;
    }

    private Object getHologramObject(String id) {
        try {
            Object result = mGetHologram.invoke(api, id);
            if (result instanceof Optional<?> opt) {
                return opt.orElse(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void forEachJustRtpHologram(HologramConsumer consumer) throws Exception {
        Object result = mGetHolograms.invoke(api);
        if (!(result instanceof Collection<?> holograms)) return;

        for (Object holo : new ArrayList<>(holograms)) {
            if (holo == null) continue;
            String id;
            try {
                Object rawId = mGetId.invoke(holo);
                id = (rawId == null) ? null : rawId.toString();
            } catch (Throwable t) {
                continue;
            }
            if (id != null && id.startsWith(ID_PREFIX)) {
                consumer.accept(id, holo);
            }
        }
    }

    @FunctionalInterface
    private interface HologramConsumer {
        void accept(String id, Object hologram);
    }

    private List<String> loadTemplateLines(String zoneId) {
        List<String> lines = new ArrayList<>();

        if (hologramsConfig == null) {
            plugin.getRTPLogger().debug("HOLOGRAM", "HologramsConfig is null in AxoHologramManager");
            return lines;
        }

        try {
            String configPath = "zone_holograms." + zoneId + ".lines";
            if (hologramsConfig.contains(configPath)) {
                lines = new ArrayList<>(hologramsConfig.getStringList(configPath));
            } else {
                lines = new ArrayList<>(hologramsConfig.getStringList("hologram-settings.lines"));
            }

            if (lines.isEmpty()) {
                plugin.getLogger().warning("No hologram lines configured for zone: " + zoneId);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load AxoHologram template for zone " + zoneId + ": " + e.getMessage());
        }

        return lines;
    }

    private List<String> renderLines(List<String> template, String zoneId, String timeValue) {
        List<String> out = new ArrayList<>(template.size());
        for (String line : template) {
            String rendered = (line == null) ? "" : line
                    .replace("<time>", timeValue)
                    .replace("<countdown>", timeValue)
                    .replace("<zone_id>", zoneId)
                    .replace("<zone>", zoneId);
            out.add(rendered);
        }
        return out;
    }
}
