package eu.kotori.justRTP.utils;

public enum ZoneShape {
    CUBOID,
    CYLINDER,
    BLOCKS;

    public static ZoneShape fromString(String value) {
        if (value == null) return CUBOID;
        try {
            return ZoneShape.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CUBOID;
        }
    }
}
