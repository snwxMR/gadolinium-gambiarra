// SPDX-License-Identifier: MIT
package nthrc.diag;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import nthrc.WorldgenStatus;

/**
 * Tick recorder writer, on its own lowest-priority thread: the server thread only puts lines in a queue (TickRecorder). It formats, writes
 * buffered (flush every second) and switches file when past the maximum size (rotation: nthrc-ticks.csv becomes .1.csv, .1 becomes .2...).
 * Each world opened starts new files.
 *
 * At the end of the session it writes a summary to the log (average, P95, P99, % above 50 ms, maximum, recorder cost). The percentiles come
 * from a 0.1 ms histogram, up to 2 s.
 */
final class TickWriter implements Runnable {

    static final String TICKS = "nthrc-ticks";
    static final String READY = "nthrc-readiness";

    private static final String TICKS_COLUMNS = "epochMs,tickMs,sincePreviousMs,chunks,entities,tileEntities,chunksLoadedInTick,chunksUnloadedInTick,chunk"
            + "sSentInTick,players,chunksMissing,recorderCostUs";
    private static final String READY_COLUMNS = "epochMs,player,reason,dimension,result,seconds,ticks,chunksInRange,"
            + "chunksMissing";

    /** A tick line (numbers only; this thread does the formatting). */
    static final class TickRow {
        long epochMs, tickNanos, sinceLastNanos, costNanos;
        int chunks, entities, tileEntities, loaded, unloaded, sent, players, missing;
    }

    /** A player's readiness. */
    static final class ReadyRow {
        long epochMs, nanos;
        String player, reason, result;
        int dim, ticks, target, missing;
    }

    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(8192);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closing;
    private final Thread thread;

    private final File dir;
    private final long maxBytes;
    private final int oldFiles;
    private final String header;
    private Writer ticksOut, readyOut;
    private long ticksBytes;
    private boolean broken;

    // Summary (only this thread touches it)
    private static final int BINS = 20_000;
    private final int[] histogram = new int[BINS + 1];
    private long count, over50, firstMs, lastMs, readyCount;
    private double sumMs, maxMs, sumCostUs, maxCostUs;

