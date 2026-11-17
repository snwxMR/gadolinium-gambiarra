// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import nthshaders.core.NthConfig;
import nthshaders.compat.ShadersCompat;

/**
 * Limit on Nothirium's compile buffer sets (RegionRenderCacheBuilder, ~10 MB of direct memory each).
 *
 * In Nothirium 0.4.9-beta, each compiled chunk takes a set from the RenderChunkTaskCompile.BUFFER_QUEUE queue, or creates a
 * new one if the queue is empty. The set only returns to the queue after the main thread uploads the chunk to the GPU, and
 * the queue never releases sets: during a long frame the compile threads keep creating sets for finished chunks waiting
 * for upload, and memory stays at its peak for the rest of the session (up to crashing the game out of direct memory).
 *
 * With the limit, when the queue is empty and {@link #cap()} sets already exist, the compile thread waits for a set to
 * return instead of creating another. In other words: it limits how many finished chunks can wait for upload.
 *
 * It never waits:
 * <ul>
 * <li>on the main thread (it is the one returning the sets);</li>
 * <li>while Nothirium disposes the renderer (dispose: the main thread is stuck waiting for the compile threads to
 * finish);</li>
 * <li>longer than {@link #MAX_WAIT_MS}: then it creates the set anyway (counted in "timeouts"), so an unexpected stall
 * never becomes a freeze.</li>
 * </ul>
 */
public final class CompileBufferLimit {

    static final long MAX_WAIT_MS = 5000L;
    private static final long POLL_MS = 20L;

    /** Sets created by Nothirium (the queue never discards any: this is its total size). */
    private static final AtomicInteger created = new AtomicInteger();
    private static final AtomicInteger peakInFlight = new AtomicInteger();
    private static final AtomicLong waits = new AtomicLong();
    private static final AtomicLong waitNanos = new AtomicLong();
    private static final AtomicLong maxWaitNanos = new AtomicLong();
    private static final AtomicLong timeouts = new AtomicLong();
    private static final AtomicLong overCap = new AtomicLong();
    private static volatile boolean disposing;

    private CompileBufferLimit() {
    }

    public static int cap() {
        return CompileThreads.effective() * Math.max(1, NthConfig.chunkCompileBuffersPerThread);
    }

    /**
     * In place of compileSection()'s BUFFER_QUEUE.poll(): returns a set from the queue, or null for Nothirium to create one.
     */
    public static Object take(BlockingQueue<?> queue, Operation<Object> original) {
        Object builder = original.call(queue);
        if (builder == null) {
            builder = whenEmpty(queue);
        }
        int inFlight = created.get() - queue.size();
        peakInFlight.accumulateAndGet(inFlight, Math::max);
        return builder;
    }

    private static Object whenEmpty(BlockingQueue<?> queue) {
        boolean limit = ShadersCompat.compileBufferLimitAllowed();
        if (limit) {
            int cap = cap();
            for (int n = created.get(); n < cap; n = created.get()) {
                if (created.compareAndSet(n, n + 1)) {
                    return null;
                }
            }
            if (!disposing && !MainThread.isCurrent()) {
                Object builder = await(queue);
                if (builder != null) {
                    return builder;
                }
            }
            overCap.incrementAndGet();
        }
        created.incrementAndGet();
        return null;
    }

    private static Object await(BlockingQueue<?> queue) {
        long start = System.nanoTime();
        waits.incrementAndGet();
        ShadersCompat.markRan("Nothirium compile buffer limit",
                "queue empty with " + created.get() + " sets created: the compile waits for one to return");
        try {
            while (!disposing) {
                Object builder = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (builder != null) {
                    return builder;
                }
                if (System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MS)) {
                    if (timeouts.incrementAndGet() == 1) {
                        ShadersCompat.LOG.warn("[gadolinium] A compile thread waited {} ms for a buffer set; creating one more (above the limit of {})", MAX_WAIT_MS, cap());
                    }
                    return null;
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            long waited = System.nanoTime() - start;
            waitNanos.addAndGet(waited);
            maxWaitNanos.accumulateAndGet(waited, Math::max);
        }
    }

    /** Start and end of RenderChunkDispatcher's dispose. */
    public static void setDisposing(boolean value) {
        disposing = value;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limitOn", ShadersCompat.compileBufferLimitAllowed());
        m.put("cap", cap());
        m.put("compileThreads", CompileThreads.effective());
        m.put("created", created.get());
        m.put("peakInFlight", peakInFlight.get());
        m.put("waits", waits.get());
        long w = waits.get();
        m.put("avgWaitMs", w == 0 ? 0.0 : Math.round(waitNanos.get() / 1e4 / w) / 100.0);
        m.put("maxWaitMs", Math.round(maxWaitNanos.get() / 1e4) / 100.0);
        m.put("timeouts", timeouts.get());
        m.put("overCap", overCap.get());
        return m;
    }

}
