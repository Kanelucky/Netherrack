package com.netherrack.server.world;

import com.netherrack.server.block.Block;
import com.netherrack.server.util.Logger;
import com.netherrack.server.world.storage.WorldStorage;
import org.cloudburstmc.nbt.NbtMap;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single world/dimension. Chunks are loaded the first time they're asked for: from the
 * world's storage if it has them, otherwise from the generator. They stay in memory from
 * then on, and {@link #save()} writes whatever has changed in them back to the storage.
 * The world's storage also keeps each player's data, see {@link #loadPlayer}.
 */
public class World {

    private final String name;
    private final WorldGenerator generator;
    private final WorldStorage storage;
    private final int spawnX;
    private final Integer spawnY;
    private final int spawnZ;
    private final int generatorType;
    private final long seed;
    // Concurrent since each player's packets are handled on their own network thread,
    // so two players can ask for (and load) chunks at the same time.
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param storage where the world's chunks are saved, or null to keep them in memory only
     * @param level   the world's level.dat, for its spawn point, terrain type and seed
     */
    public World(String name, WorldGenerator generator, WorldStorage storage, NbtMap level) {
        this.name = name;
        this.generator = generator;
        this.storage = storage;
        this.spawnX = level.getInt("SpawnX", 0);
        this.spawnZ = level.getInt("SpawnZ", 0);
        // Vanilla saves 32767 until it has worked out where the ground is.
        int savedSpawnY = level.getInt("SpawnY", Short.MAX_VALUE);
        this.spawnY = savedSpawnY >= Chunk.MIN_Y && savedSpawnY <= Chunk.MAX_Y ? savedSpawnY : null;
        this.generatorType = level.getInt("Generator", 2);
        this.seed = level.getLong("RandomSeed", 0L);
    }

    /** The world's name, as players see it. */
    public String getName() {
        return name;
    }

    public WorldGenerator getGenerator() {
        return generator;
    }

    /** Vanilla's number for the world's kind of terrain: 0 old, 1 infinite, 2 flat. */
    public int getGeneratorType() {
        return generatorType;
    }

    public long getSeed() {
        return seed;
    }

    public int getSpawnX() {
        return spawnX;
    }

    public int getSpawnZ() {
        return spawnZ;
    }

    /**
     * The y a player arriving at x, z stands at: on top of the highest block there. Where
     * there's nothing at all, the world's own spawn height, or failing that the generator's.
     */
    public int findSpawnY(int x, int z) {
        Integer top = getChunk(x >> 4, z >> 4).getHighestBlockY(x & 15, z & 15);
        if (top != null) {
            return top + 1;
        }
        return spawnY != null ? spawnY : generator.getSpawnY();
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.computeIfAbsent(chunkKey(chunkX, chunkZ), key -> loadOrGenerate(chunkX, chunkZ));
    }

    public Block getBlock(int x, int y, int z) {
        return getChunk(x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
    }

    public void setBlock(int x, int y, int z, Block block) {
        getChunk(x >> 4, z >> 4).setBlock(x & 15, y, z & 15, block);
    }

    /** Writes every chunk's changes to the world's storage, and returns how many chunks that was. */
    public synchronized int save() {
        if (storage == null || closed) {
            return 0;
        }
        int saved = 0;
        for (Chunk chunk : chunks.values()) {
            if (!chunk.hasChanges()) {
                continue;
            }
            try {
                storage.save(chunk);
                saved++;
            } catch (IOException e) {
                Logger.error("Could not save chunk " + chunk.getChunkX() + ", " + chunk.getChunkZ()
                        + " of \"" + name + "\": " + e.getMessage());
            }
        }
        return saved;
    }

    /**
     * What was saved of the player with this UUID, or null if they're new here. Data that
     * can't be read counts as new, so they start afresh, and it's replaced when they're saved.
     */
    public NbtMap loadPlayer(UUID uuid) {
        if (storage == null || closed) {
            return null;
        }
        try {
            return storage.loadPlayer(uuid);
        } catch (IOException e) {
            Logger.error("Could not read the saved data of player " + uuid + " in \"" + name
                    + "\", so they start afresh: " + e.getMessage());
            return null;
        }
    }

    /** Saves a player's data, as made by PlayerData. Does nothing once the world is closed. */
    public synchronized void savePlayer(UUID uuid, NbtMap data) {
        if (storage == null || closed) {
            return;
        }
        try {
            storage.savePlayer(uuid, data);
        } catch (IOException e) {
            Logger.error("Could not save player " + uuid + " in \"" + name + "\": " + e.getMessage());
        }
    }

    /** Saves the world and closes its storage. Anything changed afterwards isn't saved. */
    public synchronized void close() {
        if (closed) {
            return;
        }
        save();
        closed = true;
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException e) {
                Logger.error("Could not close the storage of \"" + name + "\": " + e.getMessage());
            }
        }
    }

    private Chunk loadOrGenerate(int chunkX, int chunkZ) {
        boolean saveable = storage != null && !closed;
        if (saveable) {
            try {
                Chunk saved = storage.load(chunkX, chunkZ, generator.getBiome());
                if (saved != null) {
                    return saved;
                }
            } catch (RuntimeException e) {
                Logger.error("Could not read chunk " + chunkX + ", " + chunkZ + " of \"" + name + "\", so it's "
                        + "generated instead, and won't be saved: " + e.getMessage());
                saveable = false;
            }
        }
        Chunk chunk = new Chunk(chunkX, chunkZ, generator.getBiome());
        generator.generate(chunk);
        chunk.clearChanges();
        if (!saveable && storage != null) {
            chunk.markReadOnly();
        }
        return chunk;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
