package eu.kotori.justRTP.events;

import eu.kotori.justRTP.utils.RTPZone;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/**
 * API Documentation can be found on https://kotori.ink/wiki/justrtp/api
 * Called when a player enters an RTP Zone.
 * This event is cancellable - cancelling prevents the player from joining the
 * zone queue.
 * 
 * <p>
 * Use this event to:
 * </p>
 * <ul>
 * <li>Block certain players from entering zones</li>
 * <li>Check zone entry requirements</li>
 * <li>Track zone participation</li>
 * <li>Trigger custom effects on zone entry</li>
 * </ul>
 * 
 * @see PlayerRTPZoneLeaveEvent for zone exit handling
 */
public class PlayerRTPZoneEnterEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private final RTPZone zone;
    private final int playersInZone;

    /**
     * Constructs a new PlayerRTPZoneEnterEvent.
     *
     * @param player        The player entering the zone
     * @param zone          The RTP zone being entered
     * @param playersInZone The number of players currently in the zone (including
     *                      this player)
     */
    public PlayerRTPZoneEnterEvent(@NotNull Player player, @NotNull RTPZone zone, int playersInZone) {
        this.player = player;
        this.zone = zone;
        this.playersInZone = playersInZone;
    }

    /**
     * Gets the player who entered the zone.
     *
     * @return The player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the RTP zone that was entered.
     *
     * @return The zone
     */
    @NotNull
    public RTPZone getZone() {
        return zone;
    }

    /**
     * Gets the zone ID.
     *
     * @return The zone identifier
     */
    @NotNull
    public String getZoneId() {
        return zone.getId();
    }

    /**
     * Gets the number of players currently in the zone.
     * This includes the player who just entered.
     *
     * @return The player count
     */
    public int getPlayersInZone() {
        return playersInZone;
    }

    /**
     * Gets the zone's teleport interval in seconds.
     *
     * @return The countdown interval
     */
    public int getZoneInterval() {
        return zone.getInterval();
    }

    /**
     * Gets the zone's target worlds or servers.
     *
     * @return The list of targets
     */
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
