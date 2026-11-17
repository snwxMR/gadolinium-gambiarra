// SPDX-License-Identifier: MIT
package nthrc.mixin.addstructures;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.world.World;
import nthrc.Hook;
import nthrc.addstructures.GroundScan;

/**
 * Optimization G (see GroundScan): the starting height of getGroundFromAbove (the only call to World.getActualHeight in the method) becomes the top of
 * the chunk's sections with blocks.
 */
@Mixin(targets = "xxrexraptorxx.additionalstructures.world.StructureGenerator", remap = false, priority = 1500)
public abstract class GroundScanMixin {

    @WrapOperation(method = "getGroundFromAbove", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_72940_L()I"))
    private static int nthrc$GroundScanMixin$top(World world, Operation<Integer> original, @Local(argsOnly = true, ordinal = 0) int x,
            @Local(argsOnly = true, ordinal = 1) int z) {
        int top = original.call(world);
        return Hook.ADDITIONAL_STRUCTURES.ok ? GroundScan.start(world, x, z, top) : top;
    }

}
