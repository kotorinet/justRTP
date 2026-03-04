package eu.kotori.justRTP.events;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * API Documentation can be found on https://kotori.ink/wiki/justrtp/api
 * Called when a player is about to be randomly teleported (before location is
 * found).
 * This event is cancellable - cancelling prevents the teleport from happening.
 * 
 * <p>
 * Use this event to:
 * </p>
 * <ul>
 * <li>Cancel teleports based on custom conditions</li>
 * <li>Modify the target world before location search begins</li>
 * <li>Track RTP attempts</li>
 * </ul>
 * 
 * @see PlayerPostRTPEvent for after-teleport handling
 */
public class PlayerRTPEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private World targetWorld;
    private final Integer minRadius;
    private final Integer maxRadius;
    private final double cost;
    private final boolean isCrossServer;
    private final String targetServer;

    /**
     * Constructs a new PlayerRTPEvent.
     *
     * @param player        The player attempting to RTP
     * @param targetWorld   The target world for the teleport
     * @param minRadius     Minimum radius (null if using default)
     * @param maxRadius     Maximum radius (null if using default)
     * @param cost          The economy cost of the teleport
     * @param isCrossServer Whether this is a cross-server teleport
     * @param targetServer  The target server name (null if local teleport)
     */
    public PlayerRTPEvent(@NotNull Player player, @NotNull World targetWorld,
            @Nullable Integer minRadius, @Nullable Integer maxRadius,
            double cost, boolean isCrossServer, @Nullable String targetServer) {
        this.player = player;
        this.targetWorld = targetWorld;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.cost = cost;
        this.isCrossServer = isCrossServer;
        this.targetServer = targetServer;
    }

    /**
     * Gets the player who is attempting to teleport.
     *
     * @return The player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the target world for the teleport.
     *
     * @return The target world
     */
    @NotNull
    public World getTargetWorld() {
        return targetWorld;
    }

    /**
     * Sets the target world for the teleport.
     * This allows plugins to redirect teleports to different worlds.
     *
     * @param world The new target world
     */
    public void setTargetWorld(@NotNull World world) {
        this.targetWorld = world;
    }

    /**
     * Gets the minimum radius for the teleport.
     *
     * @return The minimum radius, or null if using default
     */
    @Nullable
    public Integer getMinRadius() {
        return minRadius;
    }

    /**
     * Gets the maximum radius for the teleport.
     *
     * @return The maximum radius, or null if using default
     */
    @Nullable
    public Integer getMaxRadius() {
        return maxRadius;
    }

    /**
     * Gets the economy cost of the teleport.
     *
     * @return The cost (0 if economy disabled or player has bypass)
     */
    public double getCost() {
        return cost;
    }

    /**
     * Checks if this is a cross-server teleport.
     *
     * @return true if cross-server, false if local
     */
    public boolean isCrossServer() {
        return isCrossServer;
    }

    /**
     * Gets the target server name for cross-server teleports.
     *
     * @return The server name, or null if local teleport
     */
    @Nullable
    public String getTargetServer() {
        return targetServer;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
