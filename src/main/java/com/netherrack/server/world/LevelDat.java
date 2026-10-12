package com.netherrack.server.world;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A world's level.dat: the small file next to its database describing the world itself
 * (name, generator, spawn, game rules...), separate from its chunks.
 * <p>
 * Format: an 8-byte header (the storage version, then the payload's length, both
 * little-endian ints), then the level data itself as one uncompressed little-endian NBT
 * compound.
 */
public final class LevelDat {

    /** The storage version 1.26 writes. */
    public static final int STORAGE_VERSION = 10;

    private LevelDat() {
    }

    public static NbtMap read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 8) {
            throw new IOException(file + " is too short to be a level.dat");
        }
        int length = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > bytes.length - 8) {
            // Some tools write the header's length wrong; the compound says where it ends anyway.
            length = bytes.length - 8;
        }
        try (NBTInputStream in = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes, 8, length))) {
            if (!(in.readTag() instanceof NbtMap level)) {
                throw new IOException(file + " doesn't hold a compound");
            }
            return level;
        }
    }

    public static void write(Path file, NbtMap level) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (NBTOutputStream out = NbtUtils.createWriterLE(payload)) {
            out.writeTag(level);
        }
        byte[] payloadBytes = payload.toByteArray();
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(level.getInt("StorageVersion", STORAGE_VERSION));
        header.putInt(payloadBytes.length);

        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        contents.write(header.array());
        contents.write(payloadBytes);
        Files.write(file, contents.toByteArray());
    }
}
