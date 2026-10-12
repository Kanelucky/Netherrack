package com.netherrack.server.block;

import org.cloudburstmc.nbt.NbtMap;

/**
 * A block state: a block's namespaced id plus its states (which way stairs face, what
 * color wool is...), the way vanilla stores it in a world's palettes.
 */
public class Block {

    /**
     * The block state version every state of this game version carries in saved worlds:
     * 1.21.60.33's, unchanged up to 1.26.50. Vanilla upgrades states saved with an older
     * version as it loads them.
     */
    public static final int CURRENT_VERSION = 18_168_865;

    private final String identifier;
    private final NbtMap states;
    private final int version;
    private final int blockStateHash;

    /** A block without any states, e.g. "minecraft:cobblestone". */
    public Block(String identifier) {
        this(NbtMap.builder()
                .putString("name", identifier)
                .putCompound("states", NbtMap.EMPTY)
                .build());
    }

    /**
     * @param state the state as vanilla saves it: {"name": ..., "states": {...}, "version": ...}.
     *              Without a version, it's the current one.
     */
    public Block(NbtMap state) {
        this.identifier = state.getString("name");
        this.states = state.getCompound("states", NbtMap.EMPTY);
        this.version = state.getInt("version", CURRENT_VERSION);
        this.blockStateHash = BlockStateHash.of(state);
    }

    /**
     * The Bedrock namespaced identifier, e.g. "minecraft:grass_block".
     */
    public String getIdentifier() {
        return identifier;
    }

    /** The block's states, by name - empty for blocks that have none. */
    public NbtMap getStates() {
        return states;
    }

    /** The block state version this state was saved with, see {@link #CURRENT_VERSION}. */
    public int getVersion() {
        return version;
    }

    /** The state as a world's palette saves it: {"name": ..., "states": {...}, "version": ...}. */
    public NbtMap toNbt() {
        return NbtMap.builder()
                .putString("name", identifier)
                .putCompound("states", states)
                .putInt("version", version)
                .build();
    }

    /**
     * The block's network state hash, as Bedrock's "hashed block network IDs" scheme uses -
     * a value computed from the block's name and states (see BlockStateHash), the same way
     * on both client and server, so they agree on identity without needing to share an
     * identically-ordered palette.
     */
    public int getBlockStateHash() {
        return blockStateHash;
    }

    /**
     * Vanilla's hardness value, which sets how long the block takes to break. Blocks
     * without their own class don't know theirs yet, so they use a typical block's.
     */
    public float getHardness() {
        return 1;
    }

    /** Whether breaking it by hand is the slow, no-drop kind (e.g. stone without a pickaxe). */
    public boolean requiresTool() {
        return false;
    }

    /**
     * How many ticks breaking this by hand takes, by vanilla's formula: hardness times 1.5
     * seconds, or times 5 when the block needs a tool. Only used to animate other
     * players' view of the cracks - the breaking player's own client times the break.
     */
    public int getHandBreakTicks() {
        float seconds = getHardness() * (requiresTool() ? 5f : 1.5f);
        return Math.max(1, Math.round(seconds * 20));
    }

    @Override
    public String toString() {
        return identifier;
    }
}
