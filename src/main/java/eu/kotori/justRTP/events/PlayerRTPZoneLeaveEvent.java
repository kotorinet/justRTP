package eu.kotori.justRTP.events;

import eu.kotori.justRTP.utils.RTPZone;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerRTPZoneLeaveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final RTPZone zone;
    private final LeaveReason reason;
    private final int playersInZone;

    public enum LeaveReason {

        MOVED_OUT,

        TELEPORTED,

        DISCONNECTED,

        COMMAND,

        PLUGIN_DISABLE,

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
