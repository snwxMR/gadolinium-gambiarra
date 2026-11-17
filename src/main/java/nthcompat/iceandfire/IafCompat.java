// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.DevHooks;
import gambiarra.common.JarUtil;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * Ice and Fire fixes in nthcompat (mixins.nthcompat.iceandfire.json, IafMixinPlugin):
 *  - TabulaPoseCache (cacheTabulaPoses): Tabula animation without looking parts up by name every frame;
 *  - DistantDragonAnimation (distantDragonAnimation): distant dragons animated every N frames.
 *
 * Each one only applies with its option on, Ice and Fire 1.9.1 and LLibrary 1.7.20 (mcmod.info) and the classes it touches or depends
 * on byte for byte equal to the checked ones (SHA-256); and it only acts after the injections are counted in the final class. Ice and
 * Fire absent: nothing applies, with no warning.
 */
public final class IafCompat {

    private IafCompat() {
    }

    static final String IAF_MARKER = "com/github/alexthe666/iceandfire/IceAndFire.class";
    static final String LLIB_MARKER = "net/ilexiconn/llibrary/LLibrary.class";

    /** {jar (0 = Ice and Fire, 1 = LLibrary), class, SHA-256}. */
    private static final String[][] POSE_CACHE_CLASSES = {
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/IceAndFireTabulaModelAnimator.class",
                    "ac0d590c6b53aa067c547ac347cd86bf45c8a1605486edf1e06ebb8f6bc1807e" },
            { "0", "com/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel.class",
                    "f82134a8be9a73ebcac5ae1e527ebd3f0336aa137bafddbf202a0cc6285b6bd1" },
            { "1", "net/ilexiconn/llibrary/client/model/ModelAnimator.class",
                    "2f84a5d5dec39e718826c93b4ef0c48b2df6f61c3883a11ef1a8bb5a7d3cc2d4" },
    };

    /** cacheTabulaPoses, part 2 (TabulaCubeCache): the by-name lookups of the three Tabula animators every frame. */
    private static final String[][] LOOKUP_CLASSES = {
            { "0", "com/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel.class",
                    "f82134a8be9a73ebcac5ae1e527ebd3f0336aa137bafddbf202a0cc6285b6bd1" },
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/IceDragonTabulaModelAnimator.class",
                    "220acb20a5d5076f042181e4c0cede2ba208ba4f1e246d36f9a977c8e9602c2e" },
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/FireDragonTabulaModelAnimator.class",
                    "574c84ebab1ef1312f97a3794d252418408ea9061addeea884d480ab4efbb8b4" },
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/SeaSerpentTabulaModelAnimator.class",
                    "c82f8d31bea76b165f037262ff9cf045194eb4f17d56179fdd127aa2c1ba36ee" },
            { "1", "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer.class",
                    "4ab2e558f9b07eeef042e0a793ea601286bf1e3907e9ecb70c07b70741bb9da8" },
    };

    private static final String[][] DISTANT_CLASSES = {
            { "0", "com/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel.class",
                    "f82134a8be9a73ebcac5ae1e527ebd3f0336aa137bafddbf202a0cc6285b6bd1" },
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/IceDragonTabulaModelAnimator.class",
                    "220acb20a5d5076f042181e4c0cede2ba208ba4f1e246d36f9a977c8e9602c2e" },
            { "0", "com/github/alexthe666/iceandfire/client/model/animator/FireDragonTabulaModelAnimator.class",
                    "574c84ebab1ef1312f97a3794d252418408ea9061addeea884d480ab4efbb8b4" },
            { "1", "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer.class",
                    "4ab2e558f9b07eeef042e0a793ea601286bf1e3907e9ecb70c07b70741bb9da8" },
    };

    /** Mixin -> injections it leaves in the final class (handlers nthcompat$&lt;Mixin&gt;$...). */
    static final String POSE_CACHE_MIXIN = "TabulaAnimatorPoseCacheMixin";
    static final String DISTANT_MIXIN = "TabulaModelDistantAnimationMixin";
    static final int POSE_CACHE_INJECTIONS = 1;
    static final int DISTANT_INJECTIONS = 1;
    /**
     * By-name lookups (TabulaCubeCache): 1 Iterator.next + the getCube calls (not the loop's getCubes) of setRotationAngles, genderMob and
     * isWing (ice: 72 + 6 + 4; fire: 60 + 6 + 4) and of setRotationAngles (serpent: 31), in 1.9.1's bytecode.
     */
    static final String ICE_LOOKUP_MIXIN = "IceDragonCubeLookupMixin";
    static final String FIRE_LOOKUP_MIXIN = "FireDragonCubeLookupMixin";
    static final String SERPENT_LOOKUP_MIXIN = "SeaSerpentCubeLookupMixin";
    static final int ICE_LOOKUP_INJECTIONS = 83;
    static final int FIRE_LOOKUP_INJECTIONS = 71;
    static final int SERPENT_LOOKUP_INJECTIONS = 32;

    private static boolean evaluated;
    private static boolean present;
    private static String problem;
    static boolean poseCacheAllowed;
    static boolean lookupAllowed;
    static boolean distantAllowed;
    /** Allowed and with the injections checked: the handlers only act with this. */
    public static volatile boolean poseCacheOk;
    public static volatile boolean distantOk;
    public static volatile boolean iceLookupOk;
    public static volatile boolean fireLookupOk;
    public static volatile boolean serpentLookupOk;
    private static final Map<String, String> audit = new LinkedHashMap<>();

    /** validationMode: runs the original animation alongside and compares (TabulaPoseCache). */
    static final boolean VALIDATE = gambiarra.common.ValidationLog.on();

    public static boolean validating() {
        return VALIDATE;
    }

    /** Dev: -Dnthcompat.iceandfire.lookupCache=false turns off only part 2 of cacheTabulaPoses (to measure part 1 alone). */
    private static final boolean LOOKUP_OFF = DevHooks.ON && "false".equals(System.getProperty("nthcompat.iceandfire.lookupCache"));

    static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        boolean wantPose = CompatConfig.iceAndFireTabulaPoseCache;
        // The same hook (TabulaModelDistantAnimationMixin) serves the distant animation and LOD level 2.
        boolean wantDistant = CompatConfig.iceAndFireDistantDragonAnimation || CompatConfig.lodAnimationBySize;
        File iaf = JarUtil.jarOf(IAF_MARKER);
        File llib = JarUtil.jarOf(LLIB_MARKER);
        present = iaf != null;
        if (!present) {
            return;
        }
        if (!wantPose && !wantDistant) {
            CompatStatus.LOG.info("[gambiarra] Ice and Fire: fixes disabled in the config");
            return;
        }
        try {
            String iafVersion = JarUtil.modVersion(IAF_MARKER, "iceandfire");
            String llibVersion = llib == null ? null : JarUtil.modVersion(LLIB_MARKER, "llibrary");
            if (!"1.9.1".equals(iafVersion) || !"1.7.20-1.12.2".equals(llibVersion)) {
                problem = "Ice and Fire " + iafVersion + " and LLibrary " + llibVersion + " (checked: 1.9.1 and 1.7.20-1.12.2)";
            } else {
                File[] jars = { iaf, llib };
                String posePrint = wantPose ? mismatch(jars, POSE_CACHE_CLASSES) : null;
                String lookupPrint = wantPose && !LOOKUP_OFF ? mismatch(jars, LOOKUP_CLASSES) : null;
                String distantPrint = wantDistant ? mismatch(jars, DISTANT_CLASSES) : null;
                poseCacheAllowed = wantPose && posePrint == null;
                lookupAllowed = wantPose && !LOOKUP_OFF && lookupPrint == null;
                distantAllowed = wantDistant && distantPrint == null;
                String print = posePrint != null ? posePrint : lookupPrint != null ? lookupPrint : distantPrint;
                if (print != null) {
                    problem = "class differs from the checked one: " + print;
                }
            }
        } catch (Throwable t) {
            problem = "failed checking the versions: " + t;
            poseCacheAllowed = false;
            lookupAllowed = false;
            distantAllowed = false;
        }
        CompatStatus.LOG.info("[gambiarra] Ice and Fire: cacheTabulaPoses {}, distantDragonAnimation {}{}",
                wantPose ? (poseCacheAllowed && lookupAllowed ? "enabled" : poseCacheAllowed || lookupAllowed ? "PARTIALLY" : "OFF")
                        : "disabled in the config",
                (CompatConfig.iceAndFireDistantDragonAnimation ? (distantAllowed ? "enabled" : "OFF") : "disabled in the config")
                        + (CompatConfig.lodAnimationBySize ? (distantAllowed ? "; LOD level 2 on Tabula models enabled" : "; LOD level 2 on Tabula models DISABLED") : ""),
                problem != null ? " (" + problem + ")" : "");
        if (problem != null) {
            boolean byLock = !problem.startsWith("failed");
            if (wantPose && !(poseCacheAllowed && lookupAllowed)) {
                gambiarra.common.StartupReport.off("iceandfire.cacheTabulaPoses", problem, byLock);
            }
            if (CompatConfig.iceAndFireDistantDragonAnimation && !distantAllowed) {
                gambiarra.common.StartupReport.off("iceandfire.distantDragonAnimation", problem, byLock);
            }
        }
    }

    private static String mismatch(File[] jars, String[][] classes) throws java.io.IOException {
        for (String[] c : classes) {
            File jar = jars[Integer.parseInt(c[0])];
            byte[] bytes = jar == null ? null : JarUtil.readEntry(jar, c[1]);
            if (bytes == null || !c[2].equals(JarUtil.sha256Hex(bytes))) {
                return c[1];
            }
        }
        return null;
    }

    /** HandlerCallAudit: a mixin's injection count in the final class. */
    static synchronized void record(String target, String mixin, int found, int expected) {
        audit.put(mixin, found + "/" + expected + " in " + target);
        boolean ok = found == expected;
        if (POSE_CACHE_MIXIN.equals(mixin)) {
            poseCacheOk = ok && poseCacheAllowed;
        } else if (DISTANT_MIXIN.equals(mixin)) {
            distantOk = ok && distantAllowed;
        } else if (ICE_LOOKUP_MIXIN.equals(mixin)) {
            iceLookupOk = ok && lookupAllowed;
        } else if (FIRE_LOOKUP_MIXIN.equals(mixin)) {
            fireLookupOk = ok && lookupAllowed;
        } else if (SERPENT_LOOKUP_MIXIN.equals(mixin)) {
            serpentLookupOk = ok && lookupAllowed;
        }
        if (!ok) {
            CompatStatus.LOG.warn("[gambiarra] Ice and Fire: {} has {} of {} injections in {}; the fix does not act", mixin, found, expected, target);
            gambiarra.common.StartupReport.failed("iceandfire (" + mixin + ")", found + " of " + expected + " injections in " + target);
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        m.put("problem", problem);
        m.put("cacheTabulaPoses", poseCacheOk ? "active" : poseCacheAllowed ? "allowed, injection not checked" : "disabled");
        m.put("cacheTabulaPoses.lookups", !lookupAllowed ? "disabled"
                : "ice " + (iceLookupOk ? "active" : "not checked") + ", fire " + (fireLookupOk ? "active" : "not checked")
                        + ", serpent " + (serpentLookupOk ? "active" : "not checked"));
        m.put("distantDragonAnimation", !CompatConfig.iceAndFireDistantDragonAnimation ? "disabled"
                : distantOk ? "active" : distantAllowed ? "allowed, injection not checked" : "disabled");
        m.put("lodLevel2Tabula", !CompatConfig.lodAnimationBySize ? "disabled"
                : distantOk ? "active" : distantAllowed ? "allowed, injection not checked" : "disabled");
        m.put("injections", new LinkedHashMap<>(audit));
        // Only with the mod present: these classes use Ice and Fire and LLibrary types.
        if (poseCacheOk) {
            m.put("poses", TabulaPoseCache.describe());
        }
        if (iceLookupOk || fireLookupOk || serpentLookupOk) {
            m.put("lookups", TabulaCubeCache.describe());
        }
        if (distantOk) {
            m.put("distantDragons", DistantDragonAnimation.describe());
        }
        return m;
    }

}
