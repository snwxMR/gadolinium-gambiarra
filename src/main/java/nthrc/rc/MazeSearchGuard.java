// SPDX-License-Identifier: MIT
package nthrc.rc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import ivorius.reccomplex.RCConfig;
import gambiarra.common.DevHooks;
import nthrc.Hook;
import nthrc.WorldgenStatus;

/**
 * Proposal A: the maze search stops when Recurrent Complex gives up on it.
 *
 * Recurrent Complex (WorldScriptMazeGenerator.getPlacedRooms) sends IvToolkit's search (MazeComponentConnector.connect) to its own thread and waits up to mazeTimeout. On timeout it calls future.cancel(true),
 * which only INTERRUPTS the thread; as the search never checks the interruption, it carries on until it finishes by itself (~60 s seen), wasting a core for nothing: the result has already been discarded.
 *
 * Here:
 *  - {@link #wrap}: wraps the task sent by RC and marks its thread (only that search, never a search called some other way);
 *  - {@link #check}: called before each candidate piece of the search; if the marked thread was interrupted (RC already gave up), ends the search. Without the interruption, it does nothing.
 *
 * It does not change any maze: it only acts after the timeout, when RC has already thrown the result away.
 */
public final class MazeSearchGuard {

    private static final ThreadLocal<Boolean> GUARDED = new ThreadLocal<>();

    /** Ends the search from the inside; the wrapper catches it (it never leaves here). */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super("nthrc: maze search ended after the timeout", null, false, false);
        }
    }

    private static final Stop STOP = new Stop();

    /** Test (development build only): measures, but does not end the search (control). */
    private static final boolean STOP_ENABLED = stopEnabled();

    private static final AtomicLong searches = new AtomicLong();
    private static final AtomicLong abandoned = new AtomicLong();
    private static final AtomicLong stopped = new AtomicLong();
    private static final AtomicLong overrunSumMs = new AtomicLong();
    private static final AtomicLong overrunMaxMs = new AtomicLong();

    private MazeSearchGuard() {
    }

    private static boolean stopEnabled() {
        if (DevHooks.ON) {
            String p = System.getProperty("nthrc.testMazeStop");
            if (p != null && !Boolean.parseBoolean(p.trim())) {
                WorldgenStatus.LOG.warn("[gambiarra] [TEST] The maze search is NOT ended after the timeout (-Dnthrc.testMazeStop=false)");
                return false;
            }
        }
        return true;
    }

    /** Wraps the task RC sends to the executor (only if the injection was checked). */
    public static <T> Callable<T> wrap(Callable<T> task, String mazeId) {
        if (!Hook.MAZE_TASK.ok) {
            return task;
        }
        return () -> run(task, mazeId);
    }

    private static <T> T run(Callable<T> task, String mazeId) throws Exception {
        long start = System.nanoTime();
        searches.incrementAndGet();
        GUARDED.set(Boolean.TRUE);
        boolean stoppedHere = false;
        try {
            return task.call();
        } catch (Stop s) {
            // RC has already cancelled the future: this value is never read.
            stoppedHere = true;
            return null;
        } finally {
            GUARDED.remove();
            if (stoppedHere || Thread.currentThread().isInterrupted()) {
                long overrun = Math.max(0, (System.nanoTime() - start) / 1_000_000L - RCConfig.mazeTimeout);
                abandoned.incrementAndGet();
                if (stoppedHere) {
                    stopped.incrementAndGet();
                }
                overrunSumMs.addAndGet(overrun);
                overrunMaxMs.accumulateAndGet(overrun, Math::max);
                WorldgenStatus.LOG.info("[gambiarra] Maze {}: Recurrent Complex gave up after {} ms (mazeTimeout); the search {} ~{} ms later.",
                        mazeId, RCConfig.mazeTimeout, stoppedHere ? "was ended" : "only finished", overrun);
            }
        }
    }

    /** Called before each candidate piece tested by the search (MazeComponentConnector.connectMulti). */
    public static void check() {
        // isInterrupted first: it is the rare case and the cheapest to check.
        if (Thread.currentThread().isInterrupted() && STOP_ENABLED && GUARDED.get() != null && Hook.MAZE_CHECK.ok) {
            throw STOP;
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        long a = abandoned.get();
        m.put("searches", searches.get());
        m.put("givenUpByTimeout", a);
        m.put("endedByNthrc", stopped.get());
        m.put("averageDelayMs", a > 0 ? overrunSumMs.get() / a : 0);
        m.put("maxDelayMs", overrunMaxMs.get());
        return m;
    }

}
