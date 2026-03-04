package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportQueueManager {
    private record TeleportRequest(Player player, World world, Optional<Integer> minRadius, Optional<Integer> maxRadius,
            CompletableFuture<Boolean> future, long timestamp, int centerX, int centerZ, boolean useCustomCenter,
            double cost) {
        public TeleportRequest(Player player, World world, Optional<Integer> minRadius, Optional<Integer> maxRadius,
                CompletableFuture<Boolean> future, long timestamp, double cost) {
            this(player, world, minRadius, maxRadius, future, timestamp, 0, 0, false, cost);
        }

        public TeleportRequest(Player player, World world, Optional<Integer> minRadius, Optional<Integer> maxRadius,
                CompletableFuture<Boolean> future, long timestamp, int centerX, int centerZ) {
            this(player, world, minRadius, maxRadius, future, timestamp, centerX, centerZ, true, 0.0);
        }
    }

    private final JustRTP plugin;
    private final ConcurrentLinkedQueue<TeleportRequest> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, AtomicBoolean> processingPlayers = new ConcurrentHashMap<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public TeleportQueueManager(JustRTP plugin) {
        this.plugin = plugin;
        start();
    }

    public void reload() {
        start();
    }

    private void start() {
        boolean useQueue = plugin.getConfig().getBoolean("performance.use_teleport_queue", true);
        if (!useQueue)
            return;
        long rate = 20L / plugin.getConfig().getLong("performance.queue_processing_rate", 5);
        if (rate <= 0)
            rate = 1L;
        int batchSize = plugin.getConfig().getInt("performance.queue_batch_size", 1);
        if (batchSize <= 0)
            batchSize = 1;

        final int finalBatchSize = batchSize;
        plugin.getFoliaScheduler().runTimer(() -> {
            if (!isProcessing.compareAndSet(false, true)) {
                plugin.getRTPLogger().debug("QUEUE", "Queue processing already in progress, skipping this tick");
                return;
            }

            try {
                for (int i = 0; i < finalBatchSize && !queue.isEmpty(); i++) {
                    TeleportRequest request = queue.poll();
                    if (request == null)
                        break;

                    Player player = request.player();
                    UUID playerUUID = player.getUniqueId();

                    AtomicBoolean processing = processingPlayers.computeIfAbsent(playerUUID,
                            k -> new AtomicBoolean(false));
                    if (!processing.compareAndSet(false, true)) {
                        plugin.getRTPLogger().debug("QUEUE", "Player " + player.getName()
                                + " is already being processed, skipping duplicate request");
                        request.future().complete(false);
                        continue;
                    }

                    if (!player.isOnline()) {
                        plugin.getRTPLogger().debug("QUEUE",
                                "Player " + player.getName() + " went offline before processing");
                        processingPlayers.remove(playerUUID);
                        request.future().complete(false);
                        continue;
                    }

                    long currentTime = System.currentTimeMillis();
                    if ((currentTime - request.timestamp()) > 60000) {
                        plugin.getRTPLogger().debug("QUEUE",
                                "Teleport request for " + player.getName() + " timed out (>60s in queue)");
                        processingPlayers.remove(playerUUID);
                        plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                        request.future().complete(false);
                        continue;
                    }

                    CompletableFuture<Optional<org.bukkit.Location>> locationFuture;
                    if (request.useCustomCenter()) {
                        locationFuture = plugin.getRtpService().findSafeLocation(player, request.world(), 0,
                                request.minRadius(), request.maxRadius(), request.centerX(), request.centerZ());
                    } else {
                        locationFuture = plugin.getRtpService().findSafeLocation(player, request.world(), 0,
                                request.minRadius(), request.maxRadius());
                    }

                    locationFuture.whenComplete((locationOpt, throwable) -> {
                        if (throwable != null) {
                            plugin.getLogger().severe("Error finding safe location for " + player.getName() + ": "
                                    + throwable.getMessage());
                            plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                            processingPlayers.remove(playerUUID);
                            request.future().complete(false);
                        } else if (locationOpt.isPresent()) {
                            if (player.isOnline()) {
                                plugin.getRtpService().teleportPlayer(player, locationOpt.get(),
                                        request.minRadius().orElse(null),
                                        request.maxRadius().orElse(null),
                                        request.cost(),
                                        false,
                                        null).whenComplete((teleportSuccess, teleportError) -> {
                                            try {
                                                if (teleportError != null) {
                                                    plugin.getLogger()
                                                            .severe("Error teleporting " + player.getName() + ": "
                                                                    + teleportError.getMessage());
                                                    request.future().complete(false);
                                                } else {
                                                    request.future().complete(teleportSuccess);
                                                }
                                            } finally {
                                                processingPlayers.remove(playerUUID);
                                            }
                                        });
                            } else {
                                plugin.getRTPLogger().debug("QUEUE",
                                        "Player " + player.getName() + " went offline before teleport");
                                processingPlayers.remove(playerUUID);
                                request.future().complete(false);
                            }
                        } else {
                            plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                            processingPlayers.remove(playerUUID);
                            request.future().complete(false);
                        }
                    });
                }
            } finally {
                isProcessing.set(false);
            }
        }, 1L, rate);
    }

    public CompletableFuture<Boolean> requestTeleport(Player player, World world, Optional<Integer> minRadius,
            Optional<Integer> maxRadius) {
        return requestTeleportInternal(player, world, minRadius, maxRadius, 0, 0, false, 0.0);
    }

    public CompletableFuture<Boolean> requestTeleport(Player player, World world, Optional<Integer> minRadius,
            Optional<Integer> maxRadius, double cost) {
        return requestTeleportInternal(player, world, minRadius, maxRadius, 0, 0, false, cost);
    }

    public CompletableFuture<Boolean> requestTeleport(Player player, World world, Optional<Integer> minRadius,
            Optional<Integer> maxRadius, int centerX, int centerZ) {
        return requestTeleportInternal(player, world, minRadius, maxRadius, centerX, centerZ, true, 0.0);
    }

    public CompletableFuture<Boolean> requestTeleport(Player player, World world, Optional<Integer> minRadius,
            Optional<Integer> maxRadius, int centerX, int centerZ, double cost) {
        return requestTeleportInternal(player, world, minRadius, maxRadius, centerX, centerZ, true, cost);
    }

    private CompletableFuture<Boolean> requestTeleportInternal(Player player, World world,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, int centerX, int centerZ,
            boolean useCustomCenter, double cost) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        UUID playerUUID = player.getUniqueId();

        AtomicBoolean processing = processingPlayers.get(playerUUID);
        if (processing != null && processing.get()) {
            plugin.getRTPLogger().debug("QUEUE",
                    "Player " + player.getName() + " already has a teleport request in progress");
            plugin.getLocaleManager().sendMessage(player, "teleport.already_in_progress");
            future.complete(false);
            return future;
        }

        boolean useQueue = plugin.getConfig().getBoolean("performance.use_teleport_queue", true);
        if (useQueue) {
            boolean alreadyInQueue = queue.stream()
                    .anyMatch(req -> req.player().getUniqueId().equals(playerUUID));

            if (alreadyInQueue) {
                plugin.getRTPLogger().debug("QUEUE",
                        "Player " + player.getName() + " already has a teleport request in queue");
                plugin.getLocaleManager().sendMessage(player, "teleport.already_in_progress");
                future.complete(false);
                return future;
            }

            long timestamp = System.currentTimeMillis();
            queue.add(new TeleportRequest(player, world, minRadius, maxRadius, future, timestamp, centerX, centerZ,
                    useCustomCenter, cost));
            plugin.getEffectsManager().applyEffects(player,
                    plugin.getConfig().getConfigurationSection("effects.in_queue_action_bar"));
            plugin.getRTPLogger().debug("QUEUE",
                    "Added teleport request to queue for " + player.getName() + " (queue size: " + queue.size()
                            + ", center: " + centerX + "," + centerZ + ", useCustomCenter: " + useCustomCenter + ")");
        } else {
            AtomicBoolean directProcessing = processingPlayers.computeIfAbsent(playerUUID,
                    k -> new AtomicBoolean(false));
            if (!directProcessing.compareAndSet(false, true)) {
                plugin.getRTPLogger().debug("QUEUE",
                        "Player " + player.getName() + " already has a direct teleport in progress");
                plugin.getLocaleManager().sendMessage(player, "teleport.already_in_progress");
                future.complete(false);
                return future;
            }

            CompletableFuture<Optional<org.bukkit.Location>> locationFuture;
            if (useCustomCenter) {
                locationFuture = plugin.getRtpService().findSafeLocation(player, world, 0, minRadius, maxRadius,
                        centerX, centerZ);
            } else {
                locationFuture = plugin.getRtpService().findSafeLocation(player, world, 0, minRadius, maxRadius);
            }

            final double finalCost = cost;
            locationFuture.whenComplete((locationOpt, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().severe("Error finding safe location for " + player.getName() + ": "
                            + throwable.getMessage());
                    plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                    processingPlayers.remove(playerUUID);
                    future.complete(false);
                } else if (locationOpt.isPresent()) {
                    if (player.isOnline()) {
                        plugin.getRtpService().teleportPlayer(player, locationOpt.get(),
                                minRadius.orElse(null),
                                maxRadius.orElse(null),
                                finalCost,
                                false,
                                null).whenComplete((teleportSuccess, teleportError) -> {
                                    try {
                                        if (teleportError != null) {
                                            plugin.getLogger().severe("Error teleporting " + player.getName() + ": "
                                                    + teleportError.getMessage());
                                            future.complete(false);
                                        } else {
                                            future.complete(teleportSuccess);
                                        }
                                    } finally {
                                        processingPlayers.remove(playerUUID);
                                    }
                                });
                    } else {
                        processingPlayers.remove(playerUUID);
                        future.complete(false);
                    }
                } else {
                    plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                    processingPlayers.remove(playerUUID);
                    future.complete(false);
                }
            });
        }
        return future;
    }

    public void cancelRequest(Player player) {
        if (player == null)
            return;
        UUID playerUUID = player.getUniqueId();

        AtomicBoolean processing = processingPlayers.remove(playerUUID);
        if (processing != null && processing.get()) {
            plugin.getRTPLogger().debug("QUEUE", "Cancelled in-progress teleport for " + player.getName());
        }

        int removed = 0;
        boolean moreToRemove = true;
        while (moreToRemove) {
            moreToRemove = queue.removeIf(request -> {
                if (request.player() != null && request.player().getUniqueId().equals(playerUUID)) {
                    request.future().complete(false);
                    return true;
                }
                return false;
            });
            if (moreToRemove)
                removed++;
        }

        if (removed > 0) {
            plugin.getRTPLogger().debug("QUEUE",
                    "Cancelled " + removed + " queued teleport request(s) for " + player.getName());
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getProcessingCount() {
        return (int) processingPlayers.values().stream()
                .filter(AtomicBoolean::get)
                .count();
    }

    public boolean isPlayerInProgress(UUID playerUUID) {
        AtomicBoolean processing = processingPlayers.get(playerUUID);
        if (processing != null && processing.get()) {
            return true;
        }
        return queue.stream().anyMatch(req -> req.player().getUniqueId().equals(playerUUID));
    }
}