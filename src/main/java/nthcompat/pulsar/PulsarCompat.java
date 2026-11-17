// SPDX-License-Identifier: MIT
package nthcompat.pulsar;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.JarUtil;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * Pulsar fix in nthcompat (mixins.nthcompat.pulsar.json, PulsarMixinPlugin): the smooth lighting crash (fixSmoothLightCrash, see
 * SmoothLightScratch).
 *
 * Only applies with the option on and the code it depends on equal to the checked one (Pulsar 0.6.1 and 0.6.2; see CLASSES); the version
 * only goes to the log. And it only acts after the injection is counted in the final class. Pulsar absent: nothing applies, with no
 * warning.
 */
public final class PulsarCompat {

    private PulsarCompat() {
    }

    static final String MARKER = "com/sumirelabs/pulsar/Pulsar.class";
    /** The versions checked in the harness (only for the log). */
    static final java.util.List<String> VERSIONS = java.util.Arrays.asList("0.6.1", "0.6.2", "0.6.4-beta");

    /**
     * The hiddenSamples class (where the MutableBlockPos is used only during the call): byte for byte equal to 0.6.1/0.6.2's, or with a
     * hiddenSamples of the same fingerprint (gambiarra.lock.CodeLock). Pulsar's mixin that passes the null field is not part of the lock:
     * if a new Pulsar fixes it, the scratch stops arriving null and the fix does not act (it does not get in the way).
     */
    private static final String[][] CLASSES = {
            { "com/sumirelabs/pulsar/light/SmoothLightOcclusion.class",
                    "adefdc7e6482bbcf73bcb13a2e4c278e1deb5d28eada169efc3eb62101e1aa04",
                    "m:hiddenSamples=37dd79274ddb613ae27f236ad58bc46c470eb21254c3d171df0482ea40fd7e78" },
    };

    static final String SCRATCH_MIXIN = "SmoothLightOcclusionScratchMixin";
    /** A @ModifyVariable at the start of hiddenSamples. */
    static final int SCRATCH_INJECTIONS = 1;

    private static boolean evaluated;
    private static boolean present;
    private static String problem;
    static boolean scratchAllowed;
    /** Allowed and with the injection checked: the handler only acts with this. */
    public static volatile boolean scratchOk;
    private static final Map<String, String> audit = new LinkedHashMap<>();

    static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        File jar = JarUtil.jarOf(MARKER);
        present = jar != null;
        if (!present) {
            return;
        }
        if (!CompatConfig.pulsarFixSmoothLightCrash) {
            CompatStatus.LOG.info("[gambiarra] Pulsar: fixSmoothLightCrash disabled in the config");
            return;
        }
        String version = null;
        try {
            version = JarUtil.modVersion(MARKER, "pulsar");
            for (String[] c : CLASSES) {
                byte[] bytes = JarUtil.readEntry(jar, c[0]);
                if (bytes == null || !accepted(bytes, c)) {
                    problem = "code differs from the checked one: " + c[0];
                    break;
                }
            }
            scratchAllowed = problem == null;
        } catch (Throwable t) {
            problem = "failed checking the code: " + t;
            scratchAllowed = false;
        }
        // The version only goes to the log: what decides is the code (since Gambiarra 0.3.1).
        CompatStatus.LOG.info("[gambiarra] Pulsar {}: fixSmoothLightCrash {}{}", version, scratchAllowed ? "enabled" : "OFF",
                problem != null ? " (" + problem + ")" : VERSIONS.contains(version) ? "" : " (version not checked; the code is the same)");
        if (!scratchAllowed && problem != null) {
            gambiarra.common.StartupReport.off("pulsar.fixSmoothLightCrash", problem, !problem.startsWith("failed"));
        }
    }

    /** The SHA-256 of the class or the fingerprint of the methods ("m:method=fingerprint"), as in nthrc.WorldgenStatus. */
    private static boolean accepted(byte[] bytes, String[] c) {
        String sha = JarUtil.sha256Hex(bytes);
        for (int i = 1; i < c.length; i++) {
            if (c[i].startsWith("m:")) {
                int eq = c[i].indexOf('=');
                if (c[i].substring(eq + 1).equals(gambiarra.lock.CodeLock.methodPrint(bytes, c[i].substring(2, eq)))) {
                    return true;
                }
            } else if (sha.equals(c[i])) {
                return true;
            }
        }
        return false;
    }

    /** HandlerCallAudit: the mixin's injection count in the final class. */
    static synchronized void record(String target, String mixin, int found, int expected) {
        audit.put(mixin, found + "/" + expected + " in " + target);
        boolean ok = found == expected;
        if (SCRATCH_MIXIN.equals(mixin)) {
            scratchOk = ok && scratchAllowed;
        }
        if (!ok) {
            CompatStatus.LOG.warn("[gambiarra] Pulsar: {} has {} of {} injections in {}; the fix does not act", mixin, found, expected, target);
            gambiarra.common.StartupReport.failed("pulsar.fixSmoothLightCrash", found + " of " + expected + " injections in " + target);
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        m.put("problem", problem);
        m.put("fixSmoothLightCrash", scratchOk ? "active" : scratchAllowed ? "allowed, injection not checked" : "disabled");
        m.put("injections", new LinkedHashMap<>(audit));
        if (scratchOk) {
            m.put("facesWithoutPulsarMutableBlockPos", SmoothLightScratch.substituted());
        }
        return m;
    }

}
