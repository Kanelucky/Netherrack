package com.netherrack.server.block;

import com.netherrack.server.world.World;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Breaking blocks, and what follows: the other half of a door, bed or tall plant goes with
 * the half that broke, anything that can no longer stand where it is - a torch on the side
 * of the block that went, a flower on its top - breaks too, and fences, walls, tripwire and
 * the like around take their new shape. Scaffolding waits a tick to break or take its new
 * distance, as in the game, so whoever applies the changes runs {@link #updateLater} then.
 */
public final class BlockBreaking {

    /** How many blocks settling looks at, at most, so nothing can keep it going for ever. */
    private static final int MAX_SETTLE_STEPS = 10_000;

    /** A block that broke, where it was, and the block entity it had, if any - a bed's color, which its drop keeps. */
    public record Broken(Vector3i position, Block block, NbtMap blockEntity) {
    }

    /** A sound a block made as it changed shape, like a tripwire hook clicking. */
    public record Sound(Vector3i position, SoundEvent event, Block block) {
    }

    /**
     * What changed: the blocks that broke, in order, the ones that changed shape, and the
     * sounds they made - and where blocks wait to change a tick from now, for {@link #updateLater}.
     */
    public record Changes(List<Broken> broken, List<Vector3i> reshaped, List<Sound> sounds, List<Vector3i> later) {
    }

    private BlockBreaking() {
    }

    /** Breaks the block at a position, leaving air, and settles the world around it. */
    public static Changes breakBlock(World world, Vector3i position) {
        Map<Vector3i, Broken> broken = new LinkedHashMap<>();
        remove(world, position, broken);
        return settle(world, new ArrayList<>(broken.keySet()), broken);
    }

    /**
     * Brings the world around changed positions in line with them, and around each block that
     * changes in turn: blocks that can no longer stay break, with their other half, and blocks
     * shaped by their neighbours take their new shape.
     */
    public static Changes settle(World world, Collection<Vector3i> changed) {
        return settle(world, changed, new LinkedHashMap<>());
    }

    /**
     * Looks again at blocks that waited a tick to change, as settling left them: scaffolding
     * out of reach of what held it up breaks, and any other takes its new distance. The world
     * settles around what changed, which can leave more for the tick after.
     */
    public static Changes updateLater(World world, Collection<Vector3i> positions) {
        Map<Vector3i, Broken> broken = new LinkedHashMap<>();
        List<Vector3i> changed = new ArrayList<>();
        List<Vector3i> reshaped = new ArrayList<>();
        for (Vector3i at : new LinkedHashSet<>(positions)) {
            Block block = world.getBlock(at.getX(), at.getY(), at.getZ());
            if (!Scaffolding.is(block)) {
                continue;
            }
            if (!BlockSupport.survives(world, at, block)) {
                remove(world, at, broken);
                changed.add(at);
                continue;
            }
            Block shaped = Scaffolding.shaped(world, at, block);
            if (shaped != block) {
                world.setBlock(at.getX(), at.getY(), at.getZ(), shaped);
                reshaped.add(at);
                changed.add(at);
            }
        }
        Changes settled = settle(world, changed, broken);
        Set<Vector3i> allReshaped = new LinkedHashSet<>(reshaped);
        allReshaped.addAll(settled.reshaped());
        allReshaped.removeAll(broken.keySet());
        return new Changes(settled.broken(), new ArrayList<>(allReshaped), settled.sounds(), settled.later());
    }

    private static Changes settle(World world, Collection<Vector3i> changed, Map<Vector3i, Broken> broken) {
        Set<Vector3i> reshaped = new LinkedHashSet<>();
        List<Sound> sounds = new ArrayList<>();
        Set<Vector3i> later = new LinkedHashSet<>();
        Deque<Vector3i> toCheck = new ArrayDeque<>();
        for (Vector3i position : changed) {
            addAround(world, toCheck, position);
        }
        int steps = 0;
        while (!toCheck.isEmpty() && steps++ < MAX_SETTLE_STEPS) {
            Vector3i at = toCheck.poll();
            Block block = world.getBlock(at.getX(), at.getY(), at.getZ());
            if (block == null) {
                continue;
            }
            if (Scaffolding.is(block)) {
                // Scaffolding breaks, or takes its new distance, a tick later.
                if (!BlockSupport.survives(world, at, block) || Scaffolding.shaped(world, at, block) != block) {
                    later.add(at);
                }
                continue;
            }
            if (!BlockSupport.survives(world, at, block)) {
                int before = broken.size();
                remove(world, at, broken);
                List<Vector3i> justBroken = new ArrayList<>(broken.keySet()).subList(before, broken.size());
                for (Vector3i position : justBroken) {
                    addAround(world, toCheck, position);
                }
                continue;
            }
            Block shaped = BlockConnections.joined(world, at, block);
            if (shaped != block) {
                world.setBlock(at.getX(), at.getY(), at.getZ(), shaped);
                reshaped.add(at);
                if (Tripwire.isHook(block)) {
                    SoundEvent sound = Tripwire.hookSound(block, shaped);
                    if (sound != null) {
                        sounds.add(new Sound(at, sound, shaped));
                    }
                }
                addAround(world, toCheck, at);
            }
        }
        reshaped.removeAll(broken.keySet());
        later.removeAll(broken.keySet());
        return new Changes(new ArrayList<>(broken.values()), new ArrayList<>(reshaped), sounds, new ArrayList<>(later));
    }

    /** Takes a block out, and the other half of a door, bed or tall plant with it, noting both. */
    private static void remove(World world, Vector3i position, Map<Vector3i, Broken> broken) {
        Block block = world.getBlock(position.getX(), position.getY(), position.getZ());
        if (block == null) {
            return;
        }
        take(world, position, block, broken);
        Vector3i other = BlockSupport.otherHalf(block, position);
        if (other != null) {
            Block half = world.getBlock(other.getX(), other.getY(), other.getZ());
            if (half != null && half.getIdentifier().equals(block.getIdentifier())) {
                take(world, other, half, broken);
            }
        }
    }

    private static void take(World world, Vector3i position, Block block, Map<Vector3i, Broken> broken) {
        NbtMap blockEntity = world.getBlockEntity(position);
        world.setBlock(position.getX(), position.getY(), position.getZ(), null);
        broken.put(position, new Broken(position, block, blockEntity));
    }

    /**
     * Queues the blocks a change at a position can affect: the six beside it, and the tripwire
     * and hooks along the lines through it, which it can string together, part or press.
     */
    private static void addAround(World world, Deque<Vector3i> toCheck, Vector3i position) {
        for (Face face : Face.values()) {
            toCheck.add(face.from(position));
        }
        toCheck.addAll(Tripwire.alongLines(world, position));
    }
}
