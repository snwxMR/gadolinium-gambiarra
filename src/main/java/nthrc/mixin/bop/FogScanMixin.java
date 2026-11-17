// SPDX-License-Identifier: MIT
package nthrc.mixin.bop;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import nthrc.Hook;
import nthrc.bop.FogScanCache;

/**
 * Biomes O' Plenty fog (see FogScanCache): the result of onRenderFog (the two values passed to renderFog) stays stored until the next tick for the same
 * world, block and render distance. On a hit, calls the same renderFog with the same values, without querying the 41x41 biomes.
 */
@Mixin(targets = "biomesoplenty.common.handler.FogEventHandler", remap = false, priority = 1500)
public abstract class FogScanMixin {

    @Shadow
    private static void renderFog(int fogMode, float farPlaneDistance, float farPlaneDistanceScale) {
        throw new AssertionError();
    }

    @WrapMethod(method = "onRenderFog")
    private void nthrc$FogScanMixin$onRenderFog(EntityViewRenderEvent.RenderFogEvent event, Operation<Void> original) {
        if (!Hook.BOP_FOG.ok) {
            original.call(event);
            return;
        }
        Entity entity = event.getEntity();
        float farPlane = event.getFarPlaneDistance();
        int slot = FogScanCache.find(entity.world, entity.posX, entity.posY, entity.posZ, farPlane);
        if (slot >= 0) {
            if (FogScanCache.VALIDATE) {
                FogScanCache.beginCapture();
                original.call(event);
                FogScanCache.endValidation(slot);
                return;
            }
            FogScanCache.hit();
            renderFog(event.getFogMode(), FogScanCache.far(slot), FogScanCache.start(slot));
            return;
        }
        FogScanCache.beginCapture();
        try {
            original.call(event);
        } finally {
            FogScanCache.endCapture(farPlane);
        }
    }

    @WrapOperation(method = "onRenderFog", at = @At(value = "INVOKE", target = "Lbiomesoplenty/common/handler/FogEventHandler;renderFog(IFF)V"))
    private void nthrc$FogScanMixin$renderFog(int fogMode, float farPlaneDistance, float start, Operation<Void> original) {
        FogScanCache.capture(farPlaneDistance, start);
        original.call(fogMode, farPlaneDistance, start);
    }

}
