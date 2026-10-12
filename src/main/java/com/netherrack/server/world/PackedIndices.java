package com.netherrack.server.world;

import io.netty.buffer.ByteBuf;

/**
 * How Bedrock packs a 16x16x16 section's palette indices, the same way on disk and on the
 * network: as many indices as fit into each little-endian 32-bit word, never one split
 * across two words (so 3, 5 and 6 bits leave a few unused bits at the top of each word).
 * A section with a single palette entry has no words at all - every index is 0.
 */
public final class PackedIndices {

    /** The index widths Bedrock allows, apart from 0. */
    private static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    private PackedIndices() {
    }

    /** The narrowest allowed width that can index a palette of this size. */
    public static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        for (int bits : WIDTHS) {
            if ((1 << bits) >= paletteSize) {
                return bits;
            }
        }
        return 16;
    }

    public static boolean isValidWidth(int bits) {
        if (bits == 0) {
            return true;
        }
        for (int width : WIDTHS) {
            if (width == bits) {
                return true;
            }
        }
        return false;
    }

    public static void write(ByteBuf out, int[] indices, int bits) {
        if (bits == 0) {
            return;
        }
        int perWord = 32 / bits;
        int mask = (1 << bits) - 1;
        int position = 0;
        while (position < BlockLayer.SIZE) {
            int word = 0;
            for (int i = 0; i < perWord && position < BlockLayer.SIZE; i++, position++) {
                word |= (indices[position] & mask) << (i * bits);
            }
            out.writeIntLE(word);
        }
    }

    /** Reads {@link BlockLayer#SIZE} indices of a valid width. */
    public static int[] read(ByteBuf in, int bits) {
        int[] indices = new int[BlockLayer.SIZE];
        if (bits == 0) {
            return indices;
        }
        int perWord = 32 / bits;
        int mask = (1 << bits) - 1;
        int position = 0;
        while (position < BlockLayer.SIZE) {
            int word = in.readIntLE();
            for (int i = 0; i < perWord && position < BlockLayer.SIZE; i++, position++) {
                indices[position] = (word >>> (i * bits)) & mask;
            }
        }
        return indices;
    }
}
