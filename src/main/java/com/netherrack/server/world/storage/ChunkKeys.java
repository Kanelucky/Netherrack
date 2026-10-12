package com.netherrack.server.world.storage;

/**
 * Keys of a chunk's records in a world's database, as vanilla writes them: the chunk's x
 * and z (each a little-endian int), then a byte saying what the record holds, then - for
 * a sub-chunk's blocks only - which sub-chunk, as a signed byte (-4 for y -64 to -49).
 * Overworld keys are that alone; the Nether's and the End's would put their dimension's
 * id between the coordinates and the record byte, but Netherrack only has the overworld.
 */
final class ChunkKeys {

    /** The height map and the biomes of every sub-chunk (since 1.18). */
    static final byte DATA_3D = 0x2B;
    /** The chunk format version: a single byte. Every chunk vanilla saved has one. */
    static final byte VERSION = 0x2C;
    /** The height map and a biome per column (before 1.18). */
    static final byte DATA_2D = 0x2D;
    /** One sub-chunk's blocks. */
    static final byte SUB_CHUNK = 0x2F;
    /** Block entities: one little-endian NBT compound after another. */
    static final byte BLOCK_ENTITIES = 0x31;
    /** How far generation got: a little-endian int, 2 once it's done. */
    static final byte FINALIZED_STATE = 0x36;
    /** The chunk format version, in worlds from before 1.16.100. */
    static final byte LEGACY_VERSION = 0x76;

    private ChunkKeys() {
    }

    static byte[] of(int chunkX, int chunkZ, byte record) {
        byte[] key = new byte[9];
        writeCoordinates(key, chunkX, chunkZ);
        key[8] = record;
        return key;
    }

    /** @param subChunkY the sub-chunk's vertical index: its lowest y divided by 16 */
    static byte[] subChunk(int chunkX, int chunkZ, int subChunkY) {
        byte[] key = new byte[10];
        writeCoordinates(key, chunkX, chunkZ);
        key[8] = SUB_CHUNK;
        key[9] = (byte) subChunkY;
        return key;
    }

    private static void writeCoordinates(byte[] key, int chunkX, int chunkZ) {
        for (int i = 0; i < 4; i++) {
            key[i] = (byte) (chunkX >> (8 * i));
            key[4 + i] = (byte) (chunkZ >> (8 * i));
        }
    }
}
