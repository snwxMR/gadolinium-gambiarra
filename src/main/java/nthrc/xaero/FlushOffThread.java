// SPDX-License-Identifier: MIT
package nthrc.xaero;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.storage.ThreadedFileIOBase;
import nthrc.Hook;

/**
 * flushChunksOffServerThread (see FlushRunMixin and FlushWaitMixin). The two parts only act together: taking the flush out without the wait would let Xaero read region files without the freshly saved chunks.
 *
 * The wait is the game's own when closing the world (ThreadedFileIOBase.waitForFinish): until the disk queue is empty, including the chunks the Runnable just put in it. It runs on Xaero's thread; the server thread carries on.
 */
public final class FlushOffThread {

    private static final AtomicLong skipped = new AtomicLong(), waits = new AtomicLong(), waitMsTotal = new AtomicLong(),
            waitMsMax = new AtomicLong();

    private FlushOffThread() {
    }

    public static boolean active() {
        return Hook.XAERO_FLUSH_RUN.ok && Hook.XAERO_FLUSH_WAIT.ok;
    }

    public static void skipped() {
        skipped.incrementAndGet();
    }

    public static void waitForDisk() throws InterruptedException {
        long t0 = System.nanoTime();
        ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        waits.incrementAndGet();
        waitMsTotal.addAndGet(ms);
        waitMsMax.accumulateAndGet(ms, Math::max);
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flushesOnDiskThread", skipped.get());
        m.put("xaeroWaits", waits.get());
        m.put("waitTotalMs", waitMsTotal.get());
        m.put("waitMaxMs", waitMsMax.get());
        return m;
    }

}
