// SPDX-License-Identifier: MIT
package nthcompat.oflights;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.DevHooks;
import gambiarra.common.JarUtil;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * OptiFine dynamic lights (mixins.nthcompat.oflights.json, OfLightsMixinPlugin; 0.10.0, docs/item2-luz-dinamica.md):
 *  - optifineDynamicLightsSnapshot (item e): the per-block lookup without the global lock (OfLightsSnapshot);
 *  - optifineDynamicLightsBlockStep (item b): each source only updates (and marks sections) when it changes block or level;
 *  - optifineDynamicLightsReachedOnly (item a): of the sections Nothirium marks for each source, only those the light reaches and that
 *    have blocks.
 *
 * Each one only applies with its option on and the classes it touches byte for byte equal to the checked ones (SHA-256): OptiFine
 * 1.12.2 HD U G5's and, for (b) and (a), Nothirium 0.4.9's MixinDynamicLight (which replaces OptiFine's update) and its
 * AbstractRenderChunk. They only act after the injections are counted in the final class. In the development build, the mixins of (a)
 * apply even with it off, only to count the light's marks (the "before" of the measurements). No OptiFine types here: this class runs
 * with or without it.
 */
public final class OfLightsGate {

    private OfLightsGate() {
    }

    static final String OPTIFINE_MARKER = "optifine/OptiFineClassTransformer.class";
    static final String NOTHIRIUM_MARKER = "meldexun/nothirium/mc/Nothirium.class";

    private static final String[][] SNAPSHOT_CLASSES = {
            { "net/optifine/DynamicLights.class", "cfcd5705eb63e17b8135129420aeac44298c2b58ce879449735b83916774573d" },
            { "net/optifine/DynamicLight.class", "94847ad68156c4025f61dc821f620829cf6d8af9d40bcfd70fc2cdf15f80b434" },
            { "net/optifine/DynamicLightsMap.class", "5f01d525027ef3bc41f020f9e522d6d5e9a36771ed9b1aabfe6ce07edce3905d" },
    };
    private static final String[][] LIGHT_UPDATE_CLASSES = {
            { "net/optifine/DynamicLights.class", "cfcd5705eb63e17b8135129420aeac44298c2b58ce879449735b83916774573d" },
            { "net/optifine/DynamicLight.class", "94847ad68156c4025f61dc821f620829cf6d8af9d40bcfd70fc2cdf15f80b434" },
    };
    private static final String[][] NOTHIRIUM_CLASSES = {
            { "meldexun/nothirium/mc/mixin/optifine/MixinDynamicLight.class",
                    "fa8c5c6df9212f476824354245746ae88cd772f92928a9e88add0408275a78c7" },
            { "meldexun/nothirium/renderer/chunk/AbstractRenderChunk.class",
                    "3fc3b10784cbbba18563469b2b205d8645732b6972928706fc075dd902243529" },
    };

    /** Mixin -> injections in the final class (handlers nthcompat$&lt;Mixin&gt;$...). */
    static final String SNAPSHOT_MIXIN = "OfDynamicLightsMixin";
    static final String LIGHT_UPDATE_MIXIN = "OfDynamicLightMixin";
    static final String MARK_MIXIN = "NothiriumMarkDirtyLightMixin";
    /** update, entityRemoved, removeLights, clear and getLightLevel(BlockPos). */
    static final int SNAPSHOT_INJECTIONS = 5;
    static final int LIGHT_UPDATE_INJECTIONS = 1;
    static final int MARK_INJECTIONS = 1;

    private static boolean evaluated;
    private static boolean present;
    private static String problem;
    static boolean snapshotAllowed;
    static boolean blockStepAllowed;
    static boolean reachedOnlyAllowed;
    /** The marking mixins (OfDynamicLightMixin + NothiriumMarkDirtyLightMixin) apply: with (a), or only to count (dev). */
    static boolean lightMarksAllowed;
    private static boolean lightUpdateHooked;
    private static boolean markHooked;
    /** Allowed and with the injections checked: the handlers only act with this. */
    static volatile boolean snapshotOk;
    static volatile boolean blockStepOk;
    static volatile boolean lightMarksHooked;
    static volatile boolean reachedOnlyOk;
    private static final Map<String, String> audit = new LinkedHashMap<>();

    /** validationMode: does each lookup through OptiFine too and compares (bit for bit). */
    static final boolean VALIDATE = gambiarra.common.ValidationLog.on();

    static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        boolean wantSnapshot = CompatConfig.optifineDynamicLightsSnapshot;
        boolean wantBlockStep = CompatConfig.optifineDynamicLightsBlockStep;
        boolean wantReached = CompatConfig.optifineDynamicLightsReachedOnly;
        File optifine = JarUtil.jarOf(OPTIFINE_MARKER);
        present = optifine != null;
        if (!present) {
            return;
        }
        if (!wantSnapshot && !wantBlockStep && !wantReached && !DevHooks.ON) {
            CompatStatus.LOG.info("[gambiarra] OptiFine dynamic lights: optimizations disabled in the config");
            return;
        }
        try {
            String snapshotPrint = wantSnapshot ? mismatch(optifine, SNAPSHOT_CLASSES) : null;
            String updatePrint = mismatch(optifine, LIGHT_UPDATE_CLASSES);
            // Nothirium replaces OptiFine's update with its own (same position math) and marks the sections through markDirty.
            File nothirium = JarUtil.jarOf(NOTHIRIUM_MARKER);
            String nothiriumPrint = nothirium == null ? null : mismatch(nothirium, NOTHIRIUM_CLASSES);
            snapshotAllowed = wantSnapshot && snapshotPrint == null;
            blockStepAllowed = wantBlockStep && updatePrint == null && nothiriumPrint == null;
            lightMarksAllowed = (wantReached || DevHooks.ON) && updatePrint == null && nothirium != null && nothiriumPrint == null;
            reachedOnlyAllowed = wantReached && lightMarksAllowed;
            String print = snapshotPrint != null ? snapshotPrint : updatePrint != null ? updatePrint : nothiriumPrint;
            if (print != null) {
                problem = "class differs from the checked one: " + print;
            } else if (wantReached && nothirium == null) {
                problem = "reachedSectionsOnly needs Nothirium";
            }
        } catch (Throwable t) {
            problem = "failed checking the classes: " + t;
            snapshotAllowed = false;
            blockStepAllowed = false;
            lightMarksAllowed = false;
            reachedOnlyAllowed = false;
        }
        CompatStatus.LOG.info("[gambiarra] OptiFine dynamic lights: lockFreeLookup {}, updatePerBlock {}, reachedSectionsOnly {}{}",
                state(wantSnapshot, snapshotAllowed), state(wantBlockStep, blockStepAllowed), state(wantReached, reachedOnlyAllowed),
                problem != null ? " (" + problem + ")" : "");
        if (problem != null && !problem.startsWith("reachedSectionsOnly needs")) {
            boolean byLock = !problem.startsWith("failed");
            if (wantSnapshot && !snapshotAllowed) {
                gambiarra.common.StartupReport.off("optifinedynamiclights.lockFreeLookup", problem, byLock);
            }
            if (wantBlockStep && !blockStepAllowed) {
                gambiarra.common.StartupReport.off("optifinedynamiclights.updatePerBlock", problem, byLock);
            }
            if (wantReached && !reachedOnlyAllowed) {
                gambiarra.common.StartupReport.off("optifinedynamiclights.reachedSectionsOnly", problem, byLock);
            }
        }
    }

    private static String state(boolean wanted, boolean allowed) {
        return wanted ? (allowed ? "enabled" : "OFF") : "disabled in the config";
    }

    private static String mismatch(File jar, String[][] classes) throws java.io.IOException {
        for (String[] c : classes) {
            byte[] bytes = JarUtil.readEntry(jar, c[0]);
            if (bytes == null || !c[1].equals(JarUtil.sha256Hex(bytes))) {
                return c[0];
            }
        }
        return null;
    }

    static boolean anyAllowed() {
        return snapshotAllowed || blockStepAllowed || lightMarksAllowed;
    }

    static boolean applies(String mixinSimpleName) {
        if (SNAPSHOT_MIXIN.equals(mixinSimpleName)) {
            return snapshotAllowed;
        }
        if (LIGHT_UPDATE_MIXIN.equals(mixinSimpleName)) {
            return blockStepAllowed || lightMarksAllowed;
        }
        if (MARK_MIXIN.equals(mixinSimpleName)) {
            return lightMarksAllowed;
        }
        return false;
    }

    static int expectedInjections(String mixinSimpleName) {
        if (SNAPSHOT_MIXIN.equals(mixinSimpleName)) {
            return SNAPSHOT_INJECTIONS;
        }
        if (LIGHT_UPDATE_MIXIN.equals(mixinSimpleName)) {
            return LIGHT_UPDATE_INJECTIONS;
        }
        if (MARK_MIXIN.equals(mixinSimpleName)) {
            return MARK_INJECTIONS;
        }
        return 0;
    }

    /** HandlerCallAudit: a mixin's injection count in the final class. */
    static synchronized void record(String target, String mixin, int found, int expected) {
        audit.put(mixin, found + "/" + expected + " in " + target);
        boolean ok = found == expected;
        if (SNAPSHOT_MIXIN.equals(mixin)) {
            snapshotOk = ok && snapshotAllowed;
        } else if (LIGHT_UPDATE_MIXIN.equals(mixin)) {
            lightUpdateHooked = ok;
            blockStepOk = ok && blockStepAllowed;
        } else if (MARK_MIXIN.equals(mixin)) {
            markHooked = ok;
        }
        lightMarksHooked = lightUpdateHooked && markHooked && lightMarksAllowed;
        reachedOnlyOk = lightMarksHooked && reachedOnlyAllowed;
        if (!ok) {
            CompatStatus.LOG.warn("[gambiarra] OptiFine dynamic lights: {} has {} of {} injections in {}; the optimization does not act", mixin, found,
                    expected, target);
            gambiarra.common.StartupReport.failed("optifinedynamiclights (" + mixin + ")", found + " of " + expected + " injections in " + target);
        }
    }

    /** Smoketest (by reflection): the counters of OfLightsCompat.counters(), or null with no mixin checked. */
    public static long[] counters() {
        return snapshotOk || blockStepOk || lightMarksHooked ? OfLightsCompat.counters() : null;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        m.put("problem", problem);
        m.put("lockFreeLookup", snapshotOk ? "active" : snapshotAllowed ? "allowed, injection not checked" : "disabled");
        m.put("updatePerBlock", blockStepOk ? "active" : blockStepAllowed ? "allowed, injection not checked" : "disabled");
        m.put("reachedSectionsOnly", reachedOnlyOk ? "active" : reachedOnlyAllowed ? "allowed, injection not checked" : "disabled");
        m.put("injections", new LinkedHashMap<>(audit));
        // Only with the injections checked: these classes use OptiFine types.
        if (snapshotOk || blockStepOk || lightMarksHooked) {
            m.putAll(OfLightsCompat.describe());
        }
        return m;
    }

}
