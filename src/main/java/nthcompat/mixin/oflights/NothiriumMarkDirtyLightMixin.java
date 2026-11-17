// SPDX-License-Identifier: MIT
package nthcompat.mixin.oflights;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import nthcompat.oflights.OfLightsCompat;

/**
 * optifineDynamicLightsReachedOnly (item a of docs/item2-luz-dinamica.md): during the update of a dynamic light source
 * (OfDynamicLightMixin), the markDirty of a section the light does not reach, at either the old or the new position, or with no block
 * at all, is skipped. Otherwise nothing changes. Wraps the whole method, with what other mixins inject into it.
 */
@Mixin(targets = "meldexun.nothirium.renderer.chunk.AbstractRenderChunk", remap = false, priority = 1500)
public abstract class NothiriumMarkDirtyLightMixin {

    @WrapMethod(method = "markDirty")
    private void nthcompat$NothiriumMarkDirtyLightMixin$markDirty(Operation<Void> original) {
        if (OfLightsCompat.skipLightMark((AbstractRenderChunk) (Object) this)) {
            return;
        }
        original.call();
    }

}
