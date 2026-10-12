package com.netherrack.server.world.storage;

import com.netherrack.server.world.BlockLayer;
import com.netherrack.server.world.Chunk;
import com.netherrack.server.world.PackedIndices;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk's biomes, in vanilla's format. Since 1.18 they're in the chunk's "Data3D"
 * record: a height map (256 little-endian shorts) and then a section per sub-chunk from
 * the bottom up, laid out like a layer of blocks - a header byte (index width shifted
 * left by one), packed indices, the palette's size as a little-endian int (left out when
 * it has one entry) and the biome ids, little-endian ints. A header of 0xFF means "the
 * same as the section below".
 * <p>
 * Before 1.18, "Data2D" had a height map and then one biome id byte per column.
 */
final class BiomeFormat {

    private static final int HEIGHT_MAP_SIZE = 256 * 2;

    private BiomeFormat() {
    }

    /** The biome sections of a Data3D record: what Chunk.getBiomes() holds. */
    static byte[] sectionsOf3d(byte[] data3d) {
        if (data3d.length <= HEIGHT_MAP_SIZE) {
            return null;
        }
        byte[] sections = new byte[data3d.length - HEIGHT_MAP_SIZE];
        System.arraycopy(data3d, HEIGHT_MAP_SIZE, sections, 0, sections.length);
        return sections;
    }

    /** A Data2D record's biome per column, as the sections Data3D would have. */
    static byte[] sectionsOf2d(byte[] data2d) {
        if (data2d.length < HEIGHT_MAP_SIZE + 256) {
            return null;
        }
        List<Integer> palette = new ArrayList<>();
        int[] columns = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int biome = data2d[HEIGHT_MAP_SIZE + ((z << 4) | x)] & 0xFF;
                int index = palette.indexOf(biome);
                if (index < 0) {
                    index = palette.size();
                    palette.add(biome);
                }
                columns[(x << 4) | z] = index;
            }
        }
        int[] indices = new int[BlockLayer.SIZE];
        for (int i = 0; i < BlockLayer.SIZE; i++) {
            indices[i] = columns[i >> 4];
        }
        ByteBuf buf = Unpooled.buffer();
        for (int i = 0; i < Chunk.SUB_CHUNK_COUNT; i++) {
            writeSection(buf, palette, indices);
        }
        return ByteBufUtil.getBytes(buf);
    }

    /**
     * The Data3D record for a chunk new to the world's database: an empty height map, which
     * vanilla works out again, and one biome everywhere.
     */
    static byte[] data3d(int biome) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeZero(HEIGHT_MAP_SIZE);
        for (int i = 0; i < Chunk.SUB_CHUNK_COUNT; i++) {
            writeSection(buf, List.of(biome), null);
        }
        return ByteBufUtil.getBytes(buf);
    }

    private static void writeSection(ByteBuf buf, List<Integer> palette, int[] indices) {
        int bits = PackedIndices.bitsFor(palette.size());
        buf.writeByte(bits << 1);
        PackedIndices.write(buf, indices, bits);
        if (bits != 0) {
            buf.writeIntLE(palette.size());
        }
        for (int biome : palette) {
            buf.writeIntLE(biome);
        }
    }
}
