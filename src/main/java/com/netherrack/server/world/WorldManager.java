package com.netherrack.server.world;

import com.netherrack.server.setup.Lang;
import com.netherrack.server.util.Logger;
import com.netherrack.server.world.storage.ChunkStorage;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a world from disk, or creates a brand new one if it doesn't exist yet. Worlds are
 * kept the way Bedrock Dedicated Server keeps them, in worlds/&lt;level-name&gt;/:
 * <ul>
 *   <li>level.dat: the world's settings (see LevelDat);</li>
 *   <li>levelname.txt: its name as players see it;</li>
 *   <li>db/: its chunks, in a LevelDB database (see ChunkStorage).</li>
 * </ul>
 * So a world folder from Bedrock Dedicated Server, or one exported from the game, can be
 * dropped in there, and a world Netherrack made opens in the game.
 */
public class WorldManager {

    /** Where worlds are kept, as Bedrock Dedicated Server keeps them. */
    public static final Path WORLDS_DIRECTORY = Path.of("worlds");

    private static final String LEVEL_DAT = "level.dat";
    private static final String LEVEL_NAME_FILE = "levelname.txt";
    private static final String DATABASE_DIRECTORY = "db";

    /** @throws IOException if the world can't be read, or a new one can't be written */
    public World loadOrCreate(String levelName) throws IOException {
        Path directory = WORLDS_DIRECTORY.resolve(levelName);
        Path levelDat = directory.resolve(LEVEL_DAT);

        NbtMap level;
        if (Files.exists(levelDat)) {
            level = LevelDat.read(levelDat);
        } else {
            Logger.warn(Lang.current.get("server.level_not_found", levelName));
            level = newLevel(levelName);
            Files.createDirectories(directory);
            LevelDat.write(levelDat, level);
            Files.writeString(directory.resolve(LEVEL_NAME_FILE), levelName, StandardCharsets.UTF_8);
        }

        WorldGenerator generator = generatorFor(level);
        Logger.info(Lang.current.get("server.loading_level", levelName, generator.getId()));
        if (generator instanceof EmptyGenerator) {
            Logger.warn(Lang.current.get("server.generator_unsupported", generator.getId()));
        }

        ChunkStorage storage = ChunkStorage.open(directory.resolve(DATABASE_DIRECTORY));
        return new World(displayName(directory, level, levelName), generator, storage, level);
    }

    /**
     * The level.dat of a new world: Netherrack's flat world. Vanilla fills in anything it
     * needs that isn't here (game rules and the like) when it opens the world.
     */
    private static NbtMap newLevel(String levelName) {
        List<Integer> version = gameVersion();
        return NbtMap.builder()
                .putString("LevelName", levelName)
                .putInt("StorageVersion", LevelDat.STORAGE_VERSION)
                .putInt("NetworkVersion", Bedrock_v2193.CODEC.getProtocolVersion())
                .putList("lastOpenedWithVersion", NbtType.INT, version)
                .putList("MinimumCompatibleClientVersion", NbtType.INT, version)
                .putString("InventoryVersion", Bedrock_v2193.CODEC.getMinecraftVersion())
                .putInt("WorldVersion", 1) // made since 1.18: y -64 to 319
                .putInt("GameType", 0) // survival
                .putInt("Generator", 2) // flat
                .putString("FlatWorldLayers", FlatGenerator.DEFAULT_LAYERS)
                .putInt("SpawnX", 0)
                .putInt("SpawnY", Short.MAX_VALUE) // "find the ground", as vanilla saves it
                .putInt("SpawnZ", 0)
                .putLong("RandomSeed", 0L)
                .putLong("Time", 0L)
                .putLong("currentTick", 0L)
                .putLong("LastPlayed", System.currentTimeMillis() / 1000L)
                .putInt("Difficulty", 1) // easy
                .putByte("commandsEnabled", (byte) 1)
                .putByte("hasBeenLoadedInCreative", (byte) 0)
                .putByte("immutableWorld", (byte) 0)
                .putByte("isCreatedInEditor", (byte) 0)
                .build();
    }

    /** The game version as level.dat lists it: e.g. 1.26.50 is [1, 26, 50, 0, 0]. */
    private static List<Integer> gameVersion() {
        List<Integer> version = new ArrayList<>();
        for (String part : Bedrock_v2193.CODEC.getMinecraftVersion().split("\\.")) {
            version.add(Integer.parseInt(part));
        }
        while (version.size() < 5) {
            version.add(0);
        }
        return version;
    }

    /**
     * The terrain to generate where the world has nothing saved: flat worlds' own layers.
     * Netherrack can't generate other kinds of terrain yet, so those get nothing.
     */
    private static WorldGenerator generatorFor(NbtMap level) {
        int type = level.getInt("Generator", 2);
        if (type != 2) {
            return new EmptyGenerator(switch (type) {
                case 0 -> "old";
                case 1 -> "infinite";
                default -> "type " + type;
            });
        }
        String layers = level.getString("FlatWorldLayers", "");
        if (layers.isBlank()) {
            // Vanilla's own flat world, for a flat world that doesn't say which layers it has.
            layers = "{\"biome_id\":1,\"block_layers\":[{\"block_name\":\"minecraft:bedrock\",\"count\":1},"
                    + "{\"block_name\":\"minecraft:dirt\",\"count\":2},{\"block_name\":\"minecraft:grass_block\","
                    + "\"count\":1}],\"encoding_version\":6,\"structure_options\":null,"
                    + "\"world_version\":\"version.post_1_18\"}";
        }
        try {
            return new FlatGenerator(layers);
        } catch (IllegalArgumentException e) {
            Logger.warn("This flat world's layers can't be read (" + e.getMessage() + "), so nothing is generated.");
            return new EmptyGenerator("flat");
        }
    }

    /** The world's name as players see it: levelname.txt's, else level.dat's, else the folder's. */
    private static String displayName(Path directory, NbtMap level, String levelName) {
        try {
            String text = Files.readString(directory.resolve(LEVEL_NAME_FILE), StandardCharsets.UTF_8);
            String name = text.replace("﻿", "").lines().findFirst().orElse("").trim();
            if (!name.isEmpty()) {
                return name;
            }
        } catch (IOException e) {
            // No levelname.txt: fall back to level.dat's name.
        }
        String name = level.getString("LevelName", "").trim();
        return name.isEmpty() ? levelName : name;
    }
}
