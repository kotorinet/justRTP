package eu.kotori.justRTP.events;

import eu.kotori.justRTP.utils.RTPZone;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player leaves an RTP Zone. Use {@link #getReason()} to find out why.
 * This event is NOT cancellable.
 *
 * API Documentation can be found on https://deltura.net/wiki/justrtp/api
 */
public class PlayerRTPZoneLeaveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final RTPZone zone;
    private final LeaveReason reason;
    private final int playersInZone;

    /**
     * The reason a player left an RTP Zone.
     */
    public enum LeaveReason {
        /** The player walked out of the zone region. */
        MOVED_OUT,
        /** The player was teleported out by the zone. */
        TELEPORTED,
        /** The player disconnected while inside the zone. */
        DISCONNECTED,
        /** The player was removed by a command. */
        COMMAND,
        /** The zone or the plugin was disabled. */
        PLUGIN_DISABLE,
        /** Any other reason. */
        OTHER
    }

    public PlayerRTPZoneLeaveEvent(@NotNull Player player, @NotNull RTPZone zone,
                                  @NotNull LeaveReason reason, int playersInZone) {
        this.player = player;
        this.zone = zone;
        this.reason = reason;
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

    @NotNull
    public LeaveReason getReason() {
        return reason;
    }

    public int getPlayersInZone() {
        return playersInZone;
    }

    public boolean wasTeleported() {
        return reason == LeaveReason.TELEPORTED;
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
