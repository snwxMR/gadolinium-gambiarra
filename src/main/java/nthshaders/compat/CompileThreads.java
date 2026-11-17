// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import nthshaders.core.NthConfig;
import nthshaders.compat.ShadersCompat;

/**
 * Number and priority of Nothirium's chunk build threads (NothiriumCompileThreadsMixin).
 *
 * Nothirium creates a ForkJoinPool with availableProcessors() - 2 threads, with no config option (RenderChunkDispatcher),
 * and the threads inherit the priority of their creator: the client thread, which has Java's maximum (10 = "Highest" on
 * Windows), the same as the integrated server. With many threads they fight the client and the server for cores and cache.
 * Measured on a 16-thread processor: with 4 instead of 14 while exploring, average FPS rose ~22% and the server tick fell
 * from 37 to 31 ms, and the time until everything around is ready (joining a world, teleporting) did not change.
 *
 * Default (chunkCompileThreads = 0): processors / 4, at least 2, at most what Nothirium would use.
 * Priority (chunkCompileThreadPriority): 5 = normal, below the client and the server; 10 = as Nothirium leaves it.
 */
public final class CompileThreads {

    /** Nothirium's math (RenderChunkDispatcher): processors - 2, at least 1. */
    public static final int NOTHIRIUM_DEFAULT = Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);

    /** Threads of the pool created through the hook (-1 = the hook has not run yet, or did not take). */
    private static volatile int appliedThreads = -1;
    private static volatile int appliedPriority = -1;
    private static volatile int poolsCreated;

    private CompileThreads() {
    }

    /** The automatic value: processors / 4, at least 2, at most Nothirium's default. */
    static int auto(int processors, int nothiriumDefault) {
        return Math.min(nothiriumDefault, Math.max(2, processors / 4));
    }

    /** How many threads the config asks for, given what Nothirium would use. */
    static int wanted(int nothiriumParallelism) {
        int cfg = NthConfig.chunkCompileThreads;
        if (cfg < 0) {
            return nothiriumParallelism;
        }
        if (cfg == 0) {
            return auto(Runtime.getRuntime().availableProcessors(), nothiriumParallelism);
        }
        return cfg;
    }

    /** How many threads the pool has (or will have): what the hook applied or, before it, what the config asks for. */
    public static int effective() {
        int applied = appliedThreads;
        if (applied > 0) {
            return applied;
        }
        return ShadersCompat.compileThreadsAllowed() ? wanted(NOTHIRIUM_DEFAULT) : NOTHIRIUM_DEFAULT;
    }

    /** In place of the new ForkJoinPool(...) in RenderChunkDispatcher's constructor. */
    public static ForkJoinPool create(int parallelism, ForkJoinWorkerThreadFactory factory, UncaughtExceptionHandler handler, boolean asyncMode,
            Operation<ForkJoinPool> original) {
        int threads = wanted(parallelism);
        int priority = NthConfig.chunkCompileThreadPriority;
        ForkJoinWorkerThreadFactory used = factory;
        if (priority >= Thread.MIN_PRIORITY && priority < Thread.MAX_PRIORITY) {
            used = pool -> {
                ForkJoinWorkerThread t = factory.newThread(pool);
                if (t != null) {
                    t.setPriority(priority);
                }
                return t;
            };
        }
        appliedThreads = threads;
        appliedPriority = used == factory ? -1 : priority;
        poolsCreated++;
        ShadersCompat.markRan("chunkCompileThreads", threads + " threads (Nothirium would use " + parallelism + "), priority "
                + (used == factory ? "inherited (as Nothirium leaves it)" : String.valueOf(priority)));
        return original.call(threads, used, handler, asyncMode);
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", NthConfig.chunkCompileThreads);
        m.put("configPriority", NthConfig.chunkCompileThreadPriority);
        m.put("nothiriumDefault", NOTHIRIUM_DEFAULT);
        m.put("applied", appliedThreads > 0 ? appliedThreads + " threads" : "not yet (Nothirium has not created the pool, or the hook did not take)");
        m.put("appliedPriority", appliedPriority > 0 ? appliedPriority : "inherited");
        m.put("poolsCreated", poolsCreated);
        return m;
    }

}
