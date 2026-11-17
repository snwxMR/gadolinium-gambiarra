// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.io.File;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.Configuration;
import nthshaders.common.ConfigMigration;
import nthshaders.common.DevHooks;

/**
 * Addon config (config/gadolinium.cfg).
 *
 * Read early, by the mixin plugin, before any target class loads: that is why it does not use Forge's @Config system
 * (which only exists later).
 */
public final class NthConfig {

    public static final String FILE_NAME = "gadolinium.cfg";
    /** The name up to 1.0.0-rc7, when the mod was called Nothirium Shaders. */
    static final String OLD_FILE_NAME = "nthshaders.cfg";

    /** Turns the whole addon on/off. Off = no mixin is applied. */
    public static boolean enabled = true;

    /**
     * Activates the addon even if OptiRefine's fingerprint does not match.
     * Does not apply to Nothirium/OptiFine: an unknown version of those always turns it off.
     */
    public static boolean forceOptiRefineFingerprint = false;

    /**
     * Diagnostics: measures whether any GL43 buffer is written while the GPU is still using that copy (BufferSync). Costs
     * about 3% FPS; off by default.
     */
    public static boolean measureBufferSync = false;

    /**
     * Diagnostics: GPU time per frame of GPU culling, Hi-Z and the terrain draw, with OpenGL time queries (nthshaders.gpu.GpuTime).
     * With gpuOcclusionCulling, Hi-Z alternates on and off every 2 s to compare. Off by default.
     */
    public static boolean measureGpuTime = false;

    /**
     * Shadow pass: draws only the sections OptiFine would draw (the shadow distance the pack asks for,
     * Shaders.getShadowRenderDistance), instead of the whole view distance. See ShadowArea.
     */
    public static boolean limitShadowPass = true;

    /** With the window out of focus, at most this FPS (see nthshaders.compat.UnfocusedFps); 0 = no limit. */
    public static int unfocusedFpsLimit = 15;

    /**
     * Terrain culling on the GPU (nthshaders.gpu.GpuCulling): Nothirium's visibility traversal only runs when something
     * changes, and the frustum is tested by a compute shader every frame. Needs GL 4.6 or ARB_indirect_parameters.
     */
    public static boolean gpuCulling = true;

    /** With gpuCulling and shaders: the shadow pass on the GPU too, tested against the shadow map volume. */
    public static boolean gpuShadowLightCulling = true;

    /** With gpuCulling: two-phase Hi-Z in the main pass (what is behind terrain is not drawn). */
    public static boolean gpuOcclusionCulling = false;

    /** With gpuCulling and without gpuOcclusionCulling: Hi-Z turned on by itself while the GPU is the bottleneck. */
    public static boolean gpuOcclusionAuto = false;

    /**
     * With gpuCulling: per-direction face culling (nthshaders.gpu.FaceCulling). Opaque faces are split by direction at build
     * time, and only the groups that can face the camera are drawn.
     */
    public static boolean gpuFaceCulling = true;

    /** With gpuFaceCulling and gpuShadowLightCulling: in the shadow pass, only the groups facing the light. */
    public static boolean gpuFaceCullingShadow = true;

    /**
     * With gpuCulling: entities and tile entities are only drawn if a section touched by their box was reached by the
     * visibility traversal, as in Embeddium (nthshaders.gpu.EntityGraphCulling).
     */
    public static boolean gpuCullingEntities = true;

    /**
     * With gpuCulling: a section outside the lists marked dirty only asks for the visibility traversal if it could give another
     * list (nthshaders.gpu.GpuTerrain.noOpDirty).
     */
    public static boolean gpuCullingSkipNoOpDirtyRebuilds = true;

