package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.task.CancellableTask;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RTPMatchmakingManager {

    private record MatchmakingRequest(Player player, World world, long timestamp, Optional<Integer> minRadius,
                                     Optional<Integer> maxRadius) {}

    private final JustRTP plugin;
    private final Map<World, List<MatchmakingRequest>> worldQueues = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queueTimestamps = new ConcurrentHashMap<>();
    private CancellableTask tickerTask;

    public RTPMatchmakingManager(JustRTP plugin) {
        this.plugin = plugin;
        startMatchmakingTicker();
    }

    public void stop() {
        if (tickerTask != null && !tickerTask.isCancelled()) {
            tickerTask.cancel();
        }
        tickerTask = null;
    }

    public void restart() {
        stop();
        startMatchmakingTicker();
    }

    public boolean joinQueue(Player player, World world, Optional<Integer> minRadius, Optional<Integer> maxRadius) {
        if (!isEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.disabled");
            return false;
        }

        if (isInQueue(player)) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.already_in_queue");
            return false;
        }

        List<MatchmakingRequest> queue = worldQueues.computeIfAbsent(world, k -> Collections.synchronizedList(new ArrayList<>()));
        int queueSize;
        synchronized (queue) {
            queue.add(new MatchmakingRequest(player, world, System.currentTimeMillis(), minRadius, maxRadius));
            queueTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
            queueSize = queue.size();
        }

        plugin.getLocaleManager().sendMessage(player, "matchmaking.joined_queue",
                Placeholder.unparsed("world", world.getName()),
                Placeholder.unparsed("players", String.valueOf(queueSize)));

        plugin.getRTPLogger().debug("MATCHMAKING",
                player.getName() + " joined matchmaking queue for " + world.getName() + " (queue size: " + queueSize + ")");

        return true;
    }

    public boolean leaveQueue(Player player) {
        if (!isInQueue(player)) {
            plugin.getLocaleManager().sendMessage(player, "matchmaking.not_in_queue");
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        boolean removed = false;

        for (Map.Entry<World, List<MatchmakingRequest>> entry : worldQueues.entrySet()) {
            List<MatchmakingRequest> queue = entry.getValue();
            synchronized (queue) {
                removed = queue.removeIf(req -> req.player().getUniqueId().equals(playerUUID));
                if (removed) {
                    plugin.getLocaleManager().sendMessage(player, "matchmaking.left_queue");
                    plugin.getRTPLogger().debug("MATCHMAKING",
                            player.getName() + " left matchmaking queue for " + entry.getKey().getName());
                    break;
                }
            }
        }

        queueTimestamps.remove(playerUUID);
        return removed;
    }

    public boolean isInQueue(Player player) {
        UUID playerUUID = player.getUniqueId();
        return worldQueues.values().stream()
                .anyMatch(queue -> queue.stream().anyMatch(req -> req.player().getUniqueId().equals(playerUUID)));
    }

    public int getQueueSize(World world) {
        List<MatchmakingRequest> queue = worldQueues.get(world);
        return queue != null ? queue.size() : 0;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("matchmaking.enabled", true);
    }

    private void startMatchmakingTicker() {
        int tickInterval = Math.max(1, plugin.getConfig().getInt("matchmaking.tick_interval", 60));

        tickerTask = plugin.getFoliaScheduler().runTimer(() -> {
            if (!isEnabled()) return;

            for (Map.Entry<World, List<MatchmakingRequest>> entry : worldQueues.entrySet()) {
                World world = entry.getKey();
                List<MatchmakingRequest> queue = entry.getValue();

                if (queue.size() < 2) continue;

                synchronized (queue) {

                    long currentTime = System.currentTimeMillis();
                    long timeout = plugin.getConfig().getLong("matchmaking.queue_timeout", 300) * 1000;

                    queue.removeIf(req -> {
                        if (!req.player().isOnline()) {
                            queueTimestamps.remove(req.player().getUniqueId());
                            return true;
                        }
                        if ((currentTime - req.timestamp()) > timeout) {
                            plugin.getLocaleManager().sendMessage(req.player(), "matchmaking.queue_timeout");
                            queueTimestamps.remove(req.player().getUniqueId());
                            return true;
                        }
                        return false;
                    });

                    int teamSize = plugin.getConfig().getInt("matchmaking.team_size", 2);
                    while (queue.size() >= teamSize) {
                        List<MatchmakingRequest> match = new ArrayList<>();
                        for (int i = 0; i < teamSize && !queue.isEmpty(); i++) {
                            match.add(queue.remove(0));
                        }

                        if (match.size() == teamSize) {
                            processMatch(match, world);
                        }
                    }
                }
            }
        }, tickInterval * 20L, tickInterval * 20L);
    }

    private void processMatch(List<MatchmakingRequest> match, World world) {
        if (match.isEmpty()) return;

        MatchmakingRequest firstReq = match.get(0);
        int attempts = plugin.getConfig().getInt("settings.attempts", 25);

        plugin.getRTPLogger().debug("MATCHMAKING",
                "Processing match with " + match.size() + " players in " + world.getName());

        plugin.getRtpService().findSafeLocation(
                firstReq.player(),
                world,
                attempts,
                firstReq.minRadius(),
                firstReq.maxRadius()
        ).thenAccept(locationOpt -> {
            if (locationOpt.isEmpty()) {

                for (MatchmakingRequest req : match) {
                    plugin.getLocaleManager().sendMessage(req.player(), "matchmaking.no_location_found");
                    queueTimestamps.remove(req.player().getUniqueId());
                }
                plugin.getRTPLogger().debug("MATCHMAKING", "Failed to find location for match");
                return;
            }

            Location location = locationOpt.get();
            int spreadDistance = plugin.getConfig().getInt("matchmaking.spread_distance", 10);

            List<String> playerNames = match.stream()
                    .map(req -> req.player().getName())
                    .toList();
            String opponents = String.join(", ", playerNames);

            for (MatchmakingRequest req : match) {
                plugin.getLocaleManager().sendMessage(req.player(), "matchmaking.match_found",
                        Placeholder.unparsed("players", opponents),
                        Placeholder.unparsed("count", String.valueOf(match.size())));
            }

            int safetyRadius = Math.max(1, plugin.getConfig().getInt("matchmaking.safety_search_radius", 5));
            for (int i = 0; i < match.size(); i++) {
                MatchmakingRequest req = match.get(i);
                Player player = req.player();

                if (!player.isOnline()) {
                    queueTimestamps.remove(player.getUniqueId());
                    continue;
                }

                double angle = (2 * Math.PI * i) / match.size();
                int offsetX = (int) (Math.cos(angle) * spreadDistance);
                int offsetZ = (int) (Math.sin(angle) * spreadDistance);

                Location playerLoc = location.clone().add(offsetX, 0, offsetZ);

                plugin.getRtpService().findSafeLocation(player, world, 10,
                        Optional.of(0),
                        Optional.of(safetyRadius),
                        (int) playerLoc.getX(),
                        (int) playerLoc.getZ()
                ).thenAccept(safeLocOpt -> {
                    if (!player.isOnline()) {
                        queueTimestamps.remove(player.getUniqueId());
                        return;
                    }

                    Location finalLoc = safeLocOpt.orElse(location);

                    plugin.getRtpService().teleportPlayer(
                            player,
                            finalLoc,
                            null,
                            null,
                            0.0,
                            false,
                            null
                    ).thenAccept(success -> {
                        if (success) {
                            queueTimestamps.remove(player.getUniqueId());
                            plugin.getRTPLogger().debug("MATCHMAKING",
                                    "Teleported " + player.getName() + " to match location");
                        } else {
                            if (player.isOnline()) {
                                plugin.getLocaleManager().sendMessage(player, "matchmaking.teleport_failed");
                            }
                            queueTimestamps.remove(player.getUniqueId());
                        }
                    });
                });
            }
        });
    }

    public void handlePlayerQuit(Player player) {
        if (isInQueue(player)) {
            leaveQueue(player);
        }
    }
}
