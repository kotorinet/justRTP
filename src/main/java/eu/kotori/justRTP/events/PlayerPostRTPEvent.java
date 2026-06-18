package eu.kotori.justRTP.events;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called after a player has been successfully teleported via RTP.
 * This event is NOT cancellable - the teleport has already occurred.
 *
 * API Documentation can be found on https://deltura.net/wiki/justrtp/api
 */
public class PlayerPostRTPEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location from;
    private final Location to;
    private final World targetWorld;
    private final Integer minRadius;
    private final Integer maxRadius;
    private final double cost;
    private final boolean isCrossServer;
    private final String targetServer;

    public PlayerPostRTPEvent(@NotNull Player player, @NotNull Location from, @NotNull Location to,
                             @NotNull World targetWorld, @Nullable Integer minRadius,
                             @Nullable Integer maxRadius, double cost, boolean isCrossServer,
                             @Nullable String targetServer) {
        this.player = player;
        this.from = from;
        this.to = to;
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
    public Location getFrom() {
        return from;
    }

    @NotNull
    public Location getTo() {
        return to;
    }

    @NotNull
    public World getTargetWorld() {
        return targetWorld;
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
