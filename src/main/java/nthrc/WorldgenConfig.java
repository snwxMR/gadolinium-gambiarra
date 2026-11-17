// SPDX-License-Identifier: MIT
package nthrc;

import java.io.File;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.Configuration;
import gambiarra.common.ConfigMigration;
import gambiarra.common.DevHooks;

/**
 * Gambiarra's worldgen config (config/gambiarra-worldgen.cfg; up to Nothirium Shaders 0.10.0, config/nthrc.cfg, copied on first start).
 *
 * "general" section: turns the whole module on/off and the forcing of unknown versions. One section per target mod (Group), with one option per optimization (Opt). Independent of the nthshaders and nthcompat configs. Read early, by the mixin
 * plugin, before any class of the target mods loads.
 */
public final class WorldgenConfig {

    public static final String FILE_NAME = "gambiarra-worldgen.cfg";
    /** The name up to Nothirium Shaders 0.10.0 (nthrc module), copied to FILE_NAME on first start. */
    public static final String OLD_FILE_NAME = "nthrc.cfg";

    /** Turns the whole module on/off. Off = no mixin of the module is applied. */
    public static boolean enabled = true;
    /** Activates the optimizations even with mods of another version (at your own risk). */
    public static boolean forceUnknownVersion = false;
    /** validationMode: each optimization runs the original along with it, checks, and the game carries on with the original's result (gambiarra.common.ValidationLog). */
    public static boolean validate = false;

    /** Tick recorder section: independent of "enabled" (it also serves to measure with the optimizations off). */
    public static final String CATEGORY_DIAGNOSTICS = "diagnostics";
    /** Tick recorder (nthrc.diag.TickRecorder): off by default. */
    public static boolean recordTicks = false;
    /** Maximum size of each ticks file, in MB, before moving on to the next. */
    public static int recordMaxFileMB = 16;
    /** How many old files to keep (nthrc-ticks.1.csv, .2.csv...). */
    public static int recordOldFiles = 3;

    private static boolean loaded;

