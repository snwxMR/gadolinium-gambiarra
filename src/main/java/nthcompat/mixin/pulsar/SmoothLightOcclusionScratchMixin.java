// SPDX-License-Identifier: MIT
package nthcompat.mixin.pulsar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.util.math.BlockPos;
import nthcompat.pulsar.PulsarCompat;
import nthcompat.pulsar.SmoothLightScratch;

/**
 * fixSmoothLightCrash (see nthcompat.pulsar.SmoothLightScratch): the scratch MutableBlockPos of Pulsar's
 * SmoothLightOcclusion.hiddenSamples never arrives null.
 */
@Mixin(targets = "com.sumirelabs.pulsar.light.SmoothLightOcclusion", remap = false)
public abstract class SmoothLightOcclusionScratchMixin {

    @ModifyVariable(method = "hiddenSamples", at = @At("HEAD"), argsOnly = true)
    private static BlockPos.MutableBlockPos nthcompat$SmoothLightOcclusionScratchMixin$scratch(BlockPos.MutableBlockPos scratch) {
        return PulsarCompat.scratchOk ? SmoothLightScratch.orThreadScratch(scratch) : scratch;
    }

}
