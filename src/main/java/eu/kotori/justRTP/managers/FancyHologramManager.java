package eu.kotori.justRTP.managers;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FancyHologramManager {
    private final JustRTP plugin;
    private FancyHologramsPlugin fancyHologramsPlugin;
    private de.oliver.fancyholograms.api.HologramManager hologramManager;
    private final Map<String, Hologram> activeHolograms = new ConcurrentHashMap<>();
    private final Map<String, List<String>> hologramTemplates = new ConcurrentHashMap<>();
    private FileConfiguration hologramsConfig;
    private boolean available = false;

    private static final Pattern TIME_PATTERN = Pattern.compile("<time>(.*?)</time>(s?)");
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile("<countdown>(.*?)</countdown>(s?)");
    private boolean usePapi = false;

    public FancyHologramManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
                this.fancyHologramsPlugin = (FancyHologramsPlugin) Bukkit.getPluginManager()
                        .getPlugin("FancyHolograms");
                if (fancyHologramsPlugin != null) {
                    this.hologramManager = fancyHologramsPlugin.getHologramManager();
                    this.available = true;
                    plugin.getLogger().info("FancyHolograms detected! Using FancyHolograms for zone holograms.");
                }
            }

            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                this.usePapi = true;
                plugin.getLogger().info("PlaceholderAPI detected! Using PAPI placeholders for hologram countdowns.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize FancyHolograms support: " + e.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "FancyHolograms error: " + e.toString());
            this.available = false;
        }
    }

    public void setHologramsConfig(FileConfiguration hologramsConfig) {
        this.hologramsConfig = hologramsConfig;
    }

    public boolean isAvailable() {
        return available && hologramManager != null;
    }

    public void refreshForPlayer(org.bukkit.entity.Player player) {
        if (!isAvailable() || player == null || !player.isOnline()) return;

        try {
            for (Map.Entry<String, Hologram> entry : activeHolograms.entrySet()) {
                Hologram hologram = entry.getValue();
                if (hologram != null) {
                    hologram.forceUpdate();
                    hologram.showHologram(Bukkit.getOnlinePlayers());
                }
            }
        } catch (Exception e) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Error refreshing FancyHolograms for player " + player.getName() + ": " + e.getMessage());
        }
    }

    public void createOrUpdateHologram(String zoneId, Location location, int viewDistance) {
        if (!isAvailable())
            return;

        try {
            String hologramName = "justrtp_zone_" + zoneId;

            List<String> templateLines = loadTemplateLines(zoneId);
            if (templateLines.isEmpty()) {
                plugin.getRTPLogger().debug("HOLOGRAM", "No hologram lines configured for zone: " + zoneId);
                return;
            }

            hologramTemplates.put(zoneId, new ArrayList<>(templateLines));
            List<String> processedLines = applyPlaceholders(templateLines, zoneId, "⏳");

            Hologram existingHologram = hologramManager.getHologram(hologramName).orElse(null);
            if (existingHologram != null) {

                activeHolograms.put(zoneId, existingHologram);

                if (existingHologram.getData() instanceof TextHologramData textData) {
                    textData.setText(processedLines);
                    textData.setVisibilityDistance(viewDistance);
                }

                existingHologram.forceUpdate();
                existingHologram.showHologram(Bukkit.getOnlinePlayers());

                plugin.getRTPLogger().debug("HOLOGRAM",
                        "Updated existing FancyHologram for zone: " + zoneId + " with config template ("
                                + templateLines.size() + " lines)");
                return;
            }

            TextHologramData data = new TextHologramData(hologramName, location);
            data.setText(processedLines);
            data.setVisibilityDistance(viewDistance);
            data.setPersistent(true);
            data.setTextUpdateInterval(1);

            Hologram hologram = hologramManager.create(data);
            if (hologram != null) {
                hologram.createHologram();
                hologram.showHologram(Bukkit.getOnlinePlayers());

                hologramManager.addHologram(hologram);

                plugin.getFoliaScheduler().runLater(() -> {
                    hologram.forceUpdate();
                }, 1L);

                activeHolograms.put(zoneId, hologram);
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "Created new FancyHologram for zone: " + zoneId + " with " + templateLines.size() + " lines");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create FancyHologram for zone " + zoneId + ": " + e.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "Hologram creation error: " + e.toString());
        }
    }

    public void updateHologramTime(String zoneId, String time) {
        if (!isAvailable())
            return;

        try {
            String hologramName = "justrtp_zone_" + zoneId;

            Hologram hologram = hologramManager.getHologram(hologramName).orElse(null);

            if (hologram == null) {
                if (activeHolograms.containsKey(zoneId)) {
                    activeHolograms.remove(zoneId);
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Hologram for zone " + zoneId + " was removed externally. Clearing cache.");
                }
                return;
            }

            activeHolograms.put(zoneId, hologram);

            if (!(hologram.getData() instanceof TextHologramData)) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Hologram for zone " + zoneId + " is not a TextHologram");

                return;
            }

            TextHologramData textData = (TextHologramData) hologram.getData();
            List<String> currentLines = textData.getText();
            if (currentLines == null || currentLines.isEmpty()) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Hologram for zone " + zoneId + " has no lines");

                return;
            }

            String cleanTime = time.endsWith("s") ? time.substring(0, time.length() - 1) : time;

            List<String> updatedLines = new ArrayList<>();
            boolean wasUpdated = false;

            for (String line : currentLines) {
                String updatedLine = line;
                String originalLine = line;

                if (usePapi) {
                    String papiPlaceholder = "%justrtp_zone_time_" + zoneId + "%";

                    if (updatedLine.contains("<time>") && updatedLine.contains("</time>")) {
                        Matcher m = TIME_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String suffix = m.group(2);
                            String content = m.group(1);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = papiPlaceholder + (hasInnerS ? "s" : "") + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }

                    if (updatedLine.contains("<countdown>") && updatedLine.contains("</countdown>")) {
                        Matcher m = COUNTDOWN_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String suffix = m.group(2);
                            String content = m.group(1);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = papiPlaceholder + (hasInnerS ? "s" : "") + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }

                    if (updatedLine.contains("⏳")) {
                        updatedLine = updatedLine.replace("⏳", papiPlaceholder);
                    }

                } else {
                    if (updatedLine.contains("<time>") && updatedLine.contains("</time>")) {
                        Matcher m = TIME_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String content = m.group(1);
                            String suffix = m.group(2);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = "<time>" + cleanTime + (hasInnerS ? "s" : "") + "</time>" + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }

                    if (updatedLine.contains("<countdown>") && updatedLine.contains("</countdown>")) {
                        Matcher m = COUNTDOWN_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String content = m.group(1);
                            String suffix = m.group(2);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = "<countdown>" + cleanTime + (hasInnerS ? "s" : "") + "</countdown>"
                                    + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }

                    if (updatedLine.contains("⏳s")) {
                        updatedLine = updatedLine.replace("⏳s", "<time>" + cleanTime + "</time>s");
                    }
                }

                if (!updatedLine.equals(originalLine)) {
                    wasUpdated = true;
                    if (cleanTime.equals("10") || cleanTime.equals("5") || cleanTime.equals("1")) {
                        plugin.getRTPLogger().debug("HOLOGRAM", "Updated line for zone " + zoneId + ": '" + originalLine
                                + "' -> '" + updatedLine + "'");
                    }
                }

                updatedLines.add(updatedLine);
            }

            if (wasUpdated) {
                textData.setText(updatedLines);
                hologram.forceUpdate();

                if (cleanTime.equals("10") || cleanTime.equals("5") || cleanTime.equals("1")
                        || cleanTime.equals("20")) {
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Updated FancyHologram time for zone: " + zoneId + " - " + cleanTime + "s");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update hologram time for zone " + zoneId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateHologramProgress(String zoneId) {
        if (!isAvailable())
            return;

        try {
            String hologramName = "justrtp_zone_" + zoneId;

            Hologram hologram = hologramManager.getHologram(hologramName).orElse(null);

            if (hologram == null) {
                if (activeHolograms.containsKey(zoneId)) {
                    activeHolograms.remove(zoneId);
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Hologram for zone " + zoneId + " was removed externally. Clearing cache.");

                }
                return;
            }

            activeHolograms.put(zoneId, hologram);

            if (!(hologram.getData() instanceof TextHologramData)) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Hologram for zone " + zoneId + " is not a TextHologram");

                return;
            }

            TextHologramData textData = (TextHologramData) hologram.getData();
            List<String> currentLines = textData.getText();
            if (currentLines == null || currentLines.isEmpty()) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Hologram for zone " + zoneId + " has no lines");

                return;
            }

            List<String> updatedLines = new ArrayList<>();
            boolean wasUpdated = false;

            for (String line : currentLines) {
                String updatedLine = line;
                String originalLine = line;

                if (usePapi) {
                    String papiPlaceholder = "%justrtp_zone_time_" + zoneId + "%";
                    if (updatedLine.contains(papiPlaceholder)) {
                        updatedLine = updatedLine.replace(papiPlaceholder, "⏳");
                    }

                    if (updatedLine.contains("<time>") && updatedLine.contains("</time>")) {
                        Matcher m = TIME_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String suffix = m.group(2);
                            String content = m.group(1);
                            boolean hasInnerS = content.endsWith("s");
                            String replacement = "⏳" + (hasInnerS ? "s" : "") + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }
                } else {

                    if (updatedLine.contains("<time>") && updatedLine.contains("</time>")) {
                        Matcher m = TIME_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String content = m.group(1);
                            String suffix = m.group(2);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = "<time>⏳" + (hasInnerS ? "s" : "") + "</time>" + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }

                    if (updatedLine.contains("<countdown>") && updatedLine.contains("</countdown>")) {
                        Matcher m = COUNTDOWN_PATTERN.matcher(updatedLine);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) {
                            String content = m.group(1);
                            String suffix = m.group(2);
                            boolean hasInnerS = content.endsWith("s");

                            String replacement = "<countdown>⏳" + (hasInnerS ? "s" : "") + "</countdown>" + suffix;
                            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        }
                        m.appendTail(sb);
                        updatedLine = sb.toString();
                    }
                }

                if (!updatedLine.equals(originalLine)) {
                    wasUpdated = true;
                }

                updatedLines.add(updatedLine);
            }

            if (wasUpdated) {
                textData.setText(updatedLines);
                hologram.forceUpdate();
                plugin.getRTPLogger().debug("HOLOGRAM", "Updated FancyHologram progress for zone: " + zoneId);
            }
        } catch (Exception e) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Failed to update hologram progress for zone " + zoneId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeHologram(String zoneId) {
        if (!isAvailable()) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "FancyHolograms not available, skipping hologram removal for: " + zoneId);
            return;
        }

        try {
            String hologramName = "justrtp_zone_" + zoneId;
            boolean removed = false;

            Hologram hologram = activeHolograms.remove(zoneId);
            if (hologram != null) {
                try {
                    hologram.hideHologram(Bukkit.getOnlinePlayers());
                    hologram.deleteHologram();
                    hologramManager.removeHologram(hologram);
                    removed = true;
                    plugin.getRTPLogger().debug("HOLOGRAM", "✓ Removed active FancyHologram for zone: " + zoneId);
                } catch (Exception e) {
                    plugin.getRTPLogger().debug("HOLOGRAM", "Error removing active hologram: " + e.getMessage());
                }
            }

            try {
                Hologram persistentHologram = hologramManager.getHologram(hologramName).orElse(null);
                if (persistentHologram != null) {
                    persistentHologram.hideHologram(Bukkit.getOnlinePlayers());
                    persistentHologram.deleteHologram();
                    hologramManager.removeHologram(persistentHologram);
                    removed = true;
                    plugin.getRTPLogger().debug("HOLOGRAM", "✓ Removed persistent FancyHologram: " + hologramName);
                }
            } catch (Exception e) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Error checking persistent hologram: " + e.getMessage());
            }

            hologramTemplates.remove(zoneId);

            if (removed) {
                plugin.getLogger().info("Successfully removed FancyHologram for zone: " + zoneId);
            } else {
                plugin.getRTPLogger().debug("HOLOGRAM", "No FancyHologram found to remove for zone: " + zoneId);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove hologram for zone " + zoneId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeAllHolograms() {
        if (!isAvailable())
            return;

        try {
            for (String zoneId : new ArrayList<>(activeHolograms.keySet())) {
                removeHologram(zoneId);
            }
            activeHolograms.clear();
            hologramTemplates.clear();
            plugin.getRTPLogger().debug("HOLOGRAM", "Removed all FancyHolograms and cleared template cache");
        } catch (Exception e) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Failed to remove all holograms: " + e.getMessage());
        }
    }

    public void reload() {
        activeHolograms.clear();
        hologramTemplates.clear();
        initialize();
        loadExistingHolograms();
    }

    public void loadExistingHolograms() {
        if (!isAvailable())
            return;

        try {
            Collection<Hologram> allHolograms = hologramManager.getHolograms();
            int loadedCount = 0;

            for (Hologram hologram : allHolograms) {
                String hologramName = hologram.getData().getName();
                if (hologramName.startsWith("justrtp_zone_")) {
                    String zoneId = hologramName.substring("justrtp_zone_".length());
                    activeHolograms.put(zoneId, hologram);

                    List<String> templateLines = loadTemplateLines(zoneId);
                    if (!templateLines.isEmpty()) {
                        hologramTemplates.put(zoneId, new ArrayList<>(templateLines));
                        List<String> processedLines = applyPlaceholders(templateLines, zoneId, "⏳");

                        if (hologram.getData() instanceof TextHologramData textData) {
                            textData.setText(processedLines);
                            hologram.forceUpdate();
                        }

                        plugin.getRTPLogger().debug("HOLOGRAM",
                                "Loaded hologram for zone: " + zoneId + " and applied config template ("
                                        + templateLines.size() + " lines)");
                    }

                    loadedCount++;
                }
            }

            if (loadedCount > 0) {
                plugin.getLogger().info("Loaded " + loadedCount
                        + " existing FancyHologram(s) and applied config templates");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load existing holograms: " + e.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "Load holograms error: " + e.toString());
        }
    }

    public void shutdown() {
        activeHolograms.clear();
        hologramTemplates.clear();
        available = false;
    }

    public boolean isHologramActive(String zoneId) {
        if (!isAvailable())
            return false;

        if (activeHolograms.containsKey(zoneId)) {
            return true;
        }

        String hologramName = "justrtp_zone_" + zoneId;
        try {
            Hologram hologram = hologramManager.getHologram(hologramName).orElse(null);
            if (hologram != null) {
                activeHolograms.put(zoneId, hologram);
                plugin.getRTPLogger().debug("HOLOGRAM", "Re-discovered FancyHologram for zone: " + zoneId);
                return true;
            }
        } catch (Exception e) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Error checking hologram existence: " + e.getMessage());
        }

        return false;
    }

    public void reloadTemplates() {
        plugin.getRTPLogger().debug("HOLOGRAM", "Reloading hologram templates from config...");
        refreshHologramReferences();

        for (Map.Entry<String, Hologram> entry : activeHolograms.entrySet()) {
            String zoneId = entry.getKey();
            Hologram hologram = entry.getValue();

            List<String> templateLines = loadTemplateLines(zoneId);
            if (!templateLines.isEmpty()) {
                hologramTemplates.put(zoneId, new ArrayList<>(templateLines));
                List<String> processedLines = applyPlaceholders(templateLines, zoneId, "⏳");

                if (hologram.getData() instanceof TextHologramData textData) {
                    textData.setText(processedLines);
                    hologram.forceUpdate();
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Applied updated config template to hologram for zone: " + zoneId);
                }
            }
        }
    }

    private void refreshHologramReferences() {
        if (!isAvailable())
            return;

        try {
            Collection<Hologram> allHolograms = hologramManager.getHolograms();

            for (Hologram hologram : allHolograms) {
                String hologramName = hologram.getData().getName();
                if (hologramName.startsWith("justrtp_zone_")) {
                    String zoneId = hologramName.substring("justrtp_zone_".length());

                    activeHolograms.put(zoneId, hologram);

                    if (hologram.getData() instanceof TextHologramData) {
                        TextHologramData textData = (TextHologramData) hologram.getData();
                        List<String> currentLines = textData.getText();
                        if (!currentLines.isEmpty()) {
                            hologramTemplates.put(zoneId, new ArrayList<>(currentLines));
                        }
                    }
                }
            }

            plugin.getRTPLogger().debug("HOLOGRAM", "Refreshed " + activeHolograms.size() + " hologram reference(s)");
        } catch (Exception e) {
            plugin.getRTPLogger().debug("HOLOGRAM", "Failed to refresh hologram references: " + e.getMessage());
        }
    }

    private List<String> loadTemplateLines(String zoneId) {
        List<String> lines = new ArrayList<>();

        if (hologramsConfig == null) {
            plugin.getRTPLogger().debug("HOLOGRAM", "HologramsConfig is null in FancyHologramManager");
            return lines;
        }

        try {
            String configPath = "zone_holograms." + zoneId + ".lines";
            if (hologramsConfig.contains(configPath)) {
                lines = new ArrayList<>(hologramsConfig.getStringList(configPath));
                plugin.getRTPLogger().debug("HOLOGRAM", "Loaded zone-specific template for: " + zoneId);
            } else {
                lines = new ArrayList<>(hologramsConfig.getStringList("hologram-settings.lines"));
                plugin.getRTPLogger().debug("HOLOGRAM", "Loaded default template for zone: " + zoneId);
            }

            if (lines.isEmpty()) {
                plugin.getLogger().warning("No hologram lines configured for zone: " + zoneId);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load hologram template for zone " + zoneId + ": " + e.getMessage());
        }

        return lines;
    }

    private List<String> applyPlaceholders(List<String> templateLines, String zoneId, String time) {
        String cleanTime = time.equals("⏳") ? "⏳" : (time.endsWith("s") ? time.substring(0, time.length() - 1) : time);

        String timeReplacement;
        String countdownReplacement;

        if (usePapi) {
            String placeholder = "%justrtp_zone_time_" + zoneId + "%";
            timeReplacement = placeholder;
            countdownReplacement = placeholder;
        } else {
            timeReplacement = "<time>" + cleanTime + "</time>";
            countdownReplacement = "<countdown>" + cleanTime + "</countdown>";
        }

        return templateLines.stream()
                .map(line -> line
                        .replace("<zone>", zoneId)
                        .replace("<zone_id>", zoneId)
                        .replace("<time>", timeReplacement)
                        .replace("<countdown>", countdownReplacement))
                .collect(Collectors.toList());
    }
}