    private WorldgenConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = new File(new File(home(), "config"), FILE_NAME);
        migrateFromNthrc(file);
        Configuration cfg = new Configuration(file);
        cfg.load();
        enabled = cfg.getBoolean("enabled", Configuration.CATEGORY_GENERAL, true,
                "Turns on the server worldgen optimizations. Off: the target mods run exactly as without this mod.");
        forceUnknownVersion = cfg.getBoolean("forceUnknownVersion", Configuration.CATEGORY_GENERAL, false,
                "Activates the optimizations even if the version of a target mod (or the classes they touch) differs from "
                        + "the validated one. Use at your own risk.");
        validate = cfg.getBoolean("validationMode", Configuration.CATEGORY_GENERAL, false,
                "Checking session: each optimization runs the original path alongside the optimized one, compares, and the "
                        + "game carries on with the original's result. It is slower on purpose. Differences go to "
                        + "logs/gambiarra-validation.log, with the per-optimization counts when closing the world. For performance, "
                        + "leave it off.");
        ConfigMigration migration = new ConfigMigration(cfg, WorldgenStatus.LOG, "[gambiarra]", FILE_NAME);
        migrate(migration);
        for (Group g : Group.values()) {
            cfg.getCategory(g.id).setComment("Optimizations and fixes for " + g.label + ". They only act with its validated version.");
        }
        boolean experimental = cfg.getBoolean("experimental", Configuration.CATEGORY_GENERAL, false,
                "Turns on all options marked EXPERIMENTAL at once, even those set to false. To turn off just one of them, "
                        + "set this back to false and turn the others on one by one.");
        for (Opt o : Opt.values()) {
            boolean value = cfg.getBoolean(o.key, o.group.id, !o.experimental(), o.experimental() ? "EXPERIMENTAL (off by default). " + o.comment : o.comment);
            o.enabledInConfig = value || (experimental && o.experimental());
        }
        readDiagnostics(cfg);
        migration.report();
        migration.save();
        if (DevHooks.ON) {
            // Development build only: -Dnthrc.<option>=... overrides the file (used by the smoketest).
            enabled = override("enabled", enabled);
            forceUnknownVersion = override("forceUnknownVersion", forceUnknownVersion);
            for (Opt o : Opt.values()) {
                o.enabledInConfig = override(o.key, o.enabledInConfig);
            }
            validate = override("validate", validate);
        }
        if (validate) {
            gambiarra.common.ValidationLog.force();
        }
    }

    /**
     * The first version (Phase 1) had stopMazeSearchAfterTimeout in "general": takes the value to the group's section.
     * The rememberLastChunk option of the "pulsar" section (up to addon 0.4.0) left: Pulsar 0.5.0 already solves what it attacked.
     * The section came back in Gambiarra 0.4.0 with another option (retryNeighboursFirst); only the old key is deleted.
     */
    private static void migrate(ConfigMigration migration) {
        migration.moveKey(Configuration.CATEGORY_GENERAL, Opt.MAZE_STOP.group.id, Opt.MAZE_STOP.key);
        migration.removeKey("pulsar", "rememberLastChunk", "Pulsar 0.5.0 already solves what it attacked");
    }

    /**
     * Rereads only the tick recorder section, when opening the world: turning the recorder on or off does not require restarting the game
     * (the optimizations do: mixins are decided once, at load).
     */
    public static synchronized void reloadDiagnostics() {
        load();
        Configuration cfg = new Configuration(new File(new File(home(), "config"), FILE_NAME));
        cfg.load();
        readDiagnostics(cfg);
        if (cfg.hasChanged()) {
            cfg.save();
        }
    }

    private static void readDiagnostics(Configuration cfg) {
        cfg.getCategory(CATEGORY_DIAGNOSTICS).setComment("Measurement. It changes nothing in the game; applies even with enabled=false.");
        recordTicks = cfg.getBoolean("recordTicks", CATEGORY_DIAGNOSTICS, false,
                "Writes one line per server tick to logs/nthrc-ticks.csv (tick duration, chunks, entities, chunks sent) "
                        + "and, for each player, how long it took until all chunks around had been sent after joining, teleporting "
                        + "or changing dimension (logs/nthrc-readiness.csv). It only reads the game; writing to disk happens off the "
                        + "server thread. Applies when opening the world.");
        recordMaxFileMB = cfg.getInt("maxFileSizeMB", CATEGORY_DIAGNOSTICS, 16, 1, 1024,
                "Maximum size of each ticks file (MB). When exceeded, the file becomes nthrc-ticks.1.csv and another starts.");
        recordOldFiles = cfg.getInt("oldFilesToKeep", CATEGORY_DIAGNOSTICS, 3, 0, 50,
                "How many old files of each kind to keep. Each world opened starts new files; the oldest beyond this "
                        + "number are deleted.");
    }

    /** First start of Gambiarra coming from Nothirium Shaders 0.10.0: copies nthrc.cfg (the old one stays untouched). */
    private static void migrateFromNthrc(File file) {
        File old = new File(file.getParentFile(), OLD_FILE_NAME);
        if (file.exists() || !old.isFile()) {
            return;
        }
        try {
            java.nio.file.Files.copy(old.toPath(), file.toPath());
            WorldgenStatus.LOG.info("[gambiarra] Config migrated: {} copied from {} (from Nothirium Shaders 0.10.0), all keys. The old {} was not "
                    + "touched.", FILE_NAME, OLD_FILE_NAME, OLD_FILE_NAME);
        } catch (java.io.IOException e) {
            WorldgenStatus.LOG.warn("[gambiarra] Could not migrate {} to {} ({}); using defaults", OLD_FILE_NAME, FILE_NAME, e.toString());
        }
    }

    /** Game folder (or dedicated server folder). */
    public static File home() {
        return Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
    }

    private static boolean override(String key, boolean value) {
        if (DevHooks.ON) {
            String p = System.getProperty("nthrc." + key);
            if (p != null && !p.trim().isEmpty()) {
                return Boolean.parseBoolean(p.trim());
            }
        }
        return value;
    }

}
