package com.netherrack.server.world;

/**
 * Generates nothing, for worlds whose terrain Netherrack can't generate yet (vanilla's
 * infinite worlds, say): only what the world has saved is there, and everywhere else is
 * left empty.
 */
public class EmptyGenerator implements WorldGenerator {

    private final String id;

    /** @param id the name of the terrain this stands in for, as the console shows it */
    public EmptyGenerator(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void generate(Chunk chunk) {
    }
}
