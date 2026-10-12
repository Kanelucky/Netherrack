package com.netherrack.server.world;

/**
 * Fills a newly-created chunk with blocks. One instance is shared across a whole world.
 */
public interface WorldGenerator {

    /**
     * Short name of the kind of terrain, as the console shows it.
     */
    String getId();

    void generate(Chunk chunk);

    /**
     * Y level new players should spawn on top of. Defaults to 64, the classic "sea level".
     */
    default int getSpawnY() {
        return 64;
    }

    /** The biome of the chunks it generates. Defaults to plains. */
    default int getBiome() {
        return 1;
    }
}
