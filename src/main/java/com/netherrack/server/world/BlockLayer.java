package com.netherrack.server.world;

import com.netherrack.server.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * One layer of a sub-chunk's 16x16x16 blocks, kept the way vanilla keeps it: a palette of
 * the block states in it, and for each position an index into that palette. Positions are
 * numbered {@code (x << 8) | (z << 4) | y}, vanilla's order both on disk and on the network.
 * <p>
 * Not thread-safe: the chunk it's in guards it.
 */
public final class BlockLayer {

    public static final int SIZE = 16 * 16 * 16;

    private final List<Block> palette;
    // Null while every position is the palette's first entry, as most of a fresh layer is.
    private short[] indices;

    /** A layer with one block everywhere. */
    public BlockLayer(Block fill) {
        this.palette = new ArrayList<>(List.of(fill));
    }

    /**
     * A layer as saved: its palette, and each position's index into it.
     *
     * @param indices {@link #SIZE} indices, each less than the palette's size
     */
    public BlockLayer(List<Block> palette, int[] indices) {
        this.palette = new ArrayList<>(palette);
        if (palette.size() > 1) {
            this.indices = new short[SIZE];
            for (int i = 0; i < SIZE; i++) {
                this.indices[i] = (short) indices[i];
            }
        }
    }

    private BlockLayer(BlockLayer other) {
        this.palette = new ArrayList<>(other.palette);
        this.indices = other.indices != null ? other.indices.clone() : null;
    }

    public static int index(int x, int y, int z) {
        return (x << 8) | (z << 4) | y;
    }

    public Block get(int index) {
        return palette.get(indices == null ? 0 : indices[index] & 0xFFFF);
    }

    public void set(int index, Block block) {
        int paletteIndex = palette.indexOf(block);
        if (paletteIndex < 0) {
            // Blocks replaced everywhere stay in the palette until it's compacted, which
            // only matters once there are more entries than positions.
            if (palette.size() >= SIZE) {
                compact();
            }
            palette.add(block);
            paletteIndex = palette.size() - 1;
        }
        if (indices == null) {
            if (paletteIndex == 0) {
                return;
            }
            indices = new short[SIZE];
        }
        indices[index] = (short) paletteIndex;
    }

    /** Whether every position holds this block. */
    public boolean isAll(Block block) {
        if (indices == null) {
            return palette.get(0) == block;
        }
        for (short index : indices) {
            if (palette.get(index & 0xFFFF) != block) {
                return false;
            }
        }
        return true;
    }

    /**
     * The blocks actually in this layer, in palette order, with each position's index into
     * that list written to {@code into} - what gets saved or sent, without entries nothing
     * uses any more.
     */
    public List<Block> usedPalette(int[] into) {
        if (indices == null) {
            Arrays.fill(into, 0, SIZE, 0);
            return List.of(palette.get(0));
        }
        Map<Block, Integer> used = new IdentityHashMap<>();
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            Block block = palette.get(indices[i] & 0xFFFF);
            Integer usedIndex = used.get(block);
            if (usedIndex == null) {
                usedIndex = blocks.size();
                used.put(block, usedIndex);
                blocks.add(block);
            }
            into[i] = usedIndex;
        }
        return blocks;
    }

    public BlockLayer copy() {
        return new BlockLayer(this);
    }

    private void compact() {
        int[] compacted = new int[SIZE];
        List<Block> used = usedPalette(compacted);
        palette.clear();
        palette.addAll(used);
        for (int i = 0; i < SIZE; i++) {
            indices[i] = (short) compacted[i];
        }
    }
}
