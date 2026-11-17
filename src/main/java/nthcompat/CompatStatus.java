// SPDX-License-Identifier: MIT
package nthcompat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import gambiarra.common.DevHooks;
import gambiarra.common.JarUtil;
import gambiarra.common.LaunchSide;
import gambiarra.common.OptiRefineFingerprint;
import nthcompat.optirefine.AccPlaceholderFix;
import nthcompat.optirefine.DynamicTextureDataFix;
import nthcompat.optirefine.SmartAnimationsFix;
import nthcompat.optirefine.SmartAnimationsGate;
import nthcompat.renderlib.RenderLibOptiRefineFix;

/**
 * Gambiarra: decides, once and before mixins are applied, which OptiRefine and OptiFine fixes apply to this installation (float view,
 * acc$ placeholders, Smart Animations, SmartLeaves leaves, the RenderLib patch). Those that depend on Nothirium (updateClouds, entities,
 * builds) live in Gadolinium (nthshaders.compat.ShadersCompat). The nthcompat name comes from the module before the split.
 */
public final class CompatStatus {

    public static final Logger LOG = LogManager.getLogger("gambiarra");

    static final String NOTHIRIUM_CLASS = "meldexun/nothirium/mc/Nothirium.class";
    static final String NAUGHTHIRIUM_CLASS = "zone/rong/naughthirium/mixins/optifine/AbstractChunkRendererMixin.class";

    private static boolean evaluated;
    private static boolean dedicatedServer;
    private static String optiRefineFingerprint;
    private static boolean fingerprintExact;
    /** Name of the known OptiRefine build ("78ff8ce", "v0.1.0-alpha"), or null. */
    private static String optiRefineBuild;
    private static boolean floatBufferFixAllowed;
    /** OptiRefine's acc$ placeholders: config on and OptiRefine present (the extension acts class by class). */
    private static boolean accPlaceholderFixAllowed;
    /** OptiRefine v0.1.0-alpha x VintageFix: config on and OptiRefine present (the extension decides on DefaultResourcePack). */
    private static boolean vintageFixFixAllowed;
    /** OptiRefine 78ff8ce's DynamicTexture array swap: config on and 78ff8ce's MixinDynamicTexture (DynamicTextureDataFix). */
    private static boolean dynamicTextureFixAllowed;
    /** General fix for SmartLeaves leaves with LoliASM: only the config decides (the mixin needs nothing beyond the class). */
    private static boolean smartLeavesFixAllowed;
    private static final Map<String, Long> firstRun = new ConcurrentHashMap<>();

    private CompatStatus() {
    }

