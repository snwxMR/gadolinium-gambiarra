// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nthshaders.common.DevHooks;
import nthshaders.common.LaunchSide;
import nthshaders.common.OptiRefineFingerprint;
import nthshaders.common.StartupReport;

/**
 * Addon state, in one place.
 *
 * Two switches decide whether the addon acts:
 *  - {@link #gatePassed()}: decided ONCE, before mixins are applied (config + versions). Failed = no addon mixin is applied.
 *  - {@link #isActive()}: gate + injection audit. If any addon mixin did not go in whole, the addon stops acting and
 *    Nothirium keeps its original behaviour (GL15 with shaders). It never runs halfway.
 */
public final class Status {

    public static final Logger LOG = LogManager.getLogger("gadolinium");

    private static boolean evaluated;
    private static boolean gatePassed;
    private static boolean injectionsOk = true;

    private static String nothiriumFound;
    private static String optiFineFound;
    private static String optiRefineFound;
    private static boolean optiRefineForced;

    private static final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> injections = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Mixins (simple name) audited with the full count. */
    private static final Set<String> verified = Collections.synchronizedSet(new LinkedHashSet<>());

    // Diagnostics of the probe mixins
    private static volatile int shaderProgramsSetUp;
    private static volatile String lastShaderProgram;
    private static volatile String lastChunkRenderer;

    private Status() {
    }

    /** Runs once, in the mixin plugin's onLoad. */
    public static synchronized void evaluateGate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        if (LaunchSide.isDedicatedServer()) {
            // The jar also loads on a dedicated server (because of the nthrc module); the shader part is client only.
            problems.add("dedicated server: the shader part is client only");
            LOG.info("[gadolinium] Dedicated server: shader part inactive (client only).");
            return;
        }
        try {
            NthConfig.load();
        } catch (Throwable t) {
            problems.add("unreadable config, using defaults: " + t);
            LOG.warn("[gadolinium] Could not read config/{}; using defaults", NthConfig.FILE_NAME, t);
        }
        if (!NthConfig.enabled) {
            problems.add("disabled in the config (enabled=false)");
            LOG.info("[gadolinium] Disabled in the config (enabled=false): no mixin will be applied.");
            return;
        }

        boolean ok = true;
        try {
            nothiriumFound = Compat.nothiriumVersion();
            optiFineFound = Compat.optiFineVersion();
            optiRefineFound = Compat.optiRefineFingerprint();
            // Development build only (DevHooks): simulates another version of each mod, to prove the version gate without other jars.
            nothiriumFound = devVersionOverride("nothirium", nothiriumFound);
            optiFineFound = devVersionOverride("optifine", optiFineFound);
            optiRefineFound = devVersionOverride("optirefine", optiRefineFound);
        } catch (Throwable t) {
            problems.add("failed reading versions: " + t);
            LOG.warn("[gadolinium] Failed reading the target mods' versions", t);
            ok = false;
        }

        try {
            List<String> copies = Compat.copiesOfThisMod();
            if (copies.size() > 1) {
                ok = false;
                problems.add("two copies of this mod are installed (" + String.join(", ", copies) + "): remove the old NothiriumShaders "
                        + "jar, Gadolinium replaces it");
            }
        } catch (Throwable t) {
            LOG.warn("[gadolinium] Could not look for an old NothiriumShaders jar next to this one", t);
        }
        if (!Compat.NOTHIRIUM_VERSION.equals(nothiriumFound)) {
            ok = false;
            problems.add("Nothirium unknown: found=" + nothiriumFound + " expected=" + Compat.NOTHIRIUM_VERSION);
        }
        if (!Compat.OPTIFINE_VERSION.equals(optiFineFound)) {
            ok = false;
            problems.add("OptiFine unknown: found=" + optiFineFound + " expected=" + Compat.OPTIFINE_VERSION);
        }
        if (optiRefineFound == null) {
            ok = false;
            problems.add("OptiRefine absent");
        } else if (OptiRefineFingerprint.buildOf(optiRefineFound) == null) {
            String msg = "OptiRefine with an unknown fingerprint: found=" + optiRefineFound
                    + " (known builds: " + OptiRefineFingerprint.knownNames() + ")";
            if (NthConfig.forceOptiRefineFingerprint) {
                optiRefineForced = true;
                LOG.warn("[gadolinium] {} -- activated anyway (forceOptiRefineFingerprint=true)", msg);
            } else {
                ok = false;
                problems.add(msg);
            }
        }

