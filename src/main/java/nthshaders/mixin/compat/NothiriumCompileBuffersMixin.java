// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import java.util.concurrent.BlockingQueue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthshaders.compat.CompileBufferLimit;

/**
 * Limits Nothirium's compile buffer sets (see CompileBufferLimit): wraps compileSection()'s BUFFER_QUEUE.poll(), the only
 * place where Nothirium takes or creates a set.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.RenderChunkTaskCompile", remap = false, priority = 1500)
public abstract class NothiriumCompileBuffersMixin {

    @WrapOperation(method = "compileSection()Lmeldexun/nothirium/api/renderer/chunk/RenderChunkTaskResult;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/BlockingQueue;poll()Ljava/lang/Object;"))
    private Object nthcompat$NothiriumCompileBuffersMixin$takeBuffer(BlockingQueue<?> queue, Operation<Object> original) {
        return CompileBufferLimit.take(queue, original);
    }

}
