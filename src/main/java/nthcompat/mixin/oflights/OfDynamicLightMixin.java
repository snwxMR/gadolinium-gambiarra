// SPDX-License-Identifier: MIT
package nthcompat.mixin.oflights;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import nthcompat.oflights.OfLightsCompat;

/**
 * The update of an OptiFine dynamic light source (Nothirium's, which replaces OptiFine's and marks 8 sections):
 *  - optifineDynamicLightsBlockStep (item b of docs/item2-luz-dinamica.md): only runs when the source changes block or level;
 *  - optifineDynamicLightsReachedOnly (item a): while it runs, the old and new light positions are kept, and the markDirty of sections
 *    the light does not reach (NothiriumMarkDirtyLightMixin) is skipped.
 * Wraps update with what Nothirium injects into it already there (higher priority): the rest of the logic is its own.
 */
@Mixin(targets = "net.optifine.DynamicLight", remap = false, priority = 1500)
public abstract class OfDynamicLightMixin {

    @Shadow
    private Entity entity;
    @Shadow
    private double offsetY;
    @Shadow
    private double lastPosX;
    @Shadow
    private double lastPosY;
    @Shadow
    private double lastPosZ;
    @Shadow
    private int lastLightLevel;

    @WrapMethod(method = "update")
    private void nthcompat$OfDynamicLightMixin$update(RenderGlobal renderGlobal, Operation<Void> original) {
        if (OfLightsCompat.beforeLightUpdate(this.entity, this.offsetY, this.lastPosX, this.lastPosY, this.lastPosZ, this.lastLightLevel)) {
            return;
        }
        try {
            original.call(renderGlobal);
        } finally {
            OfLightsCompat.afterLightUpdate();
        }
    }

}
