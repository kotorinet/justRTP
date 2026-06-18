package eu.kotori.justRTP.utils;

public enum ZoneParticleStyle {
    NONE,
    OUTLINE,
    BEAM,
    SWIRL,
    PULSE,
    DUST_WALL;

    public static ZoneParticleStyle fromString(String value) {
        if (value == null) return NONE;
        try {
            return ZoneParticleStyle.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    public boolean isAnimated() {
        return this == SWIRL || this == PULSE || this == BEAM;
    }
}
