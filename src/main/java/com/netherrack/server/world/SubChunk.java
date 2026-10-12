package com.netherrack.server.world;

import com.netherrack.server.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * A 16x16x16 section of a chunk, as layers of blocks. The first layer is the blocks
 * themselves; vanilla uses a second for the water around waterlogged blocks (seagrass, a
 * fence standing in a river...), which is kept as it was loaded.
 * <p>
 * Not thread-safe: the chunk it's in guards it.
 */
public final class SubChunk {

    private final List<BlockLayer> layers;

    /** @param layers at least one: the blocks, then any others vanilla saved */
    public SubChunk(List<BlockLayer> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("A sub-chunk has at least one layer");
        }
        this.layers = new ArrayList<>(layers);
    }

    /** A sub-chunk that's all air. */
    public static SubChunk empty() {
        return new SubChunk(List.of(new BlockLayer(Blocks.AIR)));
    }

    public BlockLayer getBlocks() {
        return layers.get(0);
    }

    public List<BlockLayer> getLayers() {
        return layers;
    }

    /** Whether there's nothing but air in it, in any layer. */
    public boolean isEmpty() {
        for (BlockLayer layer : layers) {
            if (!layer.isAll(Blocks.AIR)) {
                return false;
            }
        }
        return true;
    }

    public SubChunk copy() {
        List<BlockLayer> copies = new ArrayList<>();
        for (BlockLayer layer : layers) {
            copies.add(layer.copy());
        }
        return new SubChunk(copies);
    }
}
