package com.netherrack.server.block;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;

/**
 * Who places a block, as far as the block cares: which of the four sides they face, the
 * exact way they look (for blocks that turn in sixteen steps), where their eyes are (for
 * blocks that can face them from above or below), and whether they sneak.
 */
public record Placer(Face facing, float yaw, Vector3f eyes, boolean sneaking) {

    public static Placer of(Vector3f eyes, float yaw, boolean sneaking) {
        return new Placer(Face.facing(yaw), yaw, eyes, sneaking);
    }

    /**
     * The side of a block placed here that faces the placer, which blocks like dispensers
     * and pistons turn their front to: up or down when the placer is right beside it with
     * their eyes well above or below it, and otherwise the side around towards them.
     */
    public Face facingFrom(Vector3i position) {
        if (eyes != null && Math.abs((int) Math.floor(eyes.getX()) - position.getX()) < 2
                && Math.abs((int) Math.floor(eyes.getZ()) - position.getZ()) < 2) {
            if (eyes.getY() - position.getY() > 2) {
                return Face.UP;
            }
            if (position.getY() - eyes.getY() > 0) {
                return Face.DOWN;
            }
        }
        return facing.opposite();
    }

    /**
     * Which of sixteen steps around a block turned towards the placer points, as standing
     * signs and banners count them: 0 faces south, each step a sixteenth turn clockwise.
     */
    public int rotationTowards() {
        return Math.floorMod((int) Math.floor((yaw + 180f) * 16f / 360f + 0.5f), 16);
    }
}
