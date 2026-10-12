package com.netherrack.server.world.storage;

import com.netherrack.server.util.Logger;
import com.netherrack.server.world.Chunk;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A world's chunks, kept in its LevelDB database (the "db" folder of a world) exactly as
 * vanilla keeps them, so a world from Bedrock Dedicated Server or the game itself loads
 * here, and a world saved here opens in the game.
 * <p>
 * Each part of a chunk is its own record (see ChunkKeys): a version, the blocks of each
 * sub-chunk that isn't all air, the biomes, block entities, and others - entities,
 * scheduled ticks... - that Netherrack doesn't use but leaves as they are. Saving a chunk
 * only writes what changed, and all of it at once, so a crash midway keeps either the old
 * chunk or the new one.
 */
public final class ChunkStorage implements Closeable {

    /** The chunk format version Netherrack writes, the same as vanilla 1.21 onwards. */
    private static final byte CHUNK_VERSION = 42;
    /** FinalizedState: generation is done, so vanilla doesn't decorate the chunk again. */
    private static final int FINALIZED = 2;

    static {
        // Reading the database through memory-mapped files leaves the files locked on
        // Windows until the JVM gets round to unmapping them, and newer JVMs warn about the
        // way the library unmaps them. Plain file reads are fast enough for a world.
        if (System.getProperty("leveldb.mmap") == null) {
            System.setProperty("leveldb.mmap", "false");
        }
    }

    private final DB db;

    private ChunkStorage(DB db) {
        this.db = db;
    }

    /** Opens the database in this folder, creating an empty one if there isn't one yet. */
    public static ChunkStorage open(Path directory) throws IOException {
        Options options = new Options()
                .createIfMissing(true)
                // What vanilla writes: raw deflate. Records compressed as zlib, which older
                // versions wrote, are read just the same.
                .compressionType(CompressionType.ZLIB_RAW);
        return new ChunkStorage(Iq80DBFactory.factory.open(directory.toFile(), options));
    }

    /**
     * The chunk at these coordinates as it was saved, or null if the world doesn't have it.
     * A chunk with parts that can't be read comes back with what could be, marked so it's
     * never saved over.
     */
    public Chunk load(int chunkX, int chunkZ, int defaultBiome) {
        if (db.get(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.VERSION)) == null
                && db.get(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.LEGACY_VERSION)) == null) {
            return null;
        }
        Chunk chunk = new Chunk(chunkX, chunkZ, defaultBiome);
        chunk.markStored();
        List<String> problems = new ArrayList<>();

        for (int index = 0; index < Chunk.SUB_CHUNK_COUNT; index++) {
            byte[] record = db.get(ChunkKeys.subChunk(chunkX, chunkZ, index + Chunk.MIN_SUB_CHUNK_Y));
            if (record == null) {
                continue;
            }
            try {
                chunk.setSubChunk(index, SubChunkFormat.decode(record));
            } catch (IOException | RuntimeException e) {
                problems.add("sub-chunk " + (index + Chunk.MIN_SUB_CHUNK_Y) + ": " + e.getMessage());
            }
        }

        byte[] data3d = db.get(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.DATA_3D));
        if (data3d != null) {
            chunk.setBiomes(BiomeFormat.sectionsOf3d(data3d));
        } else {
            byte[] data2d = db.get(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.DATA_2D));
            if (data2d != null) {
                chunk.setBiomes(BiomeFormat.sectionsOf2d(data2d));
            }
        }

        byte[] blockEntities = db.get(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.BLOCK_ENTITIES));
        if (blockEntities != null) {
            try {
                chunk.setBlockEntities(readCompounds(blockEntities));
            } catch (IOException | RuntimeException e) {
                problems.add("block entities: " + e.getMessage());
            }
        }

        chunk.clearChanges();
        if (!problems.isEmpty()) {
            chunk.markReadOnly();
            Logger.warn("Chunk " + chunkX + ", " + chunkZ + " could only be read in part, so changes to it "
                    + "won't be saved (" + String.join("; ", problems) + ").");
        }
        return chunk;
    }

    /** Writes whatever has changed in the chunk since it was loaded or last saved. */
    public void save(Chunk chunk) throws IOException {
        Chunk.Changes changes = chunk.takeChanges();
        if (changes == null) {
            return;
        }
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        try (WriteBatch batch = db.createWriteBatch()) {
            if (changes.newToStorage()) {
                batch.put(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.VERSION), new byte[]{CHUNK_VERSION});
                batch.put(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.FINALIZED_STATE), intLE(FINALIZED));
                batch.put(ChunkKeys.of(chunkX, chunkZ, ChunkKeys.DATA_3D), BiomeFormat.data3d(chunk.getDefaultBiome()));
            }
            for (int index = 0; index < Chunk.SUB_CHUNK_COUNT; index++) {
                if ((changes.changedSubChunks() & (1 << index)) == 0) {
                    continue;
                }
                int subChunkY = index + Chunk.MIN_SUB_CHUNK_Y;
                byte[] key = ChunkKeys.subChunk(chunkX, chunkZ, subChunkY);
                byte[] record = SubChunkFormat.encode(changes.subChunks()[index], subChunkY);
                if (record != null) {
                    batch.put(key, record);
                } else {
                    batch.delete(key);
                }
            }
            if (changes.blockEntities() != null) {
                byte[] key = ChunkKeys.of(chunkX, chunkZ, ChunkKeys.BLOCK_ENTITIES);
                if (changes.blockEntities().isEmpty()) {
                    batch.delete(key);
                } else {
                    batch.put(key, writeCompounds(changes.blockEntities()));
                }
            }
            db.write(batch);
            chunk.markStored();
        } catch (IOException | RuntimeException e) {
            chunk.restoreChanges(changes);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        db.close();
    }

    private static List<NbtMap> readCompounds(byte[] bytes) throws IOException {
        List<NbtMap> compounds = new ArrayList<>();
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try (NBTInputStream in = NbtUtils.createReaderLE(new ByteBufInputStream(buf))) {
            while (buf.isReadable()) {
                if (!(in.readTag() instanceof NbtMap compound)) {
                    throw new IOException("expected a compound");
                }
                compounds.add(compound);
            }
        }
        return compounds;
    }

    private static byte[] writeCompounds(List<NbtMap> compounds) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try (NBTOutputStream out = NbtUtils.createWriterLE(new ByteBufOutputStream(buf))) {
            for (NbtMap compound : compounds) {
                out.writeTag(compound);
            }
        }
        return ByteBufUtil.getBytes(buf);
    }

    private static byte[] intLE(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }
}
