package com.netherrack.server.world;

import com.netherrack.server.block.Block;
import com.netherrack.server.block.Blocks;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;

/**
 * A 16x16 column of blocks from the bottom of the world to the top, identified by chunk
 * coordinates (block coordinates divided by 16): 24 sub-chunks of 16x16x16, covering the
 * overworld's y -64 to 319. Along with the blocks it keeps what else a saved chunk has
 * that players' clients need: its biomes and its block entities (a sign's text, a bed's
 * color...).
 * <p>
 * Players change blocks from their own network threads while other threads send and save
 * chunks, so every method synchronizes on the chunk - and ChunkEncoder and the storage
 * hold that same lock while they read it.
 * <p>
 * The chunk also tracks what has changed since it was loaded or last saved, so saving
 * writes only that.
 */
public class Chunk {

    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    /** The vertical index of the lowest sub-chunk, y -64 to -49. */
    public static final int MIN_SUB_CHUNK_Y = MIN_Y >> 4;
    public static final int SUB_CHUNK_COUNT = (MAX_Y - MIN_Y + 1) >> 4;

    private final int chunkX;
    private final int chunkZ;
    private final int defaultBiome;
    // Null where a sub-chunk is all air.
    private final SubChunk[] subChunks = new SubChunk[SUB_CHUNK_COUNT];
    private byte[] biomes;
    private final List<NbtMap> blockEntities = new ArrayList<>();

    private boolean stored;
    private boolean readOnly;
    // One bit per sub-chunk, by index from the bottom.
    private int changedSubChunks;
    private boolean blockEntitiesChanged;

    /** @param defaultBiome the biome everywhere in the chunk, unless it has saved biomes */
    public Chunk(int chunkX, int chunkZ, int defaultBiome) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.defaultBiome = defaultBiome;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /**
     * The block at a position, or null for air - including everywhere above and below
     * the world.
     *
     * @param localX 0-15, x within this chunk
     * @param y      world y
     * @param localZ 0-15, z within this chunk
     */
    public synchronized Block getBlock(int localX, int y, int localZ) {
        if (y < MIN_Y || y > MAX_Y) {
            return null;
        }
        SubChunk subChunk = subChunks[(y >> 4) - MIN_SUB_CHUNK_Y];
        if (subChunk == null) {
            return null;
        }
        Block block = subChunk.getBlocks().get(BlockLayer.index(localX & 15, y & 15, localZ & 15));
        return block == Blocks.AIR ? null : block;
    }

    /**
     * Sets the block at a position, null meaning air. Whatever block entity was there goes:
     * it belonged to the block that's been replaced. Positions above and below the world
     * are ignored.
     */
    public synchronized void setBlock(int localX, int y, int localZ, Block block) {
        if (y < MIN_Y || y > MAX_Y) {
            return;
        }
        if (block == null) {
            block = Blocks.AIR;
        }
        int index = (y >> 4) - MIN_SUB_CHUNK_Y;
        SubChunk subChunk = subChunks[index];
        if (subChunk == null) {
            if (block == Blocks.AIR) {
                return;
            }
            subChunk = subChunks[index] = SubChunk.empty();
        }
        subChunk.getBlocks().set(BlockLayer.index(localX & 15, y & 15, localZ & 15), block);
        changedSubChunks |= 1 << index;

        int x = (chunkX << 4) | (localX & 15);
        int z = (chunkZ << 4) | (localZ & 15);
        int worldY = y;
        if (blockEntities.removeIf(entity -> isAt(entity, x, worldY, z))) {
            blockEntitiesChanged = true;
        }
    }

    /** The y of the highest block in a column (anything but air), or null if it's empty. */
    public synchronized Integer getHighestBlockY(int localX, int localZ) {
        for (int index = SUB_CHUNK_COUNT - 1; index >= 0; index--) {
            SubChunk subChunk = subChunks[index];
            if (subChunk == null) {
                continue;
            }
            for (int y = 15; y >= 0; y--) {
                if (subChunk.getBlocks().get(BlockLayer.index(localX & 15, y, localZ & 15)) != Blocks.AIR) {
                    return ((index + MIN_SUB_CHUNK_Y) << 4) + y;
                }
            }
        }
        return null;
    }

    /**
     * A sub-chunk by its index from the bottom (0 is y -64 to -49), or null if it's all
     * air. Callers hold the chunk's lock while they use it.
     */
    public SubChunk getSubChunk(int index) {
        return subChunks[index];
    }

    /** Puts a loaded sub-chunk in place, by its index from the bottom. */
    public synchronized void setSubChunk(int index, SubChunk subChunk) {
        subChunks[index] = subChunk;
    }

