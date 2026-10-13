package com.netherrack.server.block;

import org.cloudburstmc.math.vector.Vector3i;

import java.util.List;
import java.util.Locale;

/** A side of a block, in the order the client numbers them. */
public enum Face {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    /** The four sides around a block, as fences, walls and panes look at them. */
    public static final List<Face> AROUND = List.of(NORTH, EAST, SOUTH, WEST);

    /** The order older block properties, like "direction", number the four sides around in. */
    private static final List<Face> LEGACY_ORDER = List.of(SOUTH, WEST, NORTH, EAST);

    private final int dx;
    private final int dy;
    private final int dz;

    Face(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /** The face numbered this way by the client, or null for a number that isn't one. */
    public static Face of(int id) {
        return id >= 0 && id < values().length ? values()[id] : null;
    }

    /** The side around numbered this way by older properties, or null for a number that isn't one. */
    public static Face ofLegacyIndex(int index) {
        return index >= 0 && index < LEGACY_ORDER.size() ? LEGACY_ORDER.get(index) : null;
    }

    /** The face named this way in block properties ("north", "up"...), or null if it's none of them. */
    public static Face named(Object name) {
        for (Face face : values()) {
            if (face.id().equals(name)) {
                return face;
            }
        }
        return null;
    }

    /** Which of the four sides a player looking along this yaw faces: 0 looks south, 90 west. */
    public static Face facing(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }

    /** The block next to this one through this face. */
    public Vector3i from(Vector3i position) {
        return position.add(dx, dy, dz);
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Face opposite() {
        return values()[ordinal() ^ 1];
    }

    /** Its name as block properties spell it: "north", "up"... */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The axis it lies across, as pillars like logs name it. */
    public String axis() {
        return switch (this) {
            case DOWN, UP -> "y";
            case NORTH, SOUTH -> "z";
            case WEST, EAST -> "x";
        };
    }

    /** One of the four sides around, rather than the top or bottom. */
    public boolean isHorizontal() {
        return dy == 0;
    }

    /** The side a quarter turn clockwise, seen from above. The top and bottom stay as they are. */
    public Face clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public Face counterClockwise() {
        return clockwise().opposite();
    }

    /** Its number in older properties' order of the sides around: south, west, north, east. */
    public int legacyIndex() {
        return LEGACY_ORDER.indexOf(this);
    }
}
