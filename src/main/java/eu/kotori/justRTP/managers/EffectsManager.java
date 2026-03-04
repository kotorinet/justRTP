package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EffectsManager {
    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, BossBar> activeBossBars = new HashMap<>();
    private final Map<UUID, List<PotionEffectType>> appliedTransitionEffects = new HashMap<>();

    public EffectsManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getLogger()
                .info("EffectsManager reloaded - new sound, title, and transition_effects settings will apply");
    }

    public void applyPostTeleportEffects(Player player) {
        if (!player.isOnline())
            return;
        removeTransitionEffects(player);
        applyEffects(player, plugin.getConfig().getConfigurationSection("effects"));
        plugin.getAnimationManager().playSuccessAnimation(player);
    }

    public void applyEffects(Player player, ConfigurationSection effectsSection) {
        if (effectsSection == null)
            return;

        applyTitle(player, effectsSection.getConfigurationSection("title"));
        applyActionBar(player, effectsSection.getConfigurationSection("action_bar"));
        applyBossBar(player, effectsSection.getConfigurationSection("boss_bar"));
        applyChatMessage(player, effectsSection.getConfigurationSection("chat_message"));
        applySound(player, effectsSection.getConfigurationSection("sound"));
    }

    public void applyEffects(List<Player> players, ConfigurationSection effectsSection) {
        if (effectsSection == null || players.isEmpty())
            return;
        for (Player player : players) {
            applyEffects(player, effectsSection);
        }
    }

    public void sendQueueActionBar(Player player, String server, int elapsedSeconds, int remainingSeconds,
            int totalSeconds) {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("effects.queue_action_bar");
        if (cs == null || !cs.getBoolean("enabled", false))
            return;
        String format = cs.getString("text", "<yellow>Searching on <server>... (<gold><time>s</gold>)<yellow>");
        if (format.isBlank())
            return;
        player.sendActionBar(mm.deserialize(format,
                Placeholder.unparsed("server", server),
                Placeholder.unparsed("time", eu.kotori.justRTP.utils.TimeUtils.formatDuration(elapsedSeconds)),
                Placeholder.unparsed("elapsed", eu.kotori.justRTP.utils.TimeUtils.formatDuration(elapsedSeconds)),
                Placeholder.unparsed("remaining", eu.kotori.justRTP.utils.TimeUtils.formatDuration(remainingSeconds)),
                Placeholder.unparsed("total", eu.kotori.justRTP.utils.TimeUtils.formatDuration(totalSeconds)),
                Placeholder.unparsed("countdown", eu.kotori.justRTP.utils.TimeUtils.formatDuration(remainingSeconds))));
    }

    public void clearActionBar(Player player) {
        player.sendActionBar(Component.empty());
    }

    public void applyPreTeleportEffects(Player player, int durationInSeconds) {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("effects.transition_effects");
        if (cs == null || !cs.getBoolean("enabled", true) || durationInSeconds <= 0)
            return;
        List<PotionEffectType> appliedEffects = new ArrayList<>();
        for (String effectString : cs.getStringList("effects")) {
            try {
                String[] parts = effectString.split(":");
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(parts[0].toLowerCase()));
                int amplifier = Integer.parseInt(parts[1]);
                if (type != null) {
                    player.addPotionEffect(
                            new PotionEffect(type, durationInSeconds * 20 + 10, amplifier, false, false));
                    appliedEffects.add(type);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid transition effect format: " + effectString);
            }
        }
        if (!appliedEffects.isEmpty())
            appliedTransitionEffects.put(player.getUniqueId(), appliedEffects);
    }

    public void removeTransitionEffects(Player player) {
        if (appliedTransitionEffects.containsKey(player.getUniqueId())) {
            appliedTransitionEffects.get(player.getUniqueId()).forEach(player::removePotionEffect);
            appliedTransitionEffects.remove(player.getUniqueId());
        }
    }

    private void applyTitle(Player player, ConfigurationSection cs) {
        if (cs == null || !cs.getBoolean("enabled", false))
            return;
        String mainTitleStr = cs.getString("main_title", "");
        String subtitleStr = cs.getString("subtitle", "");
        if (mainTitleStr.isBlank() && subtitleStr.isBlank())
            return;

        Component mainTitle = mm.deserialize(mainTitleStr);
        Component subtitle = mm.deserialize(subtitleStr);
        long fadeIn = cs.getLong("fade_in", 10);
        long stay = cs.getLong("stay", 40);
        long fadeOut = cs.getLong("fade_out", 10);
        Title.Times times = Title.Times.times(Duration.ofMillis(fadeIn * 50), Duration.ofMillis(stay * 50),
                Duration.ofMillis(fadeOut * 50));
        Title title = Title.title(mainTitle, subtitle, times);
        player.showTitle(title);
    }

    private void applyActionBar(Player player, ConfigurationSection cs) {
        if (cs == null || !cs.getBoolean("enabled", false))
            return;
        String text = cs.getString("text", "");
        if (text.isBlank())
            return;
        player.sendActionBar(mm.deserialize(text));
    }

    private void applyBossBar(Player player, ConfigurationSection cs) {
        if (cs == null || !cs.getBoolean("enabled", false))
            return;
        String textStr = cs.getString("text", "");
        if (textStr.isBlank())
            return;
        removeBossBar(player);
        try {
            Component text = mm.deserialize(textStr);
            BossBar.Color color = BossBar.Color.valueOf(cs.getString("color", "WHITE").toUpperCase());
            BossBar.Overlay style = BossBar.Overlay.valueOf(cs.getString("style", "PROGRESS").toUpperCase());
            BossBar bossBar = BossBar.bossBar(text, 1.0f, color, style);
            activeBossBars.put(player.getUniqueId(), bossBar);
            player.showBossBar(bossBar);
            plugin.getFoliaScheduler().runLater(() -> removeBossBar(player), cs.getLong("duration", 5) * 20L);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid BossBar config: " + e.getMessage());
        }
    }

    private void applyChatMessage(Player player, ConfigurationSection cs) {
        if (cs == null || !cs.getBoolean("enabled", false))
            return;

        List<String> messages = cs.getStringList("messages");
        if (messages.isEmpty())
            return;

        Location loc = player.getLocation();
        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("player", player.getName()))
                .resolver(Placeholder.unparsed("x", String.valueOf(loc.getBlockX())))
                .resolver(Placeholder.unparsed("y", String.valueOf(loc.getBlockY())))
                .resolver(Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())))
                .resolver(Placeholder.unparsed("world", loc.getWorld().getName()))
                .build();

        for (String message : messages) {
            if (message.isBlank()) {
                continue;
            }
            player.sendMessage(mm.deserialize(message, placeholders));
        }
    }

    private void applySound(Player player, ConfigurationSection cs) {
        if (cs == null || !cs.getBoolean("enabled", false))
            return;
        String configSoundName = cs.getString("name", "ENTITY_PLAYER_LEVEL_UP");
        if (configSoundName == null || configSoundName.trim().isEmpty()) {
            configSoundName = "ENTITY_PLAYER_LEVEL_UP";
        }
        String soundName = configSoundName.trim().toUpperCase();
        Sound sound = null;

        java.util.List<String> soundVariants = new java.util.ArrayList<>();
        soundVariants.add(soundName);

        String noUnderscores = soundName.replace("_", "");
        soundVariants.add(noUnderscores);

        soundVariants.add(noUnderscores.replaceAll("(?<!^)(?=[A-Z])", "_"));

        if (soundName.contains("_")) {
            String[] parts = soundName.split("_");

            if (parts.length >= 2) {
                StringBuilder merged = new StringBuilder();
                for (int i = 0; i < parts.length - 2; i++) {
                    if (i > 0)
                        merged.append("_");
                    merged.append(parts[i]);
                }
                if (parts.length > 2)
                    merged.append("_");
                merged.append(parts[parts.length - 2]).append(parts[parts.length - 1]);
                soundVariants.add(merged.toString());
            }

            String lastPart = parts[parts.length - 1];
            String smartSplit = splitCompoundWord(lastPart);
            if (!smartSplit.equals(lastPart)) {
                StringBuilder withSplit = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0)
                        withSplit.append("_");
                    withSplit.append(parts[i]);
                }
                if (parts.length > 1)
                    withSplit.append("_");
                withSplit.append(smartSplit);
                soundVariants.add(withSplit.toString());
            }
        }

        plugin.getRTPLogger().debug("EFFECTS",
                "Attempting to find sound '" + configSoundName + "', trying " + soundVariants.size() + " variants");

        for (String variant : soundVariants) {
            if (sound != null)
                break;

            try {
                sound = Sound.valueOf(variant);
                if (sound != null) {
                    plugin.getRTPLogger().debug("EFFECTS", "Found sound '" + variant + "' via Sound.valueOf()");
                }
            } catch (IllegalArgumentException e) {
            }

            if (sound == null) {
                try {
                    String registryKey = variant.toLowerCase().replace("_", ".");
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(registryKey);
                    sound = org.bukkit.Registry.SOUNDS.get(key);
                    if (sound != null) {
                        plugin.getRTPLogger().debug("EFFECTS",
                                "Found sound '" + variant + "' via Registry API (key: " + registryKey + ")");
                    }
                } catch (Throwable e) {
                }
            }

            if (sound == null) {
                try {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(variant.toLowerCase());
                    sound = org.bukkit.Registry.SOUNDS.get(key);
                    if (sound != null) {
                        plugin.getRTPLogger().debug("EFFECTS",
                                "Found sound '" + variant + "' via Registry API (underscore key)");
                    }
                } catch (Throwable e) {
                }
            }
        }

        if (sound != null) {
            try {
                float volume = (float) cs.getDouble("volume", 1.0);
                float pitch = (float) cs.getDouble("pitch", 1.2);
                player.playSound(player.getLocation(), sound, volume, pitch);
                plugin.getRTPLogger().debug("EFFECTS", "Successfully played sound: " + sound);
            } catch (Exception e) {
                plugin.getLogger().warning("Error playing sound: " + soundName + " - " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("Invalid Sound name in config.yml: " + configSoundName + " (tried variants: "
                    + soundVariants + ")");
        }
    }

    private String splitCompoundWord(String word) {
        String[] commonSuffixes = { "UP", "DOWN", "IN", "OUT", "ON", "OFF", "ORB" };
        for (String suffix : commonSuffixes) {
            if (word.endsWith(suffix) && word.length() > suffix.length()) {
                return word.substring(0, word.length() - suffix.length()) + "_" + suffix;
            }
        }
        return word;
    }

    public void sendDelayActionBar(Player player, int remainingSeconds) {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("effects.teleport_delay_bar");
        if (cs == null || !cs.getBoolean("enabled", true))
            return;

        String translatedMessage = plugin.getLocaleManager().getRawMessage("teleport.delay");

        if (translatedMessage == null || translatedMessage.isEmpty()) {
            translatedMessage = cs.getString("text", "<yellow>Teleporting in <gold><time>s<yellow>... Don't move!");
        }

        if (translatedMessage.isBlank())
            return;

        String prefix = plugin.getLocaleManager().getRawMessage("prefix", "");
        translatedMessage = translatedMessage.replace("%prefix%", prefix);

        player.sendActionBar(mm.deserialize(translatedMessage,
                Placeholder.unparsed("time", String.valueOf(remainingSeconds))));
    }

    public void playDelaySound(Player player, int remainingSeconds, int totalSeconds) {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("effects.teleport_delay_sound");
        if (cs == null || !cs.getBoolean("enabled", true))
            return;

        float baseVolume = (float) cs.getDouble("volume", 1.0);
        float basePitch = (float) cs.getDouble("pitch", 1.0);
        boolean dynamicPitchEnabled = cs.getBoolean("dynamic_pitch", true);

        float finalPitch = basePitch;
        if (dynamicPitchEnabled && totalSeconds > 0) {
            float progress = 1.0f - ((float) remainingSeconds / totalSeconds);
            finalPitch = basePitch + (progress * 0.4f);
        }

        String configSoundName = cs.getString("name", "BLOCK_NOTE_BLOCK_PLING");
        Sound sound = null;
        try {
            sound = Sound.valueOf(configSoundName.toUpperCase());
        } catch (Exception ignored) {
        }

        if (sound != null) {
            player.playSound(player.getLocation(), sound, baseVolume, finalPitch);
        }
    }

    public void removeBossBar(Player player) {
        if (activeBossBars.containsKey(player.getUniqueId())) {
            player.hideBossBar(activeBossBars.get(player.getUniqueId()));
            activeBossBars.remove(player.getUniqueId());
        }
    }

    public void removeAllBossBars() {
        activeBossBars.forEach((uuid, bossBar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null)
                player.hideBossBar(bossBar);
        });
        activeBossBars.clear();
    }
}