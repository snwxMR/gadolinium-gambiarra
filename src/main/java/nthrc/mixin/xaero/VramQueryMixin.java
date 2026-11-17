// SPDX-License-Identifier: MIT
package nthrc.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import nthrc.Hook;

/**
 * vramQueryFourPerSecond: MapLimiter.updateAvailableVRAM does a glGetIntegerv every frame. The number only serves for the map to decide how many textures to keep, and a read up to 250 ms
 * old is just as good. The first call always passes (it finds out the type of GPU).
 */
@Mixin(targets = "xaero.map.MapLimiter", remap = false)
public abstract class VramQueryMixin {

    @Unique
    private static final long nthrc$MIN_NANOS = 250_000_000L;

    @Unique
    private long nthrc$lastQuery;

    @Inject(method = "updateAvailableVRAM", at = @At("HEAD"), cancellable = true)
    private void nthrc$VramQueryMixin$recent(CallbackInfo ci) {
        if (!Hook.XAERO_VRAM.ok) {
            return;
        }
        long now = System.nanoTime();
        if (nthrc$lastQuery != 0L && now - nthrc$lastQuery < nthrc$MIN_NANOS) {
            ci.cancel();
        } else {
            nthrc$lastQuery = now;
        }
    }

}
