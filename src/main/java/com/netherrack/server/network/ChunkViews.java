package com.netherrack.server.network;

import com.netherrack.server.util.Logger;
import com.netherrack.server.world.World;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every connected player's {@link ChunkView}, driven by the server tick: each tick sends
 * every player the next few chunks they're waiting for, and every few seconds the world
 * lets go of the chunks nobody is near any more - saving them first if they've changed.
 */
public final class ChunkViews {

    /**
     * Chunks sent to each player per tick, at most: 320 a second, enough to fill a large
     * view in a few seconds without holding up the tick.
     */
    private static final int CHUNKS_PER_TICK = 16;

    /** How often chunks no player is near are unloaded: every 5 seconds. */
    private static final int UNLOAD_INTERVAL_TICKS = 100;

    private final World world;
    private final Set<ChunkView> views = ConcurrentHashMap.newKeySet();
    private long ticks;

    public ChunkViews(World world) {
        this.world = world;
    }

    public void add(ChunkView view) {
        views.add(view);
    }

    public void remove(ChunkView view) {
        views.remove(view);
    }

    /** One server tick. */
    public void tick() {
        for (ChunkView view : views) {
            view.sendNext(CHUNKS_PER_TICK);
        }
        if (++ticks % UNLOAD_INTERVAL_TICKS == 0) {
            unloadUnused();
        }
    }

    private void unloadUnused() {
        Set<Long> wanted = new HashSet<>();
        for (ChunkView view : views) {
            view.addWanted(wanted);
        }
        int unloaded = world.unloadChunksExcept(wanted);
        if (unloaded > 0) {
            Logger.debug("Unloaded " + unloaded + " chunks no player is near; " + world.getLoadedChunkCount()
                    + " still loaded.");
        }
    }
}
