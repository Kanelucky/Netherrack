package com.netherrack.server.network;

import com.netherrack.server.block.Block;
import com.netherrack.server.block.Blocks;
import com.netherrack.server.util.Logger;
import com.netherrack.server.world.BlockLayer;
import com.netherrack.server.world.Chunk;
import com.netherrack.server.world.PackedIndices;
import com.netherrack.server.world.SubChunk;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.io.IOException;
import java.util.List;

/**
 * Encodes a Chunk into the raw bytes LevelChunkPacket carries in its "data" field - the
 * library doesn't do this part for you, there's no chunk/block-storage helper in Protocol,
 * so this follows Bedrock's own binary chunk format by hand:
 * <p>
 * Every sub-chunk in the dimension, bottom to top -> one biome section per sub-chunk ->
 * border block count (always 0) -> the chunk's block entities.
 * <p>
 * Per sub-chunk: version byte (9, the modern format with an explicit Y index) -> storage
 * layer count (always 2: blocks, then the layer of water around waterlogged blocks) ->
 * signed Y index -> each layer (palette + bit-packed indices, see PackedIndices).
 * <p>
 * Sending only up to the highest non-empty sub-chunk, with zero-layer empty ones, parses
 * fine but real clients disconnected with a generic "Block" error on it. Real clients accept
 * the full-height, always-two-layer shape (confirmed by comparing against what AllayMC, a
 * working server on the same protocol library, puts on the wire), so that's what's sent.
 * <p>
 * This is the saved format with two differences: palettes hold blocks' network hashes and
 * biome ids as zigzag varints rather than NBT and little-endian ints, and each layer's
 * header has its low bit set to say so.
 */
public final class ChunkEncoder {

    /** What the LevelChunkPacket carrying {@link #encodeChunk(Chunk)} must set as subChunksLength. */
    public static final int SUB_CHUNK_COUNT = Chunk.SUB_CHUNK_COUNT;

    /** A biome section header meaning "the same biomes as the sub-chunk below". */
    private static final int SAME_AS_BELOW = 0xFF;

    private ChunkEncoder() {
    }

    public static ByteBuf encodeChunk(Chunk chunk) {
        ByteBuf buf = Unpooled.buffer();
        synchronized (chunk) {
            int[] indices = new int[BlockLayer.SIZE];
            for (int index = 0; index < Chunk.SUB_CHUNK_COUNT; index++) {
                writeSubChunk(buf, chunk.getSubChunk(index), index + Chunk.MIN_SUB_CHUNK_Y, indices);
            }
            writeBiomes(buf, chunk.getBiomes(), chunk.getDefaultBiome());
            buf.writeByte(0); // border blocks: none
            writeBlockEntities(buf, chunk.getBlockEntities());
        }
        return buf;
    }

    private static void writeSubChunk(ByteBuf buf, SubChunk subChunk, int subChunkY, int[] indices) {
        buf.writeByte(9); // sub-chunk format version
        buf.writeByte(2); // storage layer count
        buf.writeByte(subChunkY);

        List<BlockLayer> layers = subChunk != null ? subChunk.getLayers() : List.of();
        for (int i = 0; i < 2; i++) {
            if (i < layers.size()) {
                writeBlockLayer(buf, layers.get(i), indices);
            } else {
                writeAirLayer(buf);
            }
        }
    }

    /** A layer that's all air: a single-entry palette, so no indices. */
    private static void writeAirLayer(ByteBuf buf) {
        buf.writeByte((0 << 1) | 1);
        VarInts.writeInt(buf, Blocks.AIR.getBlockStateHash());
    }

    private static void writeBlockLayer(ByteBuf buf, BlockLayer layer, int[] indices) {
        // These are Bedrock's "hashed block network IDs", not palette-ordinal runtime IDs -
        // see Block.getBlockStateHash().
        List<Block> palette = layer.usedPalette(indices);
        int bitsPerBlock = PackedIndices.bitsFor(palette.size());

        buf.writeByte((bitsPerBlock << 1) | 1); // low bit set = palette is a runtime-ID palette
        PackedIndices.write(buf, indices, bitsPerBlock);

        // With 0 bits per block the palette size isn't written - it's implicitly 1.
        if (bitsPerBlock != 0) {
            VarInts.writeInt(buf, palette.size());
        }
        for (Block block : palette) {
            VarInts.writeInt(buf, block.getBlockStateHash());
        }
    }

    /**
     * A biome section for each sub-chunk: the chunk's saved biomes (see Chunk.getBiomes())
     * in network form, or its default biome everywhere. A section saved as "the same as
     * below" is sent as a copy of the one below. Saved biomes that end early, or can't be
     * read, are continued from the last good section.
     */
    private static void writeBiomes(ByteBuf buf, byte[] saved, int defaultBiome) {
        ByteBuf in = saved != null ? Unpooled.wrappedBuffer(saved) : null;
        ByteBuf previous = null;
        for (int i = 0; i < Chunk.SUB_CHUNK_COUNT; i++) {
            ByteBuf section = null;
            if (in != null && in.isReadable()) {
                try {
                    section = readBiomeSection(in, previous);
                } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                    Logger.debug("Unreadable saved biomes, continuing from the last good section: " + e);
                    in = null;
                }
            }
            if (section == null) {
                section = previous != null ? previous : singleBiome(defaultBiome);
            }
            buf.writeBytes(section, section.readerIndex(), section.readableBytes());
            previous = section;
        }
    }

    private static ByteBuf readBiomeSection(ByteBuf in, ByteBuf previous) {
        int header = in.readUnsignedByte();
        if (header == SAME_AS_BELOW) {
            return previous;
        }
        int bits = header >> 1;
        if (!PackedIndices.isValidWidth(bits)) {
            throw new IllegalArgumentException("biome index width " + bits);
        }
        ByteBuf section = Unpooled.buffer();
        section.writeByte((bits << 1) | 1);
        // The packed indices are laid out the same way in both forms.
        section.writeBytes(in, bits == 0 ? 0 : wordCount(bits) * 4);
        int paletteSize = bits == 0 ? 1 : in.readIntLE();
        if (paletteSize < 1 || paletteSize > BlockLayer.SIZE) {
            throw new IllegalArgumentException("a biome palette of " + paletteSize);
        }
        if (bits != 0) {
            VarInts.writeInt(section, paletteSize);
        }
        for (int i = 0; i < paletteSize; i++) {
            VarInts.writeInt(section, in.readIntLE());
        }
        return section;
    }

    private static ByteBuf singleBiome(int biome) {
        ByteBuf section = Unpooled.buffer();
        section.writeByte((0 << 1) | 1);
        VarInts.writeInt(section, biome);
        return section;
    }

    private static int wordCount(int bits) {
        int perWord = 32 / bits;
        return (BlockLayer.SIZE + perWord - 1) / perWord;
    }

    /** Each block entity as network NBT, one after another until the data ends. */
    private static void writeBlockEntities(ByteBuf buf, List<NbtMap> blockEntities) {
        if (blockEntities.isEmpty()) {
            return;
        }
        // Encoded apart first, so one that can't be encoded leaves no half-written data behind.
        ByteBuf encoded = Unpooled.buffer();
        try (NBTOutputStream out = NbtUtils.createNetworkWriter(new ByteBufOutputStream(encoded))) {
            for (NbtMap blockEntity : blockEntities) {
                out.writeTag(blockEntity);
            }
        } catch (IOException | RuntimeException e) {
            Logger.debug("Could not encode a chunk's block entities, so they're left out: " + e);
            return;
        }
        buf.writeBytes(encoded);
    }
}
