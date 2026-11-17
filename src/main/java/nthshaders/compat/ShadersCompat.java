// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nthshaders.common.DevHooks;
import nthshaders.common.JarUtil;
import nthshaders.common.LaunchSide;
import nthshaders.common.OptiRefineFingerprint;
import nthshaders.core.NthConfig;

/**
 * The compatibility fixes that depend on Nothirium: OptiRefine's updateClouds without the ChunkRenderDispatcher, entities
 * and tile entities with OptiRefine's renderEntities, the number, priority, buffers and order of Nothirium's builds, and the
 * Smart Animations shadow pass race with the build threads. Decides, once and before the mixins are applied
 * (mixins.nthshaders.compat.json, ShadersCompatPlugin), what applies to this installation. The options live in
 * gadolinium.cfg, section "compat".
 */
public final class ShadersCompat {

    public static final Logger LOG = LogManager.getLogger("gadolinium");

    /** How renderEntities is handled with Nothirium + OptiRefine (see EntityFixSupport.resolveMode). */
    public enum EntityMode {
        /** Leaves it alone (fix off, not applicable, or something both modes use is missing). */
        OFF,
        /** OptiRefine's body with the chunk loops replaced by RenderLib (with culling). */
        FULL,
        /** Last resort: runs OptiRefine and then draws the chunks' entities through RenderLib. */
        FALLBACK
    }

    static final String NOTHIRIUM_CLASS = "meldexun/nothirium/mc/Nothirium.class";
    static final String RENDERLIB_ENTITY_MANAGER = "meldexun/renderlib/renderer/EntityRenderManager.class";
    static final String RENDERLIB_TILE_MANAGER = "meldexun/renderlib/renderer/TileEntityRenderManager.class";
    static final String NAUGHTHIRIUM_CLASS = "zone/rong/naughthirium/mixins/optifine/AbstractChunkRendererMixin.class";
    /** Gambiarra's Smart Animations fix (just the name: Gadolinium does not depend on it). */
    static final String GAMBIARRA_SMART_ANIMATIONS = "nthcompat/optirefine/SmartAnimationsFix.class";

    private static boolean evaluated;
    private static boolean dedicatedServer;
    private static boolean nothirium;
    private static boolean optiRefine;
    private static String optiRefineFingerprint;
    private static boolean fingerprintExact;
    private static boolean updateCloudsAllowed;
    private static boolean entityFixEnabled;
    private static boolean compileBufferLimitAllowed;
    private static boolean compileBufferMixinsAllowed;
    private static boolean compileThreadsAllowed;
    private static boolean smartAnimationsRaceAllowed;
    private static volatile EntityMode entityMode;
    private static volatile String entityModeReason = "not decided yet (no renderEntities)";
    private static final Map<String, Long> firstRun = new ConcurrentHashMap<>();
    private static final AtomicLong raceCorrections = new AtomicLong();

    private ShadersCompat() {
    }

