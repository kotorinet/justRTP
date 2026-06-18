package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.task.CancellableTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AnimationManager {

    private record AnimationStep(Particle particle, String shape, int count, double radius, Vector offset) {}
    private record Animation(String name, List<AnimationStep> steps) {}

    private final JustRTP plugin;
    private final Map<String, Animation> loadedAnimations = new ConcurrentHashMap<>();
    private final Map<UUID, CancellableTask> activeDelayAnimations = new ConcurrentHashMap<>();

    public AnimationManager(JustRTP plugin) {
        this.plugin = plugin;
        loadAnimations();
    }

    public void reload() {
        loadedAnimations.clear();
        loadAnimations();
    }

    private void loadAnimations() {
        File animFile = new File(plugin.getDataFolder(), "animations.yml");
        if (!animFile.exists()) {
            plugin.saveResource("animations.yml", false);
        }
        FileConfiguration animConfig = YamlConfiguration.loadConfiguration(animFile);
        ConfigurationSection animationsSection = animConfig.getConfigurationSection("animations");
        if (animationsSection == null) return;

        for (String key : animationsSection.getKeys(false)) {
            List<AnimationStep> steps = new ArrayList<>();
            List<Map<?, ?>> stepsList = animationsSection.getMapList(key + ".steps");
            for (Map<?, ?> stepMap : stepsList) {
                try {
                    Object particleObj = stepMap.get("particle");
                    Particle particle = (particleObj instanceof String) ? Particle.valueOf(((String) particleObj).toUpperCase()) : Particle.FLAME;

                    Object shapeObj = stepMap.get("shape");
                    String shape = (shapeObj instanceof String) ? (String) shapeObj : "POINT";

                    Object countObj = stepMap.get("count");
                    int count = (countObj instanceof Number) ? ((Number) countObj).intValue() : 1;

                    Object radiusObj = stepMap.get("radius");
                    double radius = (radiusObj instanceof Number) ? ((Number) radiusObj).doubleValue() : 1.0;

                    Vector offset = new Vector(0, 0, 0);
                    if (stepMap.get("offset") instanceof Map) {
                        Map<?, ?> offsetMap = (Map<?, ?>) stepMap.get("offset");
                        Object xObj = offsetMap.get("x");
                        Object yObj = offsetMap.get("y");
                        Object zObj = offsetMap.get("z");
                        double x = (xObj instanceof Number) ? ((Number) xObj).doubleValue() : 0.0;
                        double y = (yObj instanceof Number) ? ((Number) yObj).doubleValue() : 0.0;
                        double z = (zObj instanceof Number) ? ((Number) zObj).doubleValue() : 0.0;
                        offset = new Vector(x, y, z);
                    }

                    steps.add(new AnimationStep(particle, shape, count, radius, offset));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load a step for animation '" + key + "': " + e.getMessage());
                }
            }
            loadedAnimations.put(key, new Animation(key, steps));
        }
        plugin.getLogger().info("Loaded " + loadedAnimations.size() + " particle animations.");
    }

    public void playDelayAnimation(Player player, int durationSeconds) {
        String animationName = plugin.getConfig().getString("animations.delay_animation");
        if (animationName == null || animationName.isEmpty() || !loadedAnimations.containsKey(animationName)) return;

        Animation animation = loadedAnimations.get(animationName);
        if (animation.steps().isEmpty()) return;

        stopDelayAnimation(player);

        AtomicInteger tick = new AtomicInteger(0);
        int durationTicks = durationSeconds * 20;

        CancellableTask task = plugin.getFoliaScheduler().runTimer(() -> {
            if (!player.isOnline() || tick.get() >= durationTicks) {
                stopDelayAnimation(player);
                return;
            }
            AnimationStep step = animation.steps().get(tick.getAndIncrement() % animation.steps().size());
            spawnParticles(player, step);
        }, 0L, 2L);
        activeDelayAnimations.put(player.getUniqueId(), task);
    }

    public void stopDelayAnimation(Player player) {
        CancellableTask existingTask = activeDelayAnimations.remove(player.getUniqueId());
        if (existingTask != null && !existingTask.isCancelled()) {
            existingTask.cancel();
        }
    }

    public void playSuccessAnimation(Player player) {
        String animationName = plugin.getConfig().getString("animations.success_animation");
        if (animationName == null || animationName.isEmpty() || !loadedAnimations.containsKey(animationName)) return;

        Animation animation = loadedAnimations.get(animationName);
        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            for (AnimationStep step : animation.steps()) {
                spawnParticles(player, step);
            }
        });
    }

    private void spawnParticles(Player player, AnimationStep step) {
        Location center = player.getLocation().add(step.offset());
        switch (step.shape().toUpperCase()) {
            case "CIRCLE":
                for (int i = 0; i < step.count(); i++) {
                    double angle = 2 * Math.PI * i / step.count();
                    double x = center.getX() + step.radius() * Math.cos(angle);
                    double z = center.getZ() + step.radius() * Math.sin(angle);
                    Location loc = new Location(center.getWorld(), x, center.getY(), z);
                    player.spawnParticle(step.particle(), loc, 1, 0, 0, 0, 0);
                }
                break;
            case "SPHERE":
                for (int i = 0; i < step.count(); i++) {
                    double u = Math.random();
                    double v = Math.random();
                    double theta = 2 * Math.PI * u;
                    double phi = Math.acos(2 * v - 1);
                    double x = center.getX() + step.radius() * Math.sin(phi) * Math.cos(theta);
                    double y = center.getY() + step.radius() * Math.sin(phi) * Math.sin(theta);
                    double z = center.getZ() + step.radius() * Math.cos(phi);
                    player.spawnParticle(step.particle, x, y, z, 1, 0, 0, 0, 0);
                }
                break;
            case "POINT":
            default:
                player.spawnParticle(step.particle(), center, step.count(), 0.5, 0.5, 0.5, 0.05);
                break;
        }
    }
}