    /*
     * "compat" section: the compatibility fixes that depend on Nothirium (nthshaders.compat.ShadersCompat). Up to 0.10.0 they
     * lived in nthcompat.cfg; on first start the values from there are copied here (migrateCompat).
     */
    static final String COMPAT = "compat";
    /** Dormant copy of the RenderLib x OptiRefine fix (only acts without Gambiarra, which has its own). */
    public static boolean fixRenderLibOptiRefine = true;
    public static boolean fixUpdateClouds = true;
    public static boolean fixEntityRendering = true;
    /** The OptiFine members the entity fix uses every frame, through MethodHandle (no reflection). */
    public static boolean entityFixFastAccess = true;
    /** Limit on Nothirium's compile buffer sets (see nthshaders.compat.CompileBufferLimit). */
    public static boolean limitChunkCompileBuffers = true;
    public static int chunkCompileBuffersPerThread = 2;
    /** Nothirium's chunk build threads (see CompileThreads): 0 = automatic, -1 = Nothirium's own. */
    public static int chunkCompileThreads = 0;
    /** Priority of those threads (Java, 1 to 10): 5 = normal; 10 = as Nothirium leaves it (inherited from the client). */
    public static int chunkCompileThreadPriority = 5;
    /** Order of Nothirium's builds (see CompilePriority): inViewFirst, inViewOnly or arrivalOrder. */
    public static String chunkCompileOrder = "inViewFirst";
    /** The Smart Animations shadow pass race with the build threads (SmartAnimationsActiveMixin). */
    public static boolean fixSmartAnimationsShadowRace = true;

    private static boolean loaded;

