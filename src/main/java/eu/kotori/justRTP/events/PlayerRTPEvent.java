package eu.kotori.justRTP.events;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired before a player is teleported via RTP.
 * Cancel it to stop the teleport, or change the destination world before it runs.
 *
 * API Documentation can be found on https://deltura.net/wiki/justrtp/api
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

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public World getTargetWorld() {
        return targetWorld;
    }

    public void setTargetWorld(@NotNull World world) {
        this.targetWorld = world;
    }

    @Nullable
    public Integer getMinRadius() {
        return minRadius;
    }

    @Nullable
    public Integer getMaxRadius() {
        return maxRadius;
    }

    public double getCost() {
        return cost;
    }

    public boolean isCrossServer() {
        return isCrossServer;
    }

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
