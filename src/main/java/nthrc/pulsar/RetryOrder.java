// SPDX-License-Identifier: MIT
package nthrc.pulsar;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sumirelabs.pulsar.light.WorldLightManager;

import gambiarra.common.ValidationLog;
import net.minecraft.world.chunk.Chunk;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * retryNeighboursFirst (see RetryOrderMixin). Only the server thread calls the resend test (InitialChunkSendRetries' drain, once per tick); the counters do not need a lock.
 */
public final class RetryOrder {

    /** Test (development build only): on each cut, also runs the whole original test and checks that it gave false. */
    private static final boolean VALIDATE = validateRequested();

    private static long tests, cut, validated, mismatches;

    private RetryOrder() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /**
     * true = the original test would give false because of the neighbours (the chunk keeps waiting). false = the original decides (the chunk was not populated, or the neighbours are ready and the pending
     * light query is missing).
     */
    public static boolean waitsForNeighbours(WorldLightManager manager, Chunk chunk) {
        tests++;
        if (!chunk.isPopulated() || manager.areNeighboursLightReady(chunk.x, chunk.z)) {
            return false;
        }
        cut++;
        if (VALIDATE) {
            validated++;
            boolean original = chunk.isPopulated() && manager.canSendUpdatedChunkLight(chunk.x, chunk.z)
                    && manager.areNeighboursLightReady(chunk.x, chunk.z);
            ValidationLog.check("pulsar.retryNeighboursFirst", !original, "chunk (" + chunk.x + ", " + chunk.z + ") would be sent this tick by the original test");
            if (original) {
                // Only in a race with the light threads (a neighbour became ready between the two tests).
                mismatches++;
                if (mismatches <= 10) {
                    WorldgenStatus.LOG.error("[gambiarra] VALIDATION pulsar.retryNeighboursFirst: chunk ({}, {}) would be sent this tick by the original "
                            + "test", chunk.x, chunk.z);
                }
            }
            return false;
        }
        return true;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tests", tests);
        m.put("expensiveQueriesSkipped", cut);
        m.put("skippedPct", tests > 0 ? Math.round(1000.0 * cut / tests) / 10.0 : 0);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