    /** The biome everywhere in the chunk, unless it has saved biomes. */
    public int getDefaultBiome() {
        return defaultBiome;
    }

    /**
     * The chunk's biomes as vanilla saved them, or null to use the default biome
     * everywhere: a section per sub-chunk from the bottom up, each a palette of biome ids
     * and an index into it per position, in the same layout as a sub-chunk's blocks - the
     * part of the chunk's "Data3D" record after its height map.
     */
    public synchronized byte[] getBiomes() {
        return biomes;
    }

    public synchronized void setBiomes(byte[] biomes) {
        this.biomes = biomes;
    }

    /**
     * The chunk's block entities, as vanilla saves them: one compound per block that has
     * one, saying where it is ("x", "y", "z", in the world) and what it is ("id").
     * Callers hold the chunk's lock while they use it.
     */
    public List<NbtMap> getBlockEntities() {
        return blockEntities;
    }

    public synchronized void setBlockEntities(List<NbtMap> loaded) {
        blockEntities.clear();
        blockEntities.addAll(loaded);
    }

    /** The block entity of the block at a position in the world, or null if it has none. */
    public synchronized NbtMap getBlockEntity(int x, int y, int z) {
        for (NbtMap entity : blockEntities) {
            if (isAt(entity, x, y, z)) {
                return entity;
            }
        }
        return null;
    }

    /** Gives a block its block entity, in place of any it had, at the position the entity says. */
    public synchronized void setBlockEntity(NbtMap entity) {
        int x = entity.getInt("x");
        int y = entity.getInt("y");
        int z = entity.getInt("z");
        blockEntities.removeIf(existing -> isAt(existing, x, y, z));
        blockEntities.add(entity);
        blockEntitiesChanged = true;
    }

    private static boolean isAt(NbtMap entity, int x, int y, int z) {
        return entity.getInt("x") == x && entity.getInt("y") == y && entity.getInt("z") == z;
    }

    /** Whether the world's storage has this chunk, or it was generated since the world was loaded. */
    public synchronized boolean isStored() {
        return stored;
    }

    public synchronized void markStored() {
        stored = true;
    }

    /**
     * Keeps the chunk from ever being saved: for a chunk that couldn't be read in full,
     * so saving it can't overwrite the parts that couldn't be read.
     */
    public synchronized void markReadOnly() {
        readOnly = true;
    }

    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    /** Forgets what has changed, e.g. once a generator has filled a new chunk in. */
    public synchronized void clearChanges() {
        changedSubChunks = 0;
        blockEntitiesChanged = false;
    }

    public synchronized boolean hasChanges() {
        return !readOnly && (changedSubChunks != 0 || blockEntitiesChanged);
    }

    /**
     * Copies what has to be saved and forgets it has changed, or returns null if nothing
     * has. A chunk the storage doesn't have yet is saved whole.
     */
    public synchronized Changes takeChanges() {
        if (!hasChanges()) {
            return null;
        }
        SubChunk[] copies = new SubChunk[SUB_CHUNK_COUNT];
        int changed = changedSubChunks;
        if (!stored) {
            changed = 0;
            for (int i = 0; i < SUB_CHUNK_COUNT; i++) {
                if (subChunks[i] != null) {
                    changed |= 1 << i;
                }
            }
        }
        for (int i = 0; i < SUB_CHUNK_COUNT; i++) {
            if ((changed & (1 << i)) != 0 && subChunks[i] != null) {
                copies[i] = subChunks[i].copy();
            }
        }
        List<NbtMap> entities = blockEntitiesChanged || (!stored && !blockEntities.isEmpty())
                ? List.copyOf(blockEntities) : null;
        Changes changes = new Changes(!stored, changed, copies, entities);
        clearChanges();
        return changes;
    }

    /** Marks changes that couldn't be saved as changed again, so the next save tries again. */
    public synchronized void restoreChanges(Changes changes) {
        changedSubChunks |= changes.changedSubChunks();
        if (changes.blockEntities() != null) {
            blockEntitiesChanged = true;
        }
    }

    /**
     * What to save of a chunk.
     *
     * @param newToStorage     whether the storage doesn't have the chunk yet
     * @param changedSubChunks one bit per sub-chunk to write, by index from the bottom
     * @param subChunks        copies of those sub-chunks; null where one is all air now
     * @param blockEntities    the block entities, or null if they haven't changed
     */
    public record Changes(boolean newToStorage, int changedSubChunks, SubChunk[] subChunks,
                          List<NbtMap> blockEntities) {
    }
}
