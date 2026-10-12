package com.netherrack.server.network;

import com.netherrack.server.world.World;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The chunks one player's client has, kept up to date as they move: every chunk within
 * their view radius of the view's centre, sent nearest first.
 * <p>
 * Each time the player strays a couple of chunks from that centre, the view moves onto
 * them: the client is told the area it may keep now (NetworkChunkPublisherUpdate, which
 * also lets it drop what's left behind), and the chunks in range it hasn't got are
 * queued. The server tick sends a few of them at a time
 * ({@link #sendNext}), so a large view fills in over a moment rather than holding up the
 * tick. Chunks that fall out of range are forgotten, so they're sent again if the player
 * comes back.
 * <p>
 * Movement arrives on the player's network thread and the sending happens on the server
 * tick, so every method synchronizes on the view.
 */
public final class ChunkView {

    /**
     * How many chunks the player can stray from the centre of their view before it follows
     * them. Players often step back and forth over a chunk border, and a view that followed
     * every step would drop and resend the same row of chunks each time.
     */
    private static final int RECENTRE_DISTANCE = 2;

    private final BedrockServerSession session;
    private final World world;

    /** In chunks; 0 until the client says how far it can see. */
    private int radius;
    private int centerX;
    private int centerZ;
    private final Set<Long> sent = new HashSet<>();
    private final Deque<Long> queue = new ArrayDeque<>();

    public ChunkView(BedrockServerSession session, World world) {
        this.session = session;
        this.world = world;
    }

    /** Sets how many chunks out the player sees, centred on where they are. */
    public synchronized void setRadius(int radius, Vector3f position) {
        this.radius = radius;
        recenter(position);
    }

    /** Follows the player to where they've moved, once they've strayed far enough from the view's centre. */
    public synchronized void moveTo(Vector3f position) {
        if (radius == 0) {
            return;
        }
        long dx = (position.getFloorX() >> 4) - centerX;
        long dz = (position.getFloorZ() >> 4) - centerZ;
        if (dx * dx + dz * dz >= RECENTRE_DISTANCE * RECENTRE_DISTANCE) {
            recenter(position);
        }
    }

    /** Sends up to this many of the queued chunks, nearest first. */
    public synchronized void sendNext(int count) {
        for (int i = 0; i < count && !queue.isEmpty(); i++) {
            send(queue.poll());
        }
    }

    /** Sends every queued chunk within this many chunks of the player, e.g. the ground under a joining player. */
    public synchronized void sendWithin(int distance) {
        while (!queue.isEmpty() && distanceSquared(queue.peek()) <= distance * distance) {
            send(queue.poll());
        }
    }

    /** Adds the chunks this player has or is about to get, by World.chunkKey, so the world keeps them loaded. */
    public synchronized void addWanted(Set<Long> keys) {
        keys.addAll(sent);
        keys.addAll(queue);
    }

    private void recenter(Vector3f position) {
        centerX = position.getFloorX() >> 4;
        centerZ = position.getFloorZ() >> 4;

        // Ahead of the chunks, so the client already counts the new area as in range when they arrive.
        NetworkChunkPublisherUpdatePacket publisherUpdate = new NetworkChunkPublisherUpdatePacket();
        publisherUpdate.setPosition(Vector3i.from(position.getFloorX(), position.getFloorY(), position.getFloorZ()));
        publisherUpdate.setRadius(radius * 16);
        session.sendPacket(publisherUpdate);

        sent.removeIf(key -> !inRange(key));
        List<Long> wanted = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                long key = World.chunkKey(x, z);
                if (inRange(key) && !sent.contains(key)) {
                    wanted.add(key);
                }
            }
        }
        wanted.sort(Comparator.comparingLong(this::distanceSquared));
        queue.clear();
        queue.addAll(wanted);
    }

    private void send(long key) {
        int chunkX = chunkX(key);
        int chunkZ = chunkZ(key);
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(chunkX);
        packet.setChunkZ(chunkZ);
        packet.setSubChunksLength(ChunkEncoder.SUB_CHUNK_COUNT);
        packet.setCachingEnabled(false);
        packet.setRequestSubChunks(false);
        packet.setData(ChunkEncoder.encodeChunk(world.getChunk(chunkX, chunkZ)));
        session.sendPacket(packet);
        sent.add(key);
    }

    /** Within the view: a circle of the radius around the player's chunk, as vanilla's is. */
    private boolean inRange(long key) {
        return distanceSquared(key) <= (long) radius * radius;
    }

    private long distanceSquared(long key) {
        long dx = chunkX(key) - centerX;
        long dz = chunkZ(key) - centerZ;
        return dx * dx + dz * dz;
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }
}