    private TickWriter(File dir, long maxBytes, int oldFiles, String header) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.oldFiles = oldFiles;
        this.header = header;
        this.thread = new Thread(this, "nthrc tick recorder");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
    }

    static TickWriter open(File dir, long maxBytes, int oldFiles, String header) throws IOException {
        TickWriter w = new TickWriter(dir, maxBytes, oldFiles, header);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        rotate(dir, TICKS, oldFiles);
        rotate(dir, READY, oldFiles);
        w.ticksOut = w.newFile(TICKS, TICKS_COLUMNS);
        w.readyOut = w.newFile(READY, READY_COLUMNS);
        w.thread.start();
        return w;
    }

    /** Called by the server thread: never waits. */
    void offer(Object row) {
        if (closing || !queue.offer(row)) {
            dropped.incrementAndGet();
        }
    }

    /** When stopping the server: finishes writing (up to 3 s) and writes the summary. */
    void close() {
        closing = true;
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        List<Object> batch = new ArrayList<>(256);
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Object first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 1023);
                    for (Object o : batch) {
                        handle(o);
                    }
                    batch.clear();
                }
                if (System.nanoTime() - lastFlush > 1_000_000_000L) {
                    flush();
                    lastFlush = System.nanoTime();
                }
                if (closing && queue.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: writing stopped.", t);
        } finally {
            closeFiles();
            logSummary();
        }
    }

    private void handle(Object o) {
        if (o instanceof TickRow) {
            TickRow r = (TickRow) o;
            account(r);
            write(ticksLine(r), true);
        } else if (o instanceof ReadyRow) {
            ReadyRow r = (ReadyRow) o;
            readyCount++;
            double seconds = r.nanos / 1e9;
            WorldgenStatus.LOG.info("[gambiarra] Readiness of {} ({}, dimension {}): {} in {} s ({} ticks; {} chunks in range{}).",
                    r.player, r.reason, r.dim, r.result, fmt(seconds, 2), r.ticks, r.target,
                    r.missing > 0 ? ", " + r.missing + " still missing" : "");
            write(r.epochMs + "," + csv(r.player) + "," + r.reason + "," + r.dim + "," + r.result + "," + fmt(seconds, 3) + ","
                    + r.ticks + "," + r.target + "," + r.missing + "\n", false);
        }
    }

    private static String ticksLine(TickRow r) {
        return r.epochMs + "," + fmt(r.tickNanos / 1e6, 3) + "," + fmt(r.sinceLastNanos / 1e6, 3) + "," + r.chunks + ","
                + r.entities + "," + r.tileEntities + "," + r.loaded + "," + r.unloaded + "," + r.sent + "," + r.players + ","
                + r.missing + "," + fmt(r.costNanos / 1e3, 1) + "\n";
    }

    private void account(TickRow r) {
        double ms = r.tickNanos / 1e6;
        double costUs = r.costNanos / 1e3;
        if (count == 0) {
            firstMs = r.epochMs;
        }
        lastMs = r.epochMs;
        count++;
        sumMs += ms;
        maxMs = Math.max(maxMs, ms);
        if (ms > 50.0) {
            over50++;
        }
        histogram[(int) Math.min(BINS, Math.max(0, ms * 10.0))]++;
        sumCostUs += costUs;
        maxCostUs = Math.max(maxCostUs, costUs);
    }

    private void write(String line, boolean ticks) {
        if (broken) {
            return;
        }
        try {
            if (ticks) {
                if (ticksBytes > maxBytes) {
                    ticksOut.close();
                    rotate(dir, TICKS, oldFiles);
                    ticksOut = newFile(TICKS, TICKS_COLUMNS);
                }
                ticksOut.write(line);
                ticksBytes += line.length();
            } else {
                readyOut.write(line);
            }
        } catch (IOException e) {
            broken = true;
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: error writing to {}; stopped recording (the summary at the end still comes).",
                    dir, e);
        }
    }

    private Writer newFile(String base, String columns) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, base + ".csv")),
                StandardCharsets.UTF_8), 1 << 16);
        String start = "# " + header + "\n" + columns + "\n";
        w.write(start);
        if (base.equals(TICKS)) {
            ticksBytes = start.length();
        }
        return w;
    }

    /** base.csv becomes base.1.csv, base.1 becomes base.2 ... ; whatever goes past oldFiles is deleted. */
    private static void rotate(File dir, String base, int oldFiles) {
        File current = new File(dir, base + ".csv");
        if (!current.exists()) {
            return;
        }
        if (oldFiles <= 0) {
            current.delete();
            return;
        }
        new File(dir, base + "." + oldFiles + ".csv").delete();
        for (int i = oldFiles - 1; i >= 1; i--) {
            File from = new File(dir, base + "." + i + ".csv");
            if (from.exists()) {
                from.renameTo(new File(dir, base + "." + (i + 1) + ".csv"));
            }
        }
        current.renameTo(new File(dir, base + ".1.csv"));
    }

    private void flush() {
        if (broken) {
            return;
        }
        try {
            ticksOut.flush();
            readyOut.flush();
        } catch (IOException e) {
            broken = true;
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: error writing to {}; stopped recording.", dir, e);
        }
    }

    private void closeFiles() {
        for (Writer w : new Writer[] {ticksOut, readyOut}) {
            try {
                if (w != null) {
                    w.close();
                }
            } catch (IOException ignored) {
                // nothing to do: the summary goes to the log anyway
            }
        }
    }

    private void logSummary() {
        if (count == 0) {
            WorldgenStatus.LOG.info("[gambiarra] Tick recorder: no tick recorded in this session.");
            return;
        }
        WorldgenStatus.LOG.info("[gambiarra] Tick recorder: {} ticks in {} min. Tick: average {} ms, P95 {} ms, P99 {} ms, above 50 ms {}%, "
                        + "maximum {} ms. Recorder cost on the server thread: average {} us per tick, maximum {} us. Readiness "
                        + "records: {}. Lines dropped: {}.",
                count, fmt((lastMs - firstMs) / 60000.0, 1), fmt(sumMs / count, 2), fmt(percentile(0.95), 1),
                fmt(percentile(0.99), 1), fmt(100.0 * over50 / count, 2), fmt(maxMs, 1), fmt(sumCostUs / count, 1),
                fmt(maxCostUs, 1), readyCount, dropped.get());
    }

    /** Upper limit of the 0.1 ms interval that holds the percentile (above 2 s: the maximum). */
    private double percentile(double p) {
        long rank = (long) Math.ceil(p * count);
        long seen = 0;
        for (int i = 0; i < BINS; i++) {
            seen += histogram[i];
            if (seen >= rank) {
                return (i + 1) / 10.0;
            }
        }
        return maxMs;
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String csv(String s) {
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

}
