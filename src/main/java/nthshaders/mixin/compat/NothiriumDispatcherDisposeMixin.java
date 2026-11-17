// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import nthshaders.compat.CompileBufferLimit;

/**
 * During RenderChunkDispatcher's dispose the main thread waits for the compile threads to finish and only then uploads what
 * is ready: if a compile thread waited for a buffer set to return, dispose would hang. Here the limit is suspended while
 * dispose runs.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.RenderChunkDispatcher", remap = false, priority = 1500)
public abstract class NothiriumDispatcherDisposeMixin {

    @WrapMethod(method = "dispose")
    private void nthcompat$NothiriumDispatcherDisposeMixin$dispose(Operation<Void> original) {
        CompileBufferLimit.setDisposing(true);
        try {
            original.call();
        } finally {
            CompileBufferLimit.setDisposing(false);
        }
    }

}
