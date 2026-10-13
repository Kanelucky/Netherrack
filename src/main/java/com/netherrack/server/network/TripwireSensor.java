package com.netherrack.server.network;

import com.netherrack.server.block.Block;
import com.netherrack.server.block.BlockBreaking;
import com.netherrack.server.block.Blocks;
import com.netherrack.server.block.Tripwire;
import com.netherrack.server.player.Player;
import com.netherrack.server.player.PlayerManager;
import com.netherrack.server.world.World;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Players walking into tripwire. Tripwire a player touches is pressed, and the hooks it's
 * strung between click down (see Tripwire). As in the game, pressed tripwire looks every
 * half second for anyone still in it, and is let go once there's nobody. Spectators pass
 * through without touching it.
 */
public class TripwireSensor {

    /** How often pressed tripwire looks for anyone still in it. */
    private static final int RECHECK_TICKS = 10;

    /** Half a player's width, and their height standing. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    private final World world;
    private final PlayerManager players;
    /** Pressed tripwire, and the tick it next looks for anyone in it. */
    private final Map<Vector3i, Long> pressed = new HashMap<>();
    private long tick;

    public TripwireSensor(World world, PlayerManager players) {
        this.world = world;
        this.players = players;
    }

    /** Presses tripwire players have walked into, and lets go of tripwire they've left. Run once a tick. */
    public void tick() {
        tick++;
        Set<Vector3i> touched = touchedWire();
        for (Vector3i at : touched) {
            if (!pressed.containsKey(at)) {
                pressed.put(at, tick + RECHECK_TICKS);
                press(at, true);
            }
        }
        Iterator<Map.Entry<Vector3i, Long>> due = pressed.entrySet().iterator();
        while (due.hasNext()) {
            Map.Entry<Vector3i, Long> entry = due.next();
            if (entry.getValue() > tick) {
                continue;
            }
            if (touched.contains(entry.getKey())) {
                entry.setValue(tick + RECHECK_TICKS);
            } else {
                due.remove();
                press(entry.getKey(), false);
            }
        }
    }

    /** The tripwire any player's box touches. */
    private Set<Vector3i> touchedWire() {
        Set<Vector3i> touched = new HashSet<>();
        for (Player player : players.getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            Vector3f feet = player.getFeetPosition();
            double bottom = feet.getY();
            double top = bottom + HEIGHT;
            for (int x = floor(feet.getX() - HALF_WIDTH); x <= floor(feet.getX() + HALF_WIDTH); x++) {
                for (int z = floor(feet.getZ() - HALF_WIDTH); z <= floor(feet.getZ() + HALF_WIDTH); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    for (int y = floor(bottom); y <= floor(top); y++) {
                        Block block = world.getBlock(x, y, z);
                        if (Tripwire.isWire(block) && Tripwire.touches(block, bottom, top, y)) {
                            touched.add(Vector3i.from(x, y, z));
                        }
                    }
                }
            }
        }
        return touched;
    }

    /** Presses or lets go of the tripwire at a position, and shows everyone the hooks it moves. */
    private void press(Vector3i at, boolean down) {
        Block wire = world.getBlock(at.getX(), at.getY(), at.getZ());
        if (!Tripwire.isWire(wire)) {
            return; // broken since it was pressed
        }
        Block changed = Tripwire.pressed(wire, down);
        if (changed == null) {
            return;
        }
        world.setBlock(at.getX(), at.getY(), at.getZ(), changed);
        players.broadcastBlock(at, changed);
        BlockBreaking.Changes settled = BlockBreaking.settle(world, List.of(at));
        for (Vector3i reshaped : settled.reshaped()) {
            players.broadcastBlock(reshaped, blockAt(reshaped));
        }
        for (BlockBreaking.Broken broken : settled.broken()) {
            players.broadcastBlock(broken.position(), Blocks.AIR);
        }
        for (BlockBreaking.Sound sound : settled.sounds()) {
            Vector3i where = sound.position();
            players.broadcastSound(sound.event(),
                    Vector3f.from(where.getX() + 0.5, where.getY() + 0.5, where.getZ() + 0.5), sound.block());
        }
    }

    private Block blockAt(Vector3i position) {
        Block block = world.getBlock(position.getX(), position.getY(), position.getZ());
        return block != null ? block : Blocks.AIR;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