        gatePassed = ok;
        if (ok) {
            String build = OptiRefineFingerprint.buildOf(optiRefineFound);
            LOG.info("[gadolinium] Versions recognized (Nothirium {}, {}, OptiRefine {} {}{}): mixins allowed.",
                    nothiriumFound, optiFineFound, build != null ? build : "unknown", shortFp(optiRefineFound), optiRefineForced ? " [FORCED]" : "");
        } else {
            LOG.warn("[gadolinium] ADDON OFF: no mixin will be applied. Reasons:");
            for (String p : problems) {
                LOG.warn("[gadolinium]   - {}", p);
            }
            String reasons = String.join("; ", problems);
            if (reasons.contains("unknown")) {
                StartupReport.lock("enabled", reasons);
            } else {
                StartupReport.failed("enabled", reasons);
            }
        }
    }

    private static String devVersionOverride(String mod, String found) {
        if (DevHooks.ON) {
            String forced = System.getProperty("nthshaders.testVersion." + mod);
            if (forced != null) {
                LOG.warn("[gadolinium] [TEST] {} version simulated: '{}' instead of '{}' (-Dnthshaders.testVersion.{})", mod,
                        forced, found, mod);
                return forced;
            }
        }
        return found;
    }

    public static boolean gatePassed() {
        return gatePassed;
    }

    /**
     * Gate + ALL the mixins in Injections.EXPECTED applied and audited with the full count. A mixin whose target class has not
     * loaded yet counts as unverified: whoever is about to act (RendererSelection) loads the target classes before asking.
     */
    public static boolean isActive() {
        if (!gatePassed || !injectionsOk) {
            return false;
        }
        synchronized (verified) {
            return verified.containsAll(Injections.EXPECTED.keySet());
        }
    }

    /** Mixins not verified yet (for diagnostics). */
    public static List<String> unverifiedMixins() {
        List<String> out = new ArrayList<>(Injections.EXPECTED.keySet());
        synchronized (verified) {
            out.removeAll(verified);
        }
        return out;
    }

    private static final String PENDING = "pending";

    /** Without an audit there is no proof the injections went in: the addon does not act. */
    public static void auditUnavailable() {
        injectionsOk = false;
        problems.add("injection audit unavailable (did Mixin internals change?)");
        LOG.warn("[gadolinium] Injection audit unavailable -- addon disabled at run time; Nothirium keeps its original behaviour.");
        StartupReport.failed("enabled", "injection audit unavailable");
    }

    /** Called by the plugin in postApply: the count comes later (InjectionAudit). */
    public static void injectionPending(String mixin, String target) {
        injections.put(mixin + " -> " + target, PENDING);
    }

    /** Called by InjectionAudit at the end of the target class's transformation. */
    public static void recordInjection(String mixin, String target, int found, int expected) {
        String result = found + "/" + expected;
        injections.put(mixin + " -> " + target, result);
        if (found < expected) {
            injectionsOk = false;
            String msg = "incomplete injection: " + mixin + " in " + target + " (" + result + ")";
            problems.add(msg);
            LOG.warn("[gadolinium] {} -- addon disabled at run time; Nothirium keeps its original behaviour.", msg);
            StartupReport.failed("enabled", msg);
        } else {
            verified.add(mixin);
            LOG.info("[gadolinium] Mixin applied: {} -> {} ({} injections)", mixin, target, result);
        }
    }

    public static void onShaderProgramSetup(String programName) {
        shaderProgramsSetUp++;
        lastShaderProgram = programName;
    }

    public static void onChunkRendererCreated(String description) {
        lastChunkRenderer = description;
        rendererHistory.add(description);
        LOG.info("[gadolinium] Chunk renderer: {}", description);
    }

    private static final List<String> rendererHistory = Collections.synchronizedList(new ArrayList<>());

    private static String shortFp(String fp) {
        return fp == null ? "null" : fp.substring(0, Math.min(12, fp.length()));
    }

    /** Summary readable by reflection (used by the smoketest for perf.json). */
    /** Why the addon is off (empty when active). */
    public static List<String> problemList() {
        synchronized (problems) {
            return new ArrayList<>(problems);
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configEnabled", NthConfig.enabled);
        m.put("gatePassed", gatePassed);
        m.put("active", isActive());
        m.put("nothirium", nothiriumFound);
        m.put("optifine", optiFineFound);
        m.put("optirefineFingerprint", optiRefineFound);
        m.put("optirefineBuild", OptiRefineFingerprint.buildOf(optiRefineFound));
        m.put("optirefineKnown", OptiRefineFingerprint.knownNames());
        m.put("optirefineForced", optiRefineForced);
        m.put("problems", new ArrayList<>(problems));
        m.put("injections", new LinkedHashMap<>(injections));
        m.put("unverifiedMixins", unverifiedMixins());
        m.put("shaderProgramsSetUp", shaderProgramsSetUp);
        m.put("lastShaderProgram", lastShaderProgram);
        m.put("lastChunkRenderer", lastChunkRenderer);
        m.put("rendererHistory", new ArrayList<>(rendererHistory));
        m.put("shaderPrograms", nthshaders.render.ShaderPrograms.describe());
        m.put("bufferSync", nthshaders.render.BufferSync.describe());
        try {
            m.put("shadowPass", nthshaders.render.ShadowArea.describe());
        } catch (Throwable t) {
            // Without OptiFine its classes do not exist; the shadow pass does not even run.
            m.put("shadowPass", "unavailable: " + t);
        }
        m.put("gpu", Diagnostics.gpu());
        m.put("gpuCulling", nthshaders.gpu.GpuCulling.describe());
        m.put("glErrorGuard", nthshaders.render.GlErrorGuard.describe());
        return m;
    }

}
