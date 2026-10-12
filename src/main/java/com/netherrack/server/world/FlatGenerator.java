package com.netherrack.server.world;

import com.netherrack.server.block.Block;
import com.netherrack.server.block.Blocks;
import com.netherrack.server.network.VanillaData;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a flat world from layers of blocks, from the bottom of the world up, as
 * described the way vanilla describes its flat worlds in level.dat ("FlatWorldLayers"):
 * <pre>
 * {"biome_id":1,"block_layers":[{"block_name":"minecraft:bedrock","count":1},
 *   {"block_name":"minecraft:dirt","count":2},{"block_name":"minecraft:grass_block","count":1}],
 *   "encoding_version":6,"structure_options":null,"world_version":"version.post_1_18"}
 * </pre>
 * Layers start at y -64 for worlds made since 1.18 ("version.post_1_18"), and at y 0 for
 * older ones. Without "block_layers" there are none: the world is empty, apart from The
 * Void preset's start platform.
 */
public class FlatGenerator implements WorldGenerator {

    private final List<Block> layers = new ArrayList<>();
    private final int bottomY;
    private final int biome;
    private final String presetId;
    private final boolean startPlatform;
    private final int spawnX;
    private final int spawnZ;

    /**
     * @param spawnX the world spawn's x, which a start platform is around
     * @param spawnZ the world spawn's z
     * @throws IllegalArgumentException if the layers can't be read
     */
    public FlatGenerator(String layersJson, int spawnX, int spawnZ) {
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        Map<String, Object> json;
        try {
            json = JsonUtil.parseJson(layersJson);
        } catch (JoseException e) {
            throw new IllegalArgumentException("the flat world layers aren't valid JSON: " + e.getMessage());
        }
        this.bottomY = "version.post_1_18".equals(json.get("world_version")) ? Chunk.MIN_Y : 0;
        this.biome = json.get("biome_id") instanceof Number id ? id.intValue() : 1;
        this.presetId = json.get("preset_id") instanceof String id ? id : null;
        FlatPreset preset = presetId != null ? FlatPreset.fromName(presetId) : null;
        this.startPlatform = preset != null && preset.hasStartPlatform();

        Object blockLayers = json.getOrDefault("block_layers", List.of());
        if (!(blockLayers instanceof List<?> entries)) {
            throw new IllegalArgumentException("the flat world's \"block_layers\" aren't a list");
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> layer) || !(layer.get("block_name") instanceof String name)) {
                // Old worlds' layers name their blocks by number, which this version has no table for.
                throw new IllegalArgumentException("a flat world layer has no \"block_name\"");
            }
            int count = layer.get("count") instanceof Number number ? number.intValue() : 1;
            Block block = Blocks.of(VanillaData.get().defaultBlockState(name));
            for (int i = 0; i < count && bottomY + layers.size() <= Chunk.MAX_Y; i++) {
                layers.add(block);
            }
        }
    }

    /** "flat", and which of the game's presets the layers are, if they say. */
    @Override
    public String getId() {
        return presetId != null ? "flat, " + presetId : "flat";
    }

    @Override
    public void generate(Chunk chunk) {
        for (int i = 0; i < layers.size(); i++) {
            Block block = layers.get(i);
            if (block == Blocks.AIR) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    chunk.setBlock(x, bottomY + i, z, block);
                }
            }
        }
        if (startPlatform) {
            generateStartPlatform(chunk);
        }
    }

    /** The part of The Void's start platform in this chunk, if any. */
    private void generateStartPlatform(Chunk chunk) {
        Block stone = Blocks.of(VanillaData.get().defaultBlockState("minecraft:stone"));
        Block cobblestone = Blocks.COBBLESTONE;
        int radius = FlatPreset.START_PLATFORM_RADIUS;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int dx = (chunk.getChunkX() << 4) + x - spawnX;
                int dz = (chunk.getChunkZ() << 4) + z - spawnZ;
                if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) {
                    chunk.setBlock(x, bottomY, z, dx == 0 && dz == 0 ? cobblestone : stone);
                }
            }
        }
    }

    @Override
    public int getSpawnY() {
        return startPlatform ? bottomY + 1 : bottomY + layers.size();
    }

    @Override
    public int getBiome() {
        return biome;
    }
}
