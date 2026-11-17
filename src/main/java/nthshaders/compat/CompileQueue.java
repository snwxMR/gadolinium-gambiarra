// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import meldexun.nothirium.api.renderer.chunk.IRenderChunkTask;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import nthshaders.compat.ShadersCompat;

/**
 * The build queue of a Nothirium RenderChunkDispatcher (chunkCompileOrder, see CompilePriority): at most "pool threads"
 * builds in the pool at the same time; the others wait here, and the next one is chosen when one finishes or every frame.
 * The pool threads stay as busy as before (whenever a build is waiting and there is a free slot, it leaves).
 */
public final class CompileQueue {

    private static final class Entry {
        final Runnable command;
        final IRenderChunkTask task;
        final AbstractRenderChunk chunk;
        final long queuedAt = System.nanoTime();

        Entry(Runnable command, IRenderChunkTask task, AbstractRenderChunk chunk) {
            this.command = command;
            this.task = task;
            this.chunk = chunk;
        }
    }

    /** Outside "inViewOnly": the oldest build that has waited this long goes first (nothing waits forever). */
    private static final long MAX_WAIT_NS = 500_000_000L;

    private final Executor executor;
    private final int limit;
    private final ArrayList<Entry> pending = new ArrayList<>();
    private final ArrayList<Entry> ready = new ArrayList<>();
    private int inFlight;
    private boolean closed;

    private long submitted;
    private long direct;
    private long outOfOrder;
    private long canceledFirst;
    private long outOfView;
    private long aged;
    private long maxWaitNanos;
    private int pendingMax;

    public CompileQueue(Executor executor) {
        this.executor = executor;
        this.limit = executor instanceof ForkJoinPool ? Math.max(1, ((ForkJoinPool) executor).getParallelism()) : 4;
    }

    /** The supplyAsync executor (on the thread that asked for the build: the render thread). */
    void submit(Runnable command, IRenderChunkTask task, AbstractRenderChunk chunk) {
        Entry e = new Entry(command, task, chunk);
        boolean now;
        synchronized (this) {
            submitted++;
            // With a free slot, the queue only has builds held by "inViewOnly": the new one does not wait for them.
            now = closed || inFlight < limit && (!CompilePriority.onlyInView() || CompilePriority.inView(chunk));
            if (now) {
                inFlight++;
                direct++;
            } else {
                pending.add(e);
                pendingMax = Math.max(pendingMax, pending.size());
            }
        }
        if (now) {
            // On the requesting thread, like Nothirium's supplyAsync: if the pool refuses, the error goes back to it.
            executor.execute(() -> run(e));
        }
    }

    private void run(Entry e) {
        try {
            e.command.run();
        } finally {
            Entry next;
            synchronized (this) {
                inFlight--;
                next = closed ? null : pick();
                if (next != null) {
                    inFlight++;
                }
            }
            if (next != null) {
                dispatch(next);
            }
        }
    }

    private void dispatch(Entry e) {
        try {
            executor.execute(() -> run(e));
        } catch (Throwable t) {
            // The pool closed (dispose already called flush) or refused: the build runs here, so the result is not lost.
            synchronized (this) {
                inFlight--;
            }
            ShadersCompat.LOG.warn("[gadolinium] Build order: the pool refused a build ({}); running on the current thread", t.toString());
            e.command.run();
        }
    }

    /**
     * The next build (with the lock held): a cancelled one (leaves at once); outside "inViewOnly", the oldest if it has already
     * waited MAX_WAIT_NS; else the oldest in the cone; else (outside "inViewOnly") the oldest.
     */
    private Entry pick() {
        boolean only = CompilePriority.onlyInView();
        if (!only && !pending.isEmpty() && System.nanoTime() - pending.get(0).queuedAt > MAX_WAIT_NS) {
            aged++;
            return take(0);
        }
        int first = -1;
        for (int i = 0, n = pending.size(); i < n; i++) {
            Entry e = pending.get(i);
            if (e.task.canceled()) {
                canceledFirst++;
                return take(i);
            }
            if (CompilePriority.inView(e.chunk)) {
                if (i > 0) {
                    outOfOrder++;
                }
                return take(i);
            }
            if (first < 0) {
                first = i;
            }
        }
        if (first >= 0 && !only) {
            outOfView++;
            return take(first);
        }
        return null;
    }

    /** Removes from the queue (with the lock held) and keeps the longest wait. */
    private Entry take(int i) {
        Entry e = pending.remove(i);
        long waited = System.nanoTime() - e.queuedAt;
        if (waited > maxWaitNanos) {
            maxWaitNanos = waited;
        }
        return e;
    }

    /** Every frame (render thread): what can leave with the new cone (free slot and, in "inViewOnly", section on screen). */
    void pump() {
        synchronized (this) {
            while (inFlight < limit) {
                Entry e = pick();
                if (e == null) {
                    break;
                }
                inFlight++;
                ready.add(e);
            }
        }
        for (int i = 0, n = ready.size(); i < n; i++) {
            dispatch(ready.get(i));
        }
        ready.clear();
    }

    /** The dispatcher's dispose (before the pool shuts down): everything waiting goes to the pool, as in Nothirium. */
    public void flush() {
        ArrayList<Entry> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(pending);
            pending.clear();
            inFlight += all.size();
        }
        for (Entry e : all) {
            dispatch(e);
        }
    }

    synchronized Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threads", limit);
        m.put("builds", submitted);
        m.put("direct", direct);
        m.put("waiting", pending.size());
        m.put("waitingMax", pendingMax);
        m.put("promotedByView", outOfOrder);
        m.put("cancelledFirst", canceledFirst);
        m.put("outOfView", outOfView);
        m.put("byAge", aged);
        m.put("longestWaitMs", maxWaitNanos / 1_000_000L);
        return m;
    }

}