    public static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        if (LaunchSide.isDedicatedServer()) {
            // Gambiarra loads on a dedicated server (worldgen, nthrc); these fixes are rendering ones.
            dedicatedServer = true;
            LOG.info("[gambiarra] Dedicated server: render fixes inactive (client only).");
            return;
        }
        try {
            CompatConfig.load();
        } catch (Throwable t) {
            LOG.warn("[gambiarra] Could not read config/{}; using defaults", CompatConfig.FILE_NAME, t);
        }
        // SmartLeaves leaves only go inside out with LoliASM + OptiFine, without Nothirium: a general fix, not depending on Nothirium or on
        // OptiRefine's fingerprint.
        smartLeavesFixAllowed = CompatConfig.fixSmartLeavesVertexData;
        LOG.info("[gambiarra] General SmartLeaves leaves fix (LoliASM): {}",
                smartLeavesFixAllowed ? "enabled" : "disabled in the config");
        try {
            boolean nothirium = JarUtil.jarOf(NOTHIRIUM_CLASS) != null;
            optiRefineFingerprint = OptiRefineFingerprint.ofInstalled();
            boolean optiRefine = optiRefineFingerprint != null;
            optiRefineBuild = OptiRefineFingerprint.buildOf(optiRefineFingerprint);
            // OptiRefine v0.1.0-alpha fixed both defects (issues #5 and #6): with it, the two temporary fixes stay off. With 78ff8ce they
            // apply, and with any other build too (each one only acts where it finds the defect).
            boolean fixedUpstream = OptiRefineFingerprint.RELEASE_0_1_0_ALPHA.equals(optiRefineBuild);
            String notNeeded = "not needed: OptiRefine " + OptiRefineFingerprint.RELEASE_0_1_0_ALPHA + " already fixes it";
            // The float view defect is OptiRefine's (it also happens without Nothirium): it only needs to be present. TEMPORARY until OptiRefine
            // fixes it.
            floatBufferFixAllowed = CompatConfig.fixOptiRefineFloatBuffer && optiRefine && !fixedUpstream;
            if (optiRefine) {
                LOG.info("[gambiarra] BufferBuilder float view fix (temporary): {}", floatBufferFixAllowed ? "enabled"
                        : !CompatConfig.fixOptiRefineFloatBuffer ? "disabled in the config" : notNeeded + " (issue #6)");
                if (fixedUpstream && CompatConfig.fixOptiRefineFloatBuffer) {
                    gambiarra.common.StartupReport.notNeeded("fixOptiRefineFloatBuffer", notNeeded + " (issue #6)");
                    if (CompatConfig.fixOptiRefineFloatBufferReuseView) {
                        gambiarra.common.StartupReport.notNeeded("fixOptiRefineFloatBufferReuseView", notNeeded + " (issue #6)");
                    }
                }
            }
            // Untranslated acc$ placeholders come from OptiRefine alone (it happens without Nothirium).
            accPlaceholderFixAllowed = CompatConfig.fixOptiRefineAccPlaceholders && optiRefine && !fixedUpstream;
            if (optiRefine) {
                LOG.info("[gambiarra] OptiRefine acc$ placeholder fix (temporary): {}", accPlaceholderFixAllowed ? "enabled"
                        : !CompatConfig.fixOptiRefineAccPlaceholders ? "disabled in the config" : notNeeded + " (issue #5)");
                if (fixedUpstream && CompatConfig.fixOptiRefineAccPlaceholders) {
                    gambiarra.common.StartupReport.notNeeded("fixOptiRefineAccPlaceholders", notNeeded + " (issue #5)");
                }
            }
            // OptiRefine v0.1.0-alpha x VintageFix: decided when DefaultResourcePack is about to get its mixins (ResourceProbePriorityFix);
            // here only whether the extension goes in.
            vintageFixFixAllowed = CompatConfig.fixOptiRefineVintageFix && optiRefine;
            if (optiRefine) {
                LOG.info("[gambiarra] OptiRefine x VintageFix fix: {}", vintageFixFixAllowed ? "enabled (acts only if both change DefaultResourcePack)"
                        : "disabled in the config");
            }
            // The lightmap that goes black after turning shaders on and off: OptiRefine's alone (it happens without Nothirium), fixed in
            // v0.1.0-alpha; with any other build than 78ff8ce, off by the code lock.
            if (optiRefine) {
                String option = DynamicTextureDataFix.OPTION;
                String why;
                if (!CompatConfig.fixOptiRefineDynamicTextureData) {
                    why = "disabled in the config";
                } else if (fixedUpstream) {
                    why = notNeeded + " (commit 2cab6ef)";
                    gambiarra.common.StartupReport.notNeeded(option, why);
                } else {
                    String problem = DynamicTextureDataFix.lockProblem();
                    dynamicTextureFixAllowed = problem == null;
                    why = problem == null ? "active" : "OFF by the code lock: " + problem;
                    if (problem != null) {
                        gambiarra.common.StartupReport.lock(option, problem);
                    }
                }
                DynamicTextureDataFix.setState(why);
                LOG.info("[gambiarra] OptiRefine dynamic texture array fix (black lightmap after shaders off): {}",
                        dynamicTextureFixAllowed ? "enabled" : why);
            }
            // Smart Animations on OptiRefine: with Nothirium, only with Naughthirium (which marks the terrain sections).
            SmartAnimationsGate.decide(nothirium, JarUtil.jarOf(NAUGHTHIRIUM_CLASS) != null, optiRefine);
            if (optiRefine) {
                LOG.info("[gambiarra] Smart Animations: {}", SmartAnimationsGate.reason());
            }
            if (!optiRefine) {
                return;
            }
            boolean exact = optiRefineBuild != null;
            if (DevHooks.ON && Boolean.getBoolean("nthcompat.testUnknownOptiRefine")) {
                // Development build only: simulates an OptiRefine of another version.
                exact = false;
                LOG.warn("[gambiarra] [TEST] OptiRefine treated as unknown (-Dnthcompat.testUnknownOptiRefine=true)");
            }
            fingerprintExact = exact;
            if (!exact) {
                // The fingerprint is only informational: the fixes apply by capability.
                LOG.warn("[gambiarra] OptiRefine not recognized by fingerprint (found={}, known builds: {}). Gambiarra's fixes only "
                        + "depend on vanilla members and on what is found at run time, so they stay active.", optiRefineFingerprint,
                        OptiRefineFingerprint.knownNames());
            } else {
                LOG.info("[gambiarra] OptiRefine {} recognized by fingerprint", optiRefineBuild);
            }
        } catch (Throwable t) {
            floatBufferFixAllowed = false;
            accPlaceholderFixAllowed = false;
            vintageFixFixAllowed = false;
            dynamicTextureFixAllowed = false;
            LOG.error("[gambiarra] Failed evaluating the fixes; none will be applied.", t);
            gambiarra.common.StartupReport.failed("fixes", t.toString());
        }
    }

    /** true = dedicated server: no render fix (not even RenderLib's) runs. */
    public static boolean dedicatedServer() {
        return dedicatedServer;
    }

    public static boolean floatBufferFixAllowed() {
        return floatBufferFixAllowed;
    }

    public static boolean accPlaceholderFixAllowed() {
        return accPlaceholderFixAllowed;
    }

    public static boolean vintageFixFixAllowed() {
        return vintageFixFixAllowed;
    }

    public static boolean dynamicTextureFixAllowed() {
        return dynamicTextureFixAllowed;
    }

    public static boolean smartLeavesFixAllowed() {
        return smartLeavesFixAllowed;
    }

    /** true = OptiRefine's fingerprint is one of the known builds, 78ff8ce or v0.1.0-alpha (informational only). */
    public static boolean optiRefineRecognized() {
        return fingerprintExact;
    }

    /**
     * Proof of execution: MixinExtras' @WrapMethod is applied after the plugin's postApply, so the audit in postApply cannot see it. Each
     * fix records the first time it really ran.
     */
    public static void markRan(String fix, String detail) {
        if (firstRun.putIfAbsent(fix, System.currentTimeMillis()) == null) {
            LOG.info("[gambiarra] Fix '{}' in action ({})", fix, detail);
        }
    }

    /** Summary readable by reflection (Gadolinium diagnostics, smoketest). */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("optirefineFingerprint", optiRefineFingerprint);
        m.put("optiRefineRecognized", fingerprintExact);
        m.put("optiRefineBuild", optiRefineBuild);
        m.put("floatBufferFixAllowed", floatBufferFixAllowed);
        m.put("smartLeavesFixAllowed", smartLeavesFixAllowed);
        m.put("accPlaceholders", !accPlaceholderFixAllowed ? (OptiRefineFingerprint.RELEASE_0_1_0_ALPHA.equals(optiRefineBuild) ? "not needed (OptiRefine " + optiRefineBuild + ")" : "disabled")
                : !AccPlaceholderFix.isRegistered() ? "not registered with Mixin"
                : "replacements=" + AccPlaceholderFix.rewrites() + ", refused=" + AccPlaceholderFix.refused());
        m.put("optiRefineVintageFix", !vintageFixFixAllowed ? "disabled" : nthcompat.optirefine.ResourceProbePriorityFix.result());
        m.put("optiRefineDynamicTextureData", DynamicTextureDataFix.describe());
        m.put("smartAnimations", SmartAnimationsFix.describe());
        m.put("ran", new LinkedHashMap<>(firstRun));
        m.put("renderLibFix", RenderLibOptiRefineFix.describeResult());
        m.put("iceAndFire", nthcompat.iceandfire.IafCompat.describe());
        m.put("optifineDynamicLights", nthcompat.oflights.OfLightsGate.describe());
        m.put("pulsar", nthcompat.pulsar.PulsarCompat.describe());
        m.put("lod", gambiarra.lod.Lod.describe());
        return m;
    }

    /** For the startup summary: {client options on in the config, of those without their target mod here}. Client only. */
    public static int[] counts() {
        boolean optiRefine = has("optirefine.default.mixin.json");
        boolean optiFine = has("optifine/OptiFineClassTransformer.class");
        boolean renderLib = has("meldexun/renderlib/RenderLib.class");
        boolean iceAndFire = has("com/github/alexthe666/iceandfire/IceAndFire.class");
        boolean nothirium = has("meldexun/nothirium/mc/Nothirium.class");
        boolean pulsar = has("com/sumirelabs/pulsar/Pulsar.class");
        boolean[][] options = {
                { CompatConfig.fixRenderLibOptiRefine, optiRefine && renderLib },
                { CompatConfig.fixOptiRefineFloatBuffer, optiRefine },
                { CompatConfig.fixOptiRefineFloatBuffer && CompatConfig.fixOptiRefineFloatBufferReuseView, optiRefine },
                { CompatConfig.fixOptiRefineAccPlaceholders, optiRefine },
                { CompatConfig.fixOptiRefineVintageFix, optiRefine && has(nthcompat.optirefine.ResourceProbePriorityFix.VINTAGEFIX_CLASS) },
                { CompatConfig.fixOptiRefineDynamicTextureData, optiRefine },
                { CompatConfig.fixSmartLeavesVertexData, optiFine },
                { CompatConfig.fixSmartAnimations, optiRefine },
                { CompatConfig.iceAndFireTabulaPoseCache, iceAndFire },
                { CompatConfig.iceAndFireDistantDragonAnimation, iceAndFire },
                { CompatConfig.optifineDynamicLightsSnapshot, optiFine },
                { CompatConfig.optifineDynamicLightsBlockStep, optiFine },
                { CompatConfig.optifineDynamicLightsReachedOnly, optiFine && nothirium },
                { CompatConfig.pulsarFixSmoothLightCrash, pulsar },
                { CompatConfig.lodSubPixelParts, true },
                { CompatConfig.lodAnimationBySize, true },
                { CompatConfig.lodImpostors, true } };
        int on = 0;
        int without = 0;
        for (boolean[] o : options) {
            if (o[0]) {
                on++;
                if (!o[1]) {
                    without++;
                }
            }
        }
        return new int[] { on, without };
    }

    private static boolean has(String resource) {
        try {
            return JarUtil.jarOf(resource) != null;
        } catch (Throwable t) {
            return false;
        }
    }

}
