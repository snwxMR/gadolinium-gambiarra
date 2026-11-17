// SPDX-License-Identifier: MIT
package nthcompat.pulsar;

import java.util.concurrent.atomic.LongAdder;

import net.minecraft.util.math.BlockPos;

/**
 * fixSmoothLightCrash: Pulsar 0.6.1 excludes from smooth lighting the light of hidden corners (stairs, slabs), in
 * SmoothLightOcclusion.hiddenSamples, with a scratch MutableBlockPos its mixin keeps in each AmbientOcclusionFace (pulsar$corner). The
 * field is only created in the constructor the face already had in the game; OptiRefine adds another constructor, with no arguments (the
 * one OptiFine's RenderEnv uses in every chunk build), and with that one the field stays null: the game crashes at the first face with
 * different corner lights (NullPointerException, "scratch" is null).
 *
 * The fix hands out a MutableBlockPos of the thread itself when Pulsar's arrives null. The scratch only lives during the call
 * (hiddenSamples does not keep it), so one per thread is enough, and several build threads (Nothirium's, the game's) do not mix. With
 * Pulsar's field present, nothing changes.
 */
public final class SmoothLightScratch {

    private SmoothLightScratch() {
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final LongAdder SUBSTITUTED = new LongAdder();

    public static BlockPos.MutableBlockPos orThreadScratch(BlockPos.MutableBlockPos scratch) {
        if (scratch != null) {
            return scratch;
        }
        SUBSTITUTED.increment();
        return SCRATCH.get();
    }

    static long substituted() {
        return SUBSTITUTED.sum();
    }

}