    private NthConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        File file = new File(new File(gameDir(), "config"), FILE_NAME);
        migrateOldFile(file);
        Configuration cfg = new Configuration(file);
        cfg.load();
        ConfigMigration migration = new ConfigMigration(cfg, Status.LOG, "[gadolinium]", FILE_NAME);
        migration.renameSection("diagnostico", "diagnostics");
        migration.removeKey(Configuration.CATEGORY_GENERAL, "gpuCullingCompileEmptyDirectly", "gpuCullingSkipNoOpDirtyRebuilds already covers the same case");
        cfg.getBoolean("validationMode", Configuration.CATEGORY_GENERAL, false,
                "Check session: where a comparison is possible, the original path runs next to the optimized one. "
                        + "Differences go to logs/gadolinium-validation.log, with the per-optimization counts when the game exits. "
                        + "Leave it off for performance. Read directly from the file (nthshaders.common.ValidationLog).");
        enabled = cfg.getBoolean("enabled", Configuration.CATEGORY_GENERAL, true,
                "Keeps Nothirium's GL43 render engine with OptiFine shader packs. Off: Nothirium goes back to its original "
                        + "behaviour (GL15 with shaders).");
        forceOptiRefineFingerprint = cfg.getBoolean("forceOptiRefineFingerprint", Configuration.CATEGORY_GENERAL, false,
                "Activates the addon even if OptiRefine's fingerprint is unknown (a build from another commit). Use at "
                        + "your own risk: the addon was validated only against the known commit.");
        measureBufferSync = cfg.getBoolean("measureBufferSync", "diagnostics", false,
                "Diagnostics: counts, in the status log, GL43 buffer writes made while the GPU was still using that copy "
                        + "(should always be 0). With GPU culling, it also counts how many times a buffer set was rewritten with the "
                        + "GPU still on it (not an error there). Costs about 3% FPS.");
        measureGpuTime = cfg.getBoolean("measureGpuTime", "diagnostics", false,
                "Diagnostics, with gpuCulling: measures with OpenGL time queries how much GPU time per frame GPU culling (the "
                        + "compute shaders), Hi-Z (the depth copies and the pyramid) and the terrain draw of the main pass take, "
                        + "separately with and without a shader pack. Shown in F3, every 30 s in the log and in "
                        + "logs/gadolinium-diagnostics.txt. With gpuOcclusionCulling on, Hi-Z also alternates on and off every 2 s, "
                        + "so the terrain draw is measured with and without it in the same scene (stand still for a minute): the "
                        + "image does not change, only the GPU work. The queries never wait for the GPU; a failure only turns the "
                        + "measurement off. Off by default.");
        limitShadowPass = cfg.getBoolean("limitShadowPass", Configuration.CATEGORY_GENERAL, true,
                "Shadow pass: draws only the sections within the shadow distance the shader pack asks for "
                        + "(shadowDistanceRenderMul), as OptiFine does without Nothirium. Off: the whole view distance.");
        unfocusedFpsLimit = cfg.getInt("unfocusedFpsLimit", Configuration.CATEGORY_GENERAL, 15, 0, 240,
                "With the game window out of focus (another program in front), at most this FPS. Saves CPU and GPU while "
                        + "you are not playing; the integrated server carries on normally. 0 = no limit (for example, to record with "
                        + "the window out of focus).");
        gpuCulling = cfg.getBoolean("gpuCulling", Configuration.CATEGORY_GENERAL, true,
                "Terrain culling on the GPU, with and without shaders. Nothirium's visibility traversal (which decides "
                        + "what is built and what can be seen through what) only runs when something changes, instead of every "
                        + "frame, and a compute shader tests the frustum and fog of each section and builds the draw commands "
                        + "(glMultiDrawArraysIndirectCount). Less CPU per frame. Needs OpenGL 4.6 or ARB_indirect_parameters "
                        + "(current NVIDIA, AMD and Intel); without that, or on any error, Nothirium carries on as before. Takes "
                        + "effect after restarting the game.");
        gpuShadowLightCulling = cfg.getBoolean("gpuShadowLightCulling", Configuration.CATEGORY_GENERAL, true,
                "With gpuCulling and shaders: the shadow pass also goes to the GPU, tested against the shadow map volume "
                        + "(the light) and RenderLib's extended frustum; replaces limitShadowPass in that pass. Off: the shadow pass "
                        + "stays as before (CPU traversal with limitShadowPass).");
        gpuOcclusionCulling = cfg.getBoolean("gpuOcclusionCulling", Configuration.CATEGORY_GENERAL, false,
                "EXPERIMENTAL. With gpuCulling: two-phase Hi-Z in the main pass. First draws the solid terrain of what was "
                        + "visible in the previous frame, builds a depth pyramid from it and only draws the rest that is not behind "
                        + "it. Saves GPU time in caves, forests and mountains; costs ~0.1-0.3 ms of GPU per frame. Clouds and sky "
                        + "never hide terrain.");
        gpuOcclusionAuto = cfg.getBoolean("gpuOcclusionAuto", Configuration.CATEGORY_GENERAL, false,
                "EXPERIMENTAL. With gpuCulling and gpuOcclusionCulling off: turns Hi-Z on by itself while the GPU is the "
                        + "bottleneck (measured from the GPU queue delay, every 2 s) and off when the CPU becomes the bottleneck "
                        + "again. The image does not change. The log counts each switch.");
        gpuFaceCulling = cfg.getBoolean("gpuFaceCulling", Configuration.CATEGORY_GENERAL, true,
                "With gpuCulling: per-direction face culling. When each section is built, the faces of the opaque layers "
                        + "(solid and cutout) are split by direction (up, down, north, south, east, west and 'any'), and the GPU "
                        + "only draws the groups that can face the camera (up to 4 commands per section). The same vertices, just in "
                        + "another order. With shaders, the blocks in the pack's block.properties (leaves and plants in the wind, "
                        + "for example) stay in 'any'. Water and stained glass (translucent layer) do not change. Takes effect after "
                        + "restarting the game.");
        gpuFaceCullingShadow = cfg.getBoolean("gpuFaceCullingShadow", Configuration.CATEGORY_GENERAL, true,
                "With gpuFaceCulling, shaders and gpuShadowLightCulling: in the shadow pass, only the face groups facing "
                        + "the light (sun or moon). Off: the shadow pass draws all faces, as before. Takes effect after restarting "
                        + "the game.");
        gpuCullingEntities = cfg.getBoolean("gpuCullingEntities", Configuration.CATEGORY_GENERAL, true,
                "With gpuCulling: entities and tile entities (chests, signs...) are only drawn if the visibility traversal "
                        + "reached a section touched by their box, as Sodium/Embeddium do. Hides, for example, mobs and chests in a "
                        + "cave not connected to where you are. Always drawn: the shadow with shaders, you, what you ride, and "
                        + "entities marked to ignore the frustum. Needs RenderLib 1.4.5. Takes effect after restarting the game.");
        gpuCullingSkipNoOpDirtyRebuilds = cfg.getBoolean("gpuCullingSkipNoOpDirtyRebuilds", Configuration.CATEGORY_GENERAL, true,
                "With gpuCulling: when a section outside the candidate list is marked for a rebuild (fire, light, a "
                        + "changed block), the visibility traversal is only redone if it could change: not if the last traversal did "
                        + "not even reach it, nor if it was already built empty and still has no blocks. It stays marked and is "
                        + "built when the next traversal passes through it (half a second at most). Fewer traversals with lots of "
                        + "fire or dynamic light. Off: traversal on every such mark.");
        loadCompat(cfg, migration);
        migration.report();
        migration.save();

