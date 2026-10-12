package com.netherrack.server.world.storage;

import com.netherrack.server.block.Block;
import com.netherrack.server.block.Blocks;
import com.netherrack.server.util.Logger;
import com.netherrack.server.world.BlockLayer;
import com.netherrack.server.world.PackedIndices;
import com.netherrack.server.world.SubChunk;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A sub-chunk's record, in vanilla's format: a version byte (9), how many layers of blocks
 * follow, the sub-chunk's vertical index, then each layer - a header byte (the index
 * width, shifted left by one; the low bit is 0 on disk), the packed indices (see
 * PackedIndices), the palette's size as a little-endian int (left out for a one-entry
 * palette), and the palette itself: one little-endian NBT compound per block state,
 * {"name", "states", "version"}.
 * <p>
 * Versions 8 (the same without the vertical index) and 1 (a single layer, no count) are
 * read too. Anything older stored numeric block ids instead of states, from before 1.2.13,
 * and isn't supported.
 */
final class SubChunkFormat {

    private static final int VERSION = 9;
    private static final AtomicBoolean WARNED_OUTDATED = new AtomicBoolean();

    private SubChunkFormat() {
    }

    /**
     * @param subChunkY the sub-chunk's vertical index
     * @return the record, or null if the sub-chunk is all air: vanilla doesn't save those
     */
    static byte[] encode(SubChunk subChunk, int subChunkY) throws IOException {
        if (subChunk == null || subChunk.isEmpty()) {
            return null;
        }
        // Layers after the first are only kept while they have something in them.
        List<BlockLayer> layers = new ArrayList<>(subChunk.getLayers());
        while (layers.size() > 1 && layers.get(layers.size() - 1).isAll(Blocks.AIR)) {
            layers.remove(layers.size() - 1);
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VERSION);
        buf.writeByte(layers.size());
        buf.writeByte(subChunkY);
        for (BlockLayer layer : layers) {
            writeLayer(buf, layer);
        }
        return ByteBufUtil.getBytes(buf);
    }

    private static void writeLayer(ByteBuf buf, BlockLayer layer) throws IOException {
        int[] indices = new int[BlockLayer.SIZE];
        List<Block> palette = layer.usedPalette(indices);
        int bits = PackedIndices.bitsFor(palette.size());
        buf.writeByte(bits << 1);
        PackedIndices.write(buf, indices, bits);
        if (bits != 0) {
            buf.writeIntLE(palette.size());
        }
        try (NBTOutputStream out = NbtUtils.createWriterLE(new ByteBufOutputStream(buf))) {
            for (Block block : palette) {
                out.writeTag(block.toNbt());
            }
        }
    }

    /** @return the sub-chunk, or null if the record has no layers */
    static SubChunk decode(byte[] bytes) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            int version = buf.readUnsignedByte();
            int layerCount;
            switch (version) {
                case 1 -> layerCount = 1;
                case 8 -> layerCount = buf.readUnsignedByte();
                case 9 -> {
                    layerCount = buf.readUnsignedByte();
                    buf.skipBytes(1); // its vertical index, which the key says too
                }
                default -> throw new IOException("sub-chunk format " + version
                        + " is from before Bedrock 1.2.13 and isn't supported");
            }
            if (layerCount == 0) {
                return null;
            }
            List<BlockLayer> layers = new ArrayList<>();
            for (int i = 0; i < layerCount; i++) {
                layers.add(readLayer(buf));
            }
            return new SubChunk(layers);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("the sub-chunk's data ends early");
        }
    }

    private static BlockLayer readLayer(ByteBuf buf) throws IOException {
        int header = buf.readUnsignedByte();
        int bits = header >> 1;
        if ((header & 1) != 0 || !PackedIndices.isValidWidth(bits)) {
            throw new IOException("unexpected block layer header " + header);
        }
        int[] indices = PackedIndices.read(buf, bits);

        int paletteSize = 1;
        // Vanilla leaves the size out of a one-entry palette; take it if it's there anyway.
        if (bits != 0 || buf.getUnsignedByte(buf.readerIndex()) != NbtType.COMPOUND.getId()) {
            paletteSize = buf.readIntLE();
        }
        if (paletteSize < 1 || paletteSize > BlockLayer.SIZE) {
            throw new IOException("a block palette of " + paletteSize + " entries");
        }

        List<Block> palette = new ArrayList<>(paletteSize);
        try (NBTInputStream in = NbtUtils.createReaderLE(new ByteBufInputStream(buf))) {
            for (int i = 0; i < paletteSize; i++) {
                palette.add(toBlock(in.readTag()));
            }
        }
        for (int index : indices) {
            if (index >= paletteSize) {
                throw new IOException("a block index past the end of its palette");
            }
        }
        return new BlockLayer(palette, indices);
    }

    private static Block toBlock(Object tag) throws IOException {
        if (!(tag instanceof NbtMap state) || !(state.get("name") instanceof String)) {
            throw new IOException("a block palette entry without a name");
        }
        // Worlds from before 1.13 numbered a block's variants ("val") instead of naming its states.
        if (!state.containsKey("states") && state.containsKey("val")) {
            throw new IOException("block states from before Bedrock 1.13 aren't supported");
        }
        if (state.getInt("version", Block.CURRENT_VERSION) < Block.CURRENT_VERSION
                && WARNED_OUTDATED.compareAndSet(false, true)) {
            Logger.warn("This world has blocks saved by an older version of the game. Netherrack can't "
                    + "upgrade them yet, so any whose states have changed since may show up as unknown blocks.");
        }
        return Blocks.of(state);
    }
}
