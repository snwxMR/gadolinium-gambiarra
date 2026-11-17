// SPDX-License-Identifier: MIT
package nthrc.rc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.world.gen.feature.StructureGenerator;
import ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Optimization F1 (Recurrent Complex): refuse by height limits or overlap BEFORE preparing.
 *
 * StructureGenerator.generateInternal prepares the structure (instanceData() -&gt; prepareInstanceData: ruin transformers, natural air, mazes...) and ONLY THEN, for natural generation (SUGGEST maturity),
 * checks the box's height limits and whether it overlaps structures already generated. The two checks do not depend on the preparation, and the box is already placed (and kept by RC) when the preparation
 * starts. StatueTemple, for instance, spent ~3 s preparing only to be refused by overlap right after.
 *
 * Here the same check runs before the preparation and, if it refuses, returns the same failure RC would return (the same Failure objects). The world does not change: the structure would not be generated
 * anyway; what stops happening is the preparation (and the neighbouring chunks it could load).
 */
public final class EarlyReject {

    /** Test (development build only): prepares anyway and checks that the failure was the same. */
    public static final boolean VALIDATE = validateRequested();

    private static long checked;
    private static long rejected;
    private static long validated;
    private static long mismatches;
    /** Validation: the original also failed, but for another reason (the same world). */
    private static long otherReason;
    private static long validatedNanos;
    private static final Map<String, long[]> byStructure = new TreeMap<>();

    private EarlyReject() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /** The same check as generateInternal, in the same order; null = the structure carries on. */
    @Nullable
    public static StructureGenerator.GenerationResult.Failure verdict(StructureBoundingBox bb, WorldServer world, boolean allowOverlaps) {
        checked++;
        if (bb.minY < 1 || bb.maxY > world.getHeight() - 1 - 1) {
            return StructureGenerator.GenerationResult.Failure.outOfBounds;
        }
        if (RCConfig.avoidOverlappingGeneration && !allowOverlaps
                && !WorldStructureGenerationData.get(world).entriesAt(bb).noneMatch(WorldStructureGenerationData.Entry::blocking)) {
            return StructureGenerator.GenerationResult.Failure.structureOverlap;
        }
        return null;
    }

    public static synchronized void rejected(String structure) {
        rejected++;
        byStructure.computeIfAbsent(String.valueOf(structure), k -> new long[2])[0]++;
    }

    /**
     * Validation: the attempt ran whole. Different (the world would change) only when the original GENERATED the structure: all Recurrent Complex callers only look at GenerationResult.succeeded(). A failure for
     * another reason (for instance, the preparation errored, like a maze that blew mazeTimeout, before reaching the overlap check) gives the same world: it is counted apart.
     */
    public static synchronized void validated(String structure, StructureGenerator.GenerationResult.Failure verdict,
            StructureGenerator.GenerationResult result, long nanos) {
        rejected++;
        validated++;
        ValidationLog.checked("recurrentcomplex.rejectBeforePreparation");
        validatedNanos += nanos;
        long[] s = byStructure.computeIfAbsent(String.valueOf(structure), k -> new long[2]);
        s[0]++;
        s[1] += nanos;
        if (result.succeeded()) {
            mismatches++;
            ValidationLog.diff("recurrentcomplex.rejectBeforePreparation", structure + " would refuse for '" + verdict.description + "', but the original generated the structure");
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION recurrentcomplex.rejectBeforePreparation: {} would refuse for '{}', but the original "
                        + "generated the structure", structure, verdict.description);
            }
            return;
        }
        String reason = result instanceof StructureGenerator.GenerationResult.Failure
                ? ((StructureGenerator.GenerationResult.Failure) result).description : String.valueOf(result);
        if (!verdict.description.equals(reason)) {
            otherReason++;
            WorldgenStatus.LOG.info("[gambiarra] Validation recurrentcomplex.rejectBeforePreparation: {} would refuse for '{}'; the original also "
                    + "failed, for '{}' (same world)", structure, verdict.description, reason);
        }
    }

    public static synchronized Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checked", checked);
        m.put("refusedEarly", rejected);
        Map<String, String> by = new TreeMap<>();
        byStructure.forEach((k, v) -> by.put(k, v[0] + "x" + (VALIDATE ? ", " + Math.round(v[1] / 1e6) + " ms of preparation" : "")));
        m.put("perStructure", by);
        if (VALIDATE) {
            m.put("validated", validated);
            m.put("differences", mismatches);
            m.put("failedForAnotherReason", otherReason);
            m.put("preparationThatWouldBeSavedMs", Math.round(validatedNanos / 1e6));
        }
        return m;
    }

}
