package eu.kotori.justRTP.utils;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

public interface ZoneRegion {
    ZoneShape getShape();

    String getWorldName();

    boolean contains(Location location);

    Location getCenter();

    void serialize(ConfigurationSection section);
}
