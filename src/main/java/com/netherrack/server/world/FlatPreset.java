package com.netherrack.server.world;

import java.util.Locale;

/**
 * The game's own flat world presets, which flat-world-preset in server.properties picks
 * from for a new level. Each is saved in level.dat the way the game saves it, so the game
 * shows which preset the world was made with.
 * <p>
 * Layers are listed from the bottom of the world (y -64) up, as the game itself saves them
 * (checked against worlds the game made with each preset).
 * <p>
 * The Void has no layers at all - the game leaves "block_layers" out of its level.dat - just
 * a stone platform for players to start on, see {@link #hasStartPlatform()}.
 */
public enum FlatPreset {

    CLASSIC_FLAT("ClassicFlat", "Classic Flat", Biomes.PLAINS,
            "minecraft:bedrock", 1, "minecraft:dirt", 2, "minecraft:grass_block", 1),
    TUNNELERS_DREAM("TunnelersDream", "Tunneler's Dream", Biomes.WINDSWEPT_HILLS,
            "minecraft:bedrock", 1, "minecraft:stone", 230, "minecraft:dirt", 5, "minecraft:grass_block", 1),
    WATER_WORLD("WaterWorld", "Water World", Biomes.DEEP_OCEAN,
            "minecraft:bedrock", 1, "minecraft:deepslate", 64, "minecraft:stone", 5, "minecraft:dirt", 5,
            "minecraft:gravel", 5, "minecraft:water", 90),
    OVERWORLD("Overworld", "Overworld", Biomes.PLAINS,
            "minecraft:bedrock", 1, "minecraft:stone", 59, "minecraft:dirt", 3, "minecraft:grass_block", 1),
    SNOWY_KINGDOM("SnowyKingdom", "Snowy Kingdom", Biomes.SNOWY_PLAINS,
            "minecraft:bedrock", 1, "minecraft:stone", 59, "minecraft:dirt", 3, "minecraft:grass_block", 1,
            "minecraft:snow_layer", 1),
    BOTTOMLESS_PIT("BottomlessPit", "Bottomless Pit", Biomes.PLAINS,
            "minecraft:cobblestone", 2, "minecraft:dirt", 3, "minecraft:grass_block", 1),
    DESERT("Desert", "Desert", Biomes.DESERT,
            "minecraft:bedrock", 1, "minecraft:stone", 3, "minecraft:sandstone", 52, "minecraft:sand", 8),
    REDSTONE_READY("RedstoneReady", "Redstone Ready", Biomes.DESERT,
            "minecraft:bedrock", 1, "minecraft:stone", 3, "minecraft:sandstone", 116),
    THE_VOID("TheVoid", "The Void", Biomes.PLAINS);

    /** The Void's start platform: stone, this many blocks out from the middle each way, at the bottom of the world. */
    public static final int START_PLATFORM_RADIUS = 16;

    /** Bedrock's numeric ids of the biomes the presets use. */
    private static final class Biomes {
        static final int PLAINS = 1;
        static final int DESERT = 2;
        static final int WINDSWEPT_HILLS = 3;
        static final int SNOWY_PLAINS = 12;
        static final int DEEP_OCEAN = 24;
    }

    private final String id;
    private final String displayName;
    private final int biome;
    private final Object[] layers;

    /**
     * @param displayName the preset's name as the game shows it
     * @param layers      block name, then how many layers of it, for each layer from the bottom
     */
    FlatPreset(String id, String displayName, int biome, Object... layers) {
        this.id = id;
        this.displayName = displayName;
        this.biome = biome;
        this.layers = layers;
    }

    /** The id the game saves the preset as, e.g. "ClassicFlat". */
    public String getId() {
        return id;
    }

    /** The preset's name as the game shows it, and as server.properties names it: e.g. "Classic Flat". */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whether players start on a platform rather than on the layers: The Void's 33x33 stone
     * square at the bottom of the world, around the spawn, with a cobblestone block in the
     * middle.
     */
    public boolean hasStartPlatform() {
        return this == THE_VOID;
    }

    /** The preset as level.dat's FlatWorldLayers, which FlatGenerator reads. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"biome_id\":").append(biome);
        if (layers.length > 0) {
            json.append(",\"block_layers\":[");
            for (int i = 0; i < layers.length; i += 2) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"block_name\":\"").append(layers[i]).append("\",\"count\":").append(layers[i + 1])
                        .append('}');
            }
            json.append(']');
        }
        return json.append(",\"encoding_version\":6,\"preset_id\":\"").append(id)
                .append("\",\"world_version\":\"version.post_1_18\"}").toString();
    }

    /**
     * The preset by name: as server.properties names it ("Classic Flat") or as the game saves
     * it ("ClassicFlat"), ignoring case, spaces and punctuation. Null if it's none of them.
     */
    public static FlatPreset fromName(String name) {
        String wanted = simplified(name);
        for (FlatPreset preset : values()) {
            if (simplified(preset.id).equals(wanted)) {
                return preset;
            }
        }
        return null;
    }

    private static String simplified(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
