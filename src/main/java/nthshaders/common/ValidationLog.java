// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.launchwrapper.Launch;

/**
 * Validation mode (the mod config's validationMode option, off by default): where the original path can run next to the
 * optimized one, each optimization runs both and compares. The game keeps the original's result.
 *
 * This class holds the switch, the per-optimization counts and the logs/&lt;mod&gt;-validation.log file: each difference
 * becomes a line (up to MAX_LINES per optimization; the rest are only counted), and the summary is written when the world
 * closes and when the game exits. With no differences, the file only has the summaries. The switch is read straight from
 * the config file, so it already applies in the mixin plugins, before any config class runs.
 */
public final class ValidationLog {

    private static final Logger LOG = LogManager.getLogger(BuildInfo.MODID);
    private static final int MAX_LINES = 100;
    private static final String KEY = "B:validationMode=true";

    private static final class Item {
        final LongAdder checked = new LongAdder();
        final LongAdder diffs = new LongAdder();
    }

    private static final Map<String, Item> ITEMS = new ConcurrentSkipListMap<>();
    private static volatile Boolean on;
    private static PrintWriter out;
    private static boolean hookAdded;

    private ValidationLog() {
    }

    /** Whether validation mode is on (read once). */
    public static boolean on() {
        Boolean v = on;
        if (v == null) {
            v = read();
            on = v;
            if (v) {
                LOG.warn("[{}] Validation mode ON (validationMode=true): each optimization also runs the original and compares. "
                        + "Slower on purpose; results in logs/{}-validation.log", BuildInfo.MODID, BuildInfo.MODID);
                addExitSummary();
            }
        }
        return v;
    }

    /** Test hooks: turns it on without looking at the config. */
    public static void force() {
        on = Boolean.TRUE;
        addExitSummary();
    }

    private static boolean read() {
        File config = new File(home(), "config");
        File file = new File(config, "gambiarra".equals(BuildInfo.MODID) ? "gambiarra-worldgen.cfg" : "gadolinium.cfg");
        if (!file.isFile() && !"gambiarra".equals(BuildInfo.MODID)) {
            // First start after the rename: the config is copied from nthshaders.cfg only later (NthConfig).
            file = new File(config, "nthshaders.cfg");
        }
        try {
            if (file.isFile()) {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (line.trim().equalsIgnoreCase(KEY)) {
                        return true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[{}] Could not read {}; validationMode stays off", BuildInfo.MODID, file.getName());
        }
        return false;
    }

    private static File home() {
        return Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
    }

    private static Item item(String name) {
        Item i = ITEMS.get(name);
        if (i == null) {
            i = ITEMS.computeIfAbsent(name, k -> new Item());
        }
        return i;
    }

    /** One comparison made. */
    public static void checked(String name) {
        item(name).checked.increment();
    }

    /** One difference between the original and the optimized path. */
    public static void diff(String name, String detail) {
        Item i = item(name);
        i.diffs.increment();
        if (i.diffs.sum() <= MAX_LINES) {
            write(LocalTime.now() + " DIFFERENCE " + name + ": " + detail);
        }
    }

    /** A comparison in one call: same = the original and the optimized path gave the same result. */
    public static void check(String name, boolean same, String detail) {
        checked(name);
        if (!same) {
            diff(name, detail);
        }
    }

    /** The per-optimization counts, in the file and in the game log. */
    public static synchronized void summary(String when) {
        if (!Boolean.TRUE.equals(on)) {
            return;
        }
        if (ITEMS.isEmpty()) {
            write(LocalTime.now() + " SUMMARY (" + when + "): no optimization had a comparison in this session");
            LOG.warn("[{}] Validation ({}): no optimization had a comparison in this session (logs/{}-validation.log)", BuildInfo.MODID, when,
                    BuildInfo.MODID);
            return;
        }
        long total = 0;
        write(LocalTime.now() + " SUMMARY (" + when + "): checks and differences per optimization");
        for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
            long d = e.getValue().diffs.sum();
            total += d;
            write("    " + e.getKey() + ": " + e.getValue().checked.sum() + " checks, " + d + " differences");
        }
        LOG.warn("[{}] Validation ({}): {} optimizations checked, {} differences in total (details in logs/{}-validation.log)",
                BuildInfo.MODID, when, ITEMS.size(), total, BuildInfo.MODID);
    }

    /**
     * The summary when the game exits. Registered when the mode turns on: up to rc4 it was registered with the first line of the file, and a
     * session with no difference and no world-close summary (Gadolinium) never wrote the file.
     */
    private static synchronized void addExitSummary() {
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> summary("game exit"), BuildInfo.MODID + "-validation"));
        }
    }

    private static synchronized void write(String line) {
        try {
            if (out == null) {
                File dir = new File(home(), "logs");
                dir.mkdirs();
                out = new PrintWriter(Files.newBufferedWriter(new File(dir, BuildInfo.MODID + "-validation.log").toPath(), StandardCharsets.UTF_8));
                out.println("# Validation mode of " + BuildInfo.NAME + " " + BuildInfo.VERSION + " (commit " + BuildInfo.COMMIT + ")");
            }
            out.println(line);
            out.flush();
        } catch (IOException | RuntimeException e) {
            LOG.warn("[{}] Could not write the validation file: {}", BuildInfo.MODID, e.toString());
        }
    }

}