    public static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        if (LaunchSide.isDedicatedServer()) {
            dedicatedServer = true;
            return;
        }
        try {
            NthConfig.load();
            File nothiriumJar = JarUtil.jarOf(NOTHIRIUM_CLASS);
            nothirium = nothiriumJar != null;
            optiRefineFingerprint = OptiRefineFingerprint.ofInstalled();
            optiRefine = optiRefineFingerprint != null;
            fingerprintExact = OptiRefineFingerprint.buildOf(optiRefineFingerprint) != null;
            if (DevHooks.ON && Boolean.getBoolean("nthcompat.testUnknownOptiRefine")) {
                fingerprintExact = false;
                LOG.warn("[gadolinium] [TEST] OptiRefine treated as unknown (-Dnthcompat.testUnknownOptiRefine=true)");
            }
            // The buffer queue, the threads and the build order are Nothirium's (with or without OptiRefine).
            compileBufferLimitAllowed = NthConfig.limitChunkCompileBuffers && nothirium;
            compileBufferMixinsAllowed = nothirium && (compileBufferLimitAllowed || DevHooks.ON);
            compileThreadsAllowed = nothirium
                    && (NthConfig.chunkCompileThreads != -1 || NthConfig.chunkCompileThreadPriority != Thread.MAX_PRIORITY);
            CompilePriority.evaluate(nothiriumJar);
            if (nothirium) {
                LOG.info("[gadolinium] Nothirium chunk build threads: {}", compileThreadsAllowed
                        ? CompileThreads.effective() + " (Nothirium would use " + CompileThreads.NOTHIRIUM_DEFAULT + "), priority "
                                + (NthConfig.chunkCompileThreadPriority < Thread.MAX_PRIORITY ? String.valueOf(NthConfig.chunkCompileThreadPriority)
                                        : "inherited")
                        : "as Nothirium leaves it (disabled in the config)");
                LOG.info("[gadolinium] Nothirium compile buffer limit: {}", compileBufferLimitAllowed
                        ? "enabled (" + CompileBufferLimit.cap() + " sets, " + CompileThreads.effective() + " threads)"
                        : "disabled in the config");
            }
            // The shadow pass race only exists with Smart Animations on in OptiRefine and sections built off the render thread
            // (Nothirium). Gambiarra is what turns Smart Animations on, and with Nothirium only with Naughthirium (its
            // SmartAnimationsGate): the same conditions as before, so the mixin only applies where it used to.
            smartAnimationsRaceAllowed = NthConfig.fixSmartAnimationsShadowRace && nothirium && optiRefine
                    && JarUtil.jarOf(NAUGHTHIRIUM_CLASS) != null && JarUtil.jarOf(GAMBIARRA_SMART_ANIMATIONS) != null;
            if (!nothirium || !optiRefine) {
                LOG.info("[gadolinium] Nothirium present={}, OptiRefine present={}: updateClouds/entity fixes do not apply.",
                        nothirium, optiRefine);
                return;
            }
            if (!fingerprintExact) {
                LOG.warn("[gadolinium] OptiRefine not recognized by fingerprint (found={}, known builds: {}). The compatibility fixes "
                        + "only depend on vanilla members and on what is found at run time, so they stay active.", optiRefineFingerprint,
                        OptiRefineFingerprint.knownNames());
            }
            // updateClouds only uses vanilla members and only acts with a null renderDispatcher: harmless even if a newer OptiRefine
            // has already fixed the bug.
            updateCloudsAllowed = NthConfig.fixUpdateClouds;
            boolean renderLib = JarUtil.jarOf(RENDERLIB_ENTITY_MANAGER) != null && JarUtil.jarOf(RENDERLIB_TILE_MANAGER) != null;
            if (NthConfig.fixEntityRendering && !renderLib) {
                LOG.warn("[gadolinium] RenderLib without EntityRenderManager/TileEntityRenderManager: entity fix not applied.");
                nthshaders.common.StartupReport.failed("fixEntityRendering", "RenderLib without EntityRenderManager/TileEntityRenderManager");
            }
            entityFixEnabled = NthConfig.fixEntityRendering && renderLib;
            LOG.info("[gadolinium] Fixes: updateClouds={}, entities={} (OptiRefine {} {})", updateCloudsAllowed,
                    entityFixEnabled ? "mode decided on the first renderEntities" : "OFF",
                    fingerprintExact ? OptiRefineFingerprint.buildOf(optiRefineFingerprint) : "unknown", optiRefineFingerprint.substring(0, 12));
        } catch (Throwable t) {
            updateCloudsAllowed = false;
            entityFixEnabled = false;
            compileBufferLimitAllowed = false;
            compileBufferMixinsAllowed = false;
            compileThreadsAllowed = false;
            smartAnimationsRaceAllowed = false;
            LOG.error("[gadolinium] Failed evaluating the compatibility fixes; none will be applied.", t);
            nthshaders.common.StartupReport.failed("compat", t.toString());
        }
    }

    public static boolean dedicatedServer() {
        return dedicatedServer;
    }

    static boolean optiRefinePresent() {
        return optiRefine;
    }

    public static boolean updateCloudsAllowed() {
        return updateCloudsAllowed;
    }

    public static boolean entityFixEnabled() {
        return entityFixEnabled;
    }

    public static boolean compileBufferLimitAllowed() {
        return compileBufferLimitAllowed;
    }

    public static boolean compileBufferMixinsAllowed() {
        return compileBufferMixinsAllowed;
    }

    public static boolean compileThreadsAllowed() {
        return compileThreadsAllowed;
    }

    public static boolean smartAnimationsRaceAllowed() {
        return smartAnimationsRaceAllowed;
    }

    /** true = OptiRefine's fingerprint is one of the known builds, 78ff8ce or v0.1.0-alpha (informational only). */
    public static boolean optiRefineRecognized() {
        return fingerprintExact;
    }

    /** null while undecided (see EntityFixSupport.mode). */
    public static EntityMode entityMode() {
        return entityMode;
    }

    /** Called once by EntityFixSupport when it decides the mode. */
    public static void setEntityMode(EntityMode mode, String reason) {
        entityModeReason = reason;
        entityMode = mode;
    }

    /** SmartAnimationsActiveMixin: the answer of isActive() off the render thread was corrected. */
    public static void raceCorrected() {
        raceCorrections.incrementAndGet();
    }

    /** Proof of execution: each fix records the first time it really ran. */
    public static void markRan(String fix, String detail) {
        if (firstRun.putIfAbsent(fix, System.currentTimeMillis()) == null) {
            LOG.info("[gadolinium] Fix '{}' in action ({})", fix, detail);
        }
    }

    /** For the diagnostics and the smoketest (by reflection). */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("optirefineFingerprint", optiRefineFingerprint);
        m.put("optirefineRecognized", fingerprintExact);
        m.put("updateCloudsAllowed", updateCloudsAllowed);
        m.put("compileBuffers", compileBufferMixinsAllowed ? CompileBufferLimit.describe() : "mixins not applied");
        m.put("compileThreads", compileThreadsAllowed ? CompileThreads.describe() : "as Nothirium leaves it (disabled in the config or no Nothirium)");
        m.put("compileOrder", CompilePriority.describe());
        m.put("smartAnimationsShadowRace", smartAnimationsRaceAllowed ? "enabled, " + raceCorrections.get() + " answers fixed"
                : "disabled (config, or no Nothirium, OptiRefine, Naughthirium or Gambiarra)");
        m.put("injectionsChecked", ShadersCompatPlugin.auditResults());
        EntityMode mode = entityMode;
        m.put("entityMode", mode != null ? mode.name() : (entityFixEnabled ? "PENDING" : EntityMode.OFF.name()));
        m.put("entityModeReason", entityModeReason);
        m.put("ran", new LinkedHashMap<>(firstRun));
        m.put("renderLibFix", RenderLibOptiRefineFix.describeResult());
        return m;
    }

    /** The compat option of one of this package's mixins, for the startup report. */
    static String optionOf(String mixin) {
        switch (mixin) {
            case "NothiriumCompileBuffersMixin":
                return "limitChunkCompileBuffers";
            case "NothiriumCompilePriorityMixin":
                return "chunkCompileOrder";
            case "NothiriumCompileThreadsMixin":
                return "chunkCompileThreads";
            case "RenderGlobalEntitiesMixin":
                return "fixEntityRendering";
            case "RenderGlobalUpdateCloudsMixin":
                return "fixUpdateClouds";
            case "SmartAnimationsActiveMixin":
                return "fixSmartAnimationsShadowRace";
            default:
                return mixin;
        }
    }

}
