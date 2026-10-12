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
 * older ones.
 */
public class FlatGenerator implements WorldGenerator {

    /**
     * Netherrack's own flat world: a single layer of grass blocks at y 4, with nothing
     * underneath (air layers up to it).
     */
    public static final String DEFAULT_LAYERS = "{\"biome_id\":1,\"block_layers\":["
            + "{\"block_name\":\"minecraft:air\",\"count\":68},"
            + "{\"block_name\":\"minecraft:grass_block\",\"count\":1}"
            + "],\"encoding_version\":6,\"structure_options\":null,\"world_version\":\"version.post_1_18\"}";

    private final List<Block> layers = new ArrayList<>();
    private final int bottomY;
    private final int biome;

    /** @throws IllegalArgumentException if the layers can't be read */
    public FlatGenerator(String layersJson) {
        Map<String, Object> json;
        try {
            json = JsonUtil.parseJson(layersJson);
        } catch (JoseException e) {
            throw new IllegalArgumentException("the flat world layers aren't valid JSON: " + e.getMessage());
        }
        this.bottomY = "version.post_1_18".equals(json.get("world_version")) ? Chunk.MIN_Y : 0;
        this.biome = json.get("biome_id") instanceof Number id ? id.intValue() : 1;

        if (!(json.get("block_layers") instanceof List<?> blockLayers)) {
            throw new IllegalArgumentException("the flat world layers have no \"block_layers\"");
        }
        for (Object entry : blockLayers) {
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

    @Override
    public String getId() {
        return "flat";
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
    }

    @Override
    public int getSpawnY() {
        return bottomY + layers.size();
    }

    @Override
    public int getBiome() {
        return biome;
    }
}
