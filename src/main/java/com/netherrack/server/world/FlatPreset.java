package com.netherrack.server.world;

import java.util.Locale;

/**
 * The game's own flat world presets, which flat-world-preset in server.properties picks
 * from for a new level. Each is saved in level.dat the way the game saves it, so the game
 * shows which preset the world was made with.
 * <p>
 * Layers are listed from the bottom of the world (y -64) up. Classic Flat and Overworld are
 * as the game itself saves them; the others are as the Minecraft Wiki lists them.
 */
public enum FlatPreset {

    CLASSIC_FLAT("ClassicFlat", Biomes.PLAINS,
            "minecraft:bedrock", 1, "minecraft:dirt", 2, "minecraft:grass_block", 1),
    TUNNELERS_DREAM("TunnelersDream", Biomes.WINDSWEPT_HILLS,
            "minecraft:bedrock", 1, "minecraft:stone", 230, "minecraft:dirt", 5, "minecraft:grass_block", 1),
    WATER_WORLD("WaterWorld", Biomes.DEEP_OCEAN,
            "minecraft:bedrock", 1, "minecraft:deepslate", 64, "minecraft:stone", 5, "minecraft:dirt", 5,
            "minecraft:gravel", 5, "minecraft:water", 90),
    OVERWORLD("Overworld", Biomes.PLAINS,
            "minecraft:bedrock", 1, "minecraft:stone", 59, "minecraft:dirt", 3, "minecraft:grass_block", 1),
    SNOWY_KINGDOM("SnowyKingdom", Biomes.SNOWY_PLAINS,
            "minecraft:bedrock", 1, "minecraft:stone", 59, "minecraft:dirt", 3, "minecraft:grass_block", 1,
            "minecraft:snow_layer", 1),
    BOTTOMLESS_PIT("BottomlessPit", Biomes.PLAINS,
            "minecraft:cobblestone", 2, "minecraft:dirt", 3, "minecraft:grass_block", 1),
    DESERT("Desert", Biomes.DESERT,
            "minecraft:bedrock", 1, "minecraft:stone", 3, "minecraft:sandstone", 52, "minecraft:sand", 8),
    REDSTONE_READY("RedstoneReady", Biomes.DESERT,
            "minecraft:bedrock", 1, "minecraft:stone", 3, "minecraft:sandstone", 116);

    /** Bedrock's numeric ids of the biomes the presets use. */
    private static final class Biomes {
        static final int PLAINS = 1;
        static final int DESERT = 2;
        static final int WINDSWEPT_HILLS = 3;
        static final int SNOWY_PLAINS = 12;
        static final int DEEP_OCEAN = 24;
    }

    private final String id;
    private final int biome;
    private final Object[] layers;

    /** @param layers block name, then how many layers of it, for each layer from the bottom */
    FlatPreset(String id, int biome, Object... layers) {
        this.id = id;
        this.biome = biome;
        this.layers = layers;
    }

    /** The id the game saves the preset as, e.g. "ClassicFlat". */
    public String getId() {
        return id;
    }

    /** The name server.properties uses for it, e.g. "classic-flat". */
    public String getSettingName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The preset as level.dat's FlatWorldLayers, which FlatGenerator reads. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"biome_id\":").append(biome).append(",\"block_layers\":[");
        for (int i = 0; i < layers.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"block_name\":\"").append(layers[i]).append("\",\"count\":").append(layers[i + 1]).append('}');
        }
        return json.append("],\"encoding_version\":6,\"preset_id\":\"").append(id)
                .append("\",\"world_version\":\"version.post_1_18\"}").toString();
    }

    /**
     * The preset by name, as server.properties or the game writes it - "classic-flat",
     * "ClassicFlat" and "Classic Flat" are all the same - or null if it's none of them.
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
