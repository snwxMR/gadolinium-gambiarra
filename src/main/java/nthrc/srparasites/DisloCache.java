// SPDX-License-Identifier: MIT
package nthrc.srparasites;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * cacheDisloValues (Scape and Run Parasites): counters and the side-by-side validation of DisloCacheMixin. Only the server thread calls getDisloValues.
 */
public final class DisloCache {

    /** Test (development build only): on each hit, runs the original too and compares. */
    public static final boolean VALIDATE = WorldgenConfig.validate;

    private static long hits;
    private static long misses;
    private static long validated;
    private static long mismatches;

    private DisloCache() {
    }

    public static void miss() {
        misses++;
    }

    public static void hit() {
        hits++;
    }

    /** Validation: the stored one (cached) against what the original gave now (fresh). */
    public static void check(int dim, int[] cached, int[] fresh) {
        validated++;
        ValidationLog.checked("srparasites.cacheDisloValues");
        if (!Arrays.equals(cached, fresh)) {
            mismatches++;
            ValidationLog.diff("srparasites.cacheDisloValues", "dimension " + dim + " stored=" + Arrays.toString(cached) + " original=" + Arrays.toString(fresh));
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION srparasites.cacheDisloValues: dimension {} stored={} original={}", dim,
                        Arrays.toString(cached), Arrays.toString(fresh));
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", hits);
        m.put("computed", misses);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
