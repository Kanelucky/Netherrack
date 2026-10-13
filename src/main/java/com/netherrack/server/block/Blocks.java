package com.netherrack.server.block;

import com.netherrack.server.network.VanillaData;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of the blocks Netherrack currently knows about.
 * <p>
 * Only a few blocks have their own class. Any other block state - one placed from the
 * creative inventory, or loaded from a saved world - is a plain {@link Block} made on
 * first use by {@link #of}.
 */
public final class Blocks {

    public static final GrassBlock GRASS_BLOCK = new GrassBlock();
    public static final CobblestoneBlock COBBLESTONE = new CobblestoneBlock();
    public static final AirBlock AIR = new AirBlock();

    private static final List<Block> ALL = List.of(GRASS_BLOCK, COBBLESTONE, AIR);
    private static final Map<String, Block> BY_IDENTIFIER =
            ALL.stream().collect(Collectors.toMap(Block::getIdentifier, Function.identity()));
    // Keyed by {"name", "states"} without the version: the same state saved by two game
    // versions is still the same block.
    private static final Map<NbtMap, Block> BY_STATE = new ConcurrentHashMap<>(
            ALL.stream().collect(Collectors.toMap(block -> key(block.getIdentifier(), block.getStates()),
                    Function.identity())));

    private Blocks() {
    }

    /** The block with this identifier (e.g. "minecraft:cobblestone"), or null if Netherrack doesn't have it. */
    public static Block byIdentifier(String identifier) {
        return BY_IDENTIFIER.get(identifier);
    }

    /**
     * The block for a block state, {"name": ..., "states": {...}} and optionally its
     * "version": Netherrack's own class if it has one for that state, otherwise a plain block.
     */
    public static Block of(NbtMap state) {
        String name = state.getString("name");
        NbtMap states = state.getCompound("states", NbtMap.EMPTY);
        return BY_STATE.computeIfAbsent(key(name, states),
                key -> UnbreakableBlock.isUnbreakable(name) ? new UnbreakableBlock(state) : new Block(state));
    }

    /**
     * The block with this name and these states, or null if the game has no such state -
     * say, a property set to a value that block doesn't take.
     */
    public static Block find(String name, NbtMap states) {
        NbtMap key = key(name, states);
        Block known = BY_STATE.get(key);
        if (known != null) {
            return known;
        }
        NbtMap state = VanillaData.get().blockState(BlockStateHash.of(key));
        return state != null && name.equals(state.getString("name")) ? of(state) : null;
    }

    /** A block's default state by name, or null if the game has no block by that name. */
    public static Block byName(String name) {
        NbtMap state = VanillaData.get().defaultBlockState(name);
        return find(name, state.getCompound("states", NbtMap.EMPTY));
    }

    private static NbtMap key(String name, NbtMap states) {
        return NbtMap.builder().putString("name", name).putCompound("states", states).build();
    }
}
