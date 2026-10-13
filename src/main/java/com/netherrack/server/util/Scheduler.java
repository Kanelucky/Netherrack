package com.netherrack.server.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Things to do some ticks from now, run by the server tick - like a pressed button popping
 * back out. Tasks are scheduled from players' network threads and run on the tick thread.
 */
public final class Scheduler {

    private long tick;
    private final Map<Long, List<Runnable>> due = new HashMap<>();

    /** Runs a task this many ticks from now (at least the next tick). */
    public synchronized void schedule(int ticks, Runnable task) {
        due.computeIfAbsent(tick + Math.max(1, ticks), key -> new ArrayList<>()).add(task);
    }

    /** One server tick: runs what's due. A task that throws is logged, and the rest still run. */
    public void tick() {
        List<Runnable> now;
        synchronized (this) {
            tick++;
            now = due.remove(tick);
        }
        if (now == null) {
            return;
        }
        for (Runnable task : now) {
            try {
                task.run();
            } catch (RuntimeException e) {
                Logger.error("Error in a scheduled task: " + e);
            }
        }
    }
}
