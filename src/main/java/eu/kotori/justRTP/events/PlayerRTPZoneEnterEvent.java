package eu.kotori.justRTP.events;

import eu.kotori.justRTP.utils.RTPZone;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/**
 * Fired when a player enters a configured RTP Zone.
 * Cancel it to keep the player out of the zone.
 *
 * API Documentation can be found on https://deltura.net/wiki/justrtp/api
 */
public class PlayerRTPZoneEnterEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private final RTPZone zone;
    private final int playersInZone;

    public PlayerRTPZoneEnterEvent(@NotNull Player player, @NotNull RTPZone zone, int playersInZone) {
        this.player = player;
        this.zone = zone;
        this.playersInZone = playersInZone;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public RTPZone getZone() {
        return zone;
    }

    @NotNull
    public String getZoneId() {
        return zone.getId();
    }

    public int getPlayersInZone() {
        return playersInZone;
    }

    public int getZoneInterval() {
        return zone.getInterval();
    }

    @NotNull
    public List<String> getZoneTargets() {
        return zone.getTargets();
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
