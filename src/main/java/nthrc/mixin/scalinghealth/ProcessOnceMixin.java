// SPDX-License-Identifier: MIT
package nthrc.mixin.scalinghealth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.EntityLivingBase;
import nthrc.Hook;
import nthrc.scalinghealth.ProcessOnce;

/**
 * processMobsOnce (Scaling Health 1.3.42): DifficultyHandler.isProcessed only counted the mob as processed with the "scalinghealth:difficulty" mark different from 0, and
 * the mark is the difficulty used (0 with difficulty below 1). Here, processed = the mark exists; it is written at the end of every pass through process (see ProcessOnce).
 */
@Mixin(targets = "net.silentchaos512.scalinghealth.event.DifficultyHandler", remap = false)
public abstract class ProcessOnceMixin {

    @Inject(method = "isProcessed", at = @At("HEAD"), cancellable = true)
    private static void nthrc$ProcessOnceMixin$isProcessed(EntityLivingBase entity, CallbackInfoReturnable<Boolean> cir) {
        if (Hook.SH_PROCESS_ONCE.ok) {
            cir.setReturnValue(ProcessOnce.isProcessed(entity));
        }
    }

}
