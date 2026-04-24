package eu.kotori.justRTP.handlers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JumpRTPListener implements Listener {
    private final JustRTP plugin;
    private final Map<UUID, Long> lastJumpTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> jumpCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> jumpRtpCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();

    public JumpRTPListener(JustRTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!plugin.getConfigManager().isJumpRtpEnabled()) {
            return;
        }

        if (!plugin.getConfigManager().getJumpRtpEnabledWorlds().contains(player.getWorld().getName())) {
            return;
        }

        if (!player.hasPermission("justrtp.jumprtp")) {
            return;
        }

        double currentY = player.getLocation().getY();
        Double previousY = lastY.get(playerId);

        if (previousY != null) {
            if (currentY > previousY && player.getVelocity().getY() > 0 && !player.isOnGround()) {
                handleJump(player);
            }
        }

        lastY.put(playerId, currentY);
    }

    private void handleJump(Player player) {
        UUID playerId = player.getUniqueId();

        if (!player.hasPermission("justrtp.jumprtp.bypass")) {
            Long cooldownEnd = jumpRtpCooldown.get(playerId);
            if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
                long remainingSeconds = (cooldownEnd - System.currentTimeMillis()) / 1000;
                plugin.getLocaleManager().sendMessage(player, "jump_rtp.cooldown",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("time",
                                eu.kotori.justRTP.utils.TimeUtils.formatDuration(remainingSeconds)));
                return;
            }
        }

        long currentTime = System.currentTimeMillis();
        long timeWindow = plugin.getConfigManager().getJumpTimeWindow();
        int jumpsRequired = plugin.getConfigManager().getJumpsRequired();

        int currentJumpCount = jumpCount.compute(playerId, (id, existing) -> {
            Long lastJump = lastJumpTime.get(id);
            if (existing != null && lastJump != null && (currentTime - lastJump) <= timeWindow) {
                return existing + 1;
            }
            return 1;
        });

        lastJumpTime.put(playerId, currentTime);

        if (currentJumpCount >= jumpsRequired) {
            jumpCount.remove(playerId);
            lastJumpTime.remove(playerId);

            if (!player.hasPermission("justrtp.jumprtp.bypass")) {
                long cooldownDuration = plugin.getConfigManager().getJumpRtpCooldown() * 1000L;
                jumpRtpCooldown.put(playerId, currentTime + cooldownDuration);
            }

            plugin.getLocaleManager().sendMessage(player, "jump_rtp.activated");
            plugin.getTeleportQueueManager().requestTeleport(
                    player,
                    player.getWorld(),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    public void cleanupCooldowns() {
        long currentTime = System.currentTimeMillis();
        jumpRtpCooldown.entrySet().removeIf(entry -> entry.getValue() < currentTime);

        lastJumpTime.entrySet().removeIf(entry -> (currentTime - entry.getValue()) > 10000);
        jumpCount.keySet().removeIf(playerId -> !lastJumpTime.containsKey(playerId));

        lastY.keySet().removeIf(playerId -> plugin.getServer().getPlayer(playerId) == null);
    }
}