        if (DevHooks.ON) {
            // Development build only: command line overrides (used by the smoketest).
            String prop = System.getProperty("nthshaders.enabled");
            if (prop != null) {
                enabled = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.measureGpuTime");
            if (prop != null) {
                measureGpuTime = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.measureBufferSync");
            if (prop != null) {
                measureBufferSync = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.forceOptiRefineFingerprint");
            if (prop != null) {
                forceOptiRefineFingerprint = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.limitShadowPass");
            if (prop != null) {
                limitShadowPass = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuCulling");
            if (prop != null) {
                gpuCulling = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuShadowLightCulling");
            if (prop != null) {
                gpuShadowLightCulling = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuOcclusionCulling");
            if (prop != null) {
                gpuOcclusionCulling = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuFaceCulling");
            if (prop != null) {
                gpuFaceCulling = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuFaceCullingShadow");
            if (prop != null) {
                gpuFaceCullingShadow = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuCullingEntities");
            if (prop != null) {
                gpuCullingEntities = Boolean.parseBoolean(prop);
            }
            prop = System.getProperty("nthshaders.gpuCullingSkipNoOpDirtyRebuilds");
            if (prop != null) {
                gpuCullingSkipNoOpDirtyRebuilds = Boolean.parseBoolean(prop);
            }
            // Compat section: older scenarios use -Dnthcompat.X; -Dnthshaders.X also works.
            prop = compatProp("fixRenderLibOptiRefine");
            if (prop != null) {
                fixRenderLibOptiRefine = Boolean.parseBoolean(prop);
            }
            prop = compatProp("fixUpdateClouds");
            if (prop != null) {
                fixUpdateClouds = Boolean.parseBoolean(prop);
            }
            prop = compatProp("fixEntityRendering");
            if (prop != null) {
                fixEntityRendering = Boolean.parseBoolean(prop);
            }
            prop = compatProp("limitChunkCompileBuffers");
            if (prop != null) {
                limitChunkCompileBuffers = Boolean.parseBoolean(prop);
            }
            prop = compatProp("chunkCompileBuffersPerThread");
            if (prop != null) {
                chunkCompileBuffersPerThread = Integer.parseInt(prop);
            }
            prop = compatProp("chunkCompileThreads");
            if (prop != null) {
                chunkCompileThreads = Integer.parseInt(prop);
            }
            prop = compatProp("chunkCompileThreadPriority");
            if (prop != null) {
                chunkCompileThreadPriority = Integer.parseInt(prop);
            }
            prop = compatProp("chunkCompileOrder");
            if (prop != null) {
                chunkCompileOrder = prop;
            }
            prop = compatProp("fixSmartAnimationsShadowRace");
            if (prop != null) {
                fixSmartAnimationsShadowRace = Boolean.parseBoolean(prop);
            }
        }
    }

    private static String compatProp(String key) {
        String prop = System.getProperty("nthshaders." + key);
        if (prop == null) {
            prop = System.getProperty("nthcompat." + key);
        }
        return prop == null || prop.trim().isEmpty() ? null : prop.trim();
    }

    /**
     * First start of Gadolinium coming from Nothirium Shaders (up to 1.0.0-rc7): with no gadolinium.cfg and an nthshaders.cfg present,
     * the old file is copied as it is (same keys, values and sections; the migrations below then run on the copy). The old file is not
     * touched. If the copy fails, the defaults are used and the log says why.
     */
    private static void migrateOldFile(File file) {
        File old = new File(file.getParentFile(), OLD_FILE_NAME);
        if (file.exists() || !old.isFile()) {
            return;
        }
        try {
            java.nio.file.Files.copy(old.toPath(), file.toPath());
            Status.LOG.warn("[gadolinium] Config migrated: {} copied to {} (Nothirium Shaders is now called Gadolinium), all keys. The old {} "
                    + "was not touched and is no longer read; it can be deleted.", OLD_FILE_NAME, FILE_NAME, OLD_FILE_NAME);
        } catch (java.io.IOException | RuntimeException e) {
            Status.LOG.warn("[gadolinium] Could not copy {} to {} ({}); using defaults", OLD_FILE_NAME, FILE_NAME, e.toString());
        }
    }

    /** The old config the compat section comes from (the other keys from there went to Gambiarra's gambiarra.cfg). */
    static final String OLD_COMPAT_FILE = "nthcompat.cfg";

    /**
     * Compat section. On first start (section still absent) with an old nthcompat.cfg present, its values are the defaults
     * here: nobody coming from an old version loses an option. Each migrated key goes to the log.
     */
    private static void loadCompat(Configuration cfg, ConfigMigration migration) {
        Configuration old = null;
        File oldFile = new File(new File(gameDir(), "config"), OLD_COMPAT_FILE);
        if (!cfg.hasCategory(COMPAT) && oldFile.isFile()) {
            old = new Configuration(oldFile);
            old.load();
        }
        java.util.List<String> migrated = new java.util.ArrayList<>();
        if (old != null) {
            // First the properties with the old values; the getX calls below find them and only set the default and the comment.
            String[][] keys = { { "fixRenderLibOptiRefine", "fixRenderLibOptiRefine" }, { "fixUpdateClouds", "fixUpdateClouds" },
                    { "fixEntityRendering", "fixEntityRendering" }, { "limitChunkCompileBuffers", "limitChunkCompileBuffers" },
                    { "chunkCompileBuffersPerThread", "chunkCompileBuffersPerThread" }, { "chunkCompileThreads", "chunkCompileThreads" },
                    { "chunkCompileThreadPriority", "chunkCompileThreadPriority" }, { "chunkCompileOrder", "chunkCompileOrder" },
                    // in old versions, the race fix was part of fixSmartAnimations
                    { "fixSmartAnimationsShadowRace", "fixSmartAnimations" } };
            for (String[] k : keys) {
                if (old.hasKey(Configuration.CATEGORY_GENERAL, k[1])) {
                    net.minecraftforge.common.config.Property p = old.getCategory(Configuration.CATEGORY_GENERAL).get(k[1]);
                    cfg.get(COMPAT, k[0], p.getString(), null, p.getType());
                    migrated.add(k[0].equals(k[1]) ? k[0] : k[1] + " (as " + k[0] + ")");
                }
            }
        }
        // After the copy above: the old nthcompat.cfg also holds the old value names.
        migration.renameValue(COMPAT, "chunkCompileOrder", "campoDeVisaoPrimeiro", "inViewFirst");
        migration.renameValue(COMPAT, "chunkCompileOrder", "soCampoDeVisao", "inViewOnly");
        migration.renameValue(COMPAT, "chunkCompileOrder", "ordemDeChegada", "arrivalOrder");
        cfg.setCategoryComment(COMPAT, "Compatibility fixes that depend on Nothirium.");
        fixRenderLibOptiRefine = cfg.getBoolean("fixRenderLibOptiRefine", COMPAT, true,
                "With OptiRefine and RenderLib: removes RenderLib's patch on RenderGlobal#renderEntities, which looks for "
                        + "pieces of classic OptiFine that OptiRefine does not have (without this the game does not start). Only "
                        + "acts without Gambiarra, which has the same fix (and its own option).");
        fixUpdateClouds = cfg.getBoolean("fixUpdateClouds", COMPAT, true,
                "Avoids the crash (NullPointerException) in OptiRefine's updateClouds with Nothirium, which never creates "
                        + "the ChunkRenderDispatcher. Only acts when that dispatcher does not exist.");
        fixEntityRendering = cfg.getBoolean("fixEntityRendering", COMPAT, true,
                "Makes entities and tile entities show up with Nothirium + OptiRefine (OptiRefine's renderEntities looks "
                        + "for them in a chunk list Nothirium does not fill). Uses the full mode (with RenderLib's culling) whenever "
                        + "everything it needs from OptiFine/OptiRefine exists, even with an unrecognized OptiRefine; otherwise a "
                        + "fallback mode.");
        entityFixFastAccess = cfg.getBoolean("entityFixFastAccess", COMPAT, true,
                "With fixEntityRendering: reads and writes the fields OptiFine adds to RenderGlobal and EntityRenderer "
                        + "(counters, block damage, fog) every frame through a MethodHandle found once. Off: through reflection "
                        + "(java.lang.reflect.Field); on Java 25 Cleanroom recomputes the field position on every use, which costs "
                        + "~0.7% of the frame. The same values in both cases.");
        limitChunkCompileBuffers = cfg.getBoolean("limitChunkCompileBuffers", COMPAT, true,
                "Limits Nothirium's chunk compile buffer sets (~10 MB of direct memory each). Without the limit, during a "
                        + "long frame Nothirium creates a new set for each finished chunk waiting for GPU upload and never returns "
                        + "them: with a big modpack, high render distance and shaders, direct memory can run out (OutOfMemoryError "
                        + "... direct buffer memory). With the limit, the build waits for a set to return.");
        chunkCompileBuffersPerThread = cfg.getInt("chunkCompileBuffersPerThread", COMPAT,
                2, 1, 16,
                "With limitChunkCompileBuffers: sets per Nothirium compile thread (the number of build threads, see "
                        + "chunkCompileThreads). The total limit is threads x this value.");
        chunkCompileThreads = cfg.getInt("chunkCompileThreads", COMPAT, 0, -1, 256,
                "Nothirium chunk build threads. 0 = automatic: processors / 4, at least 2 and at most what Nothirium would "
                        + "use (processors - 2). -1 = Nothirium's number (processors - 2). Any other number = that. With many "
                        + "threads they fight the client and the server for cores and cache. Takes effect the next time Nothirium "
                        + "creates the renderer (F3+A or re-entering the world).");
        chunkCompileThreadPriority = cfg.getInt("chunkCompileThreadPriority", COMPAT,
                5, 1, 10,
                "Priority of Nothirium's chunk build threads (1 to 10, Java's). 5 = normal, below the client and the "
                        + "server. 10 = as Nothirium leaves it (they inherit the maximum, the same as the client). With "
                        + "chunkCompileThreads=-1 and 10 here, nothing changes in Nothirium.");
        chunkCompileOrder = cfg.getString("chunkCompileOrder", COMPAT, "inViewFirst",
                "Order of Nothirium's chunk builds when more are waiting than there are threads. 'inViewFirst' (default): "
                        + "sections on screen go first, and one that has waited half a second jumps ahead. 'inViewOnly': more "
                        + "aggressive, off-screen sections are only built when they come back on screen. With a shader pack active "
                        + "it behaves as the default, and the log says so: the shadow pass needs the off-screen sections. "
                        + "'arrivalOrder': as in Nothirium. Whatever is within 40 blocks, or with the camera in third person, always "
                        + "counts as on screen.",
                new String[] { "inViewFirst", "inViewOnly", "arrivalOrder" });
        fixSmartAnimationsShadowRace = cfg.getBoolean("fixSmartAnimationsShadowRace", COMPAT, true,
                "With Smart Animations on in OptiRefine (Gambiarra turns it on, its fixSmartAnimations option) and "
                        + "shaders: sections built on Nothirium's threads during the shadow pass also mark their animated textures "
                        + "(without this the animation stops on them). Without Gambiarra, nothing changes.");
        if (!migrated.isEmpty()) {
            Status.LOG.info("[gadolinium] Config migrated: {} from {} to the compat section of {}. The old {} was not touched.", migrated, OLD_COMPAT_FILE, FILE_NAME, OLD_COMPAT_FILE);
        }
    }

    private static File gameDir() {
        File home = Launch.minecraftHome;
        return home != null ? home : new File(".");
    }

}
