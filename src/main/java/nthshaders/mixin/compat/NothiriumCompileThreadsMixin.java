// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import java.util.concurrent.ForkJoinPool;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthshaders.compat.CompileThreads;

/**
 * Number and priority of Nothirium's chunk build threads (see CompileThreads): wraps the new ForkJoinPool in
 * RenderChunkDispatcher's constructor, the only place where Nothirium creates the pool, and only changes the thread count and
 * the thread factory (to set the priority). If another Nothirium does not have that new, nothing is applied (require = 0) and
 * the state in the log/diagnostics says the hook did not run.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.RenderChunkDispatcher", remap = false, priority = 1500)
public abstract class NothiriumCompileThreadsMixin {

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)Ljava/util/concurrent/ForkJoinPool;"))
    private ForkJoinPool nthcompat$NothiriumCompileThreadsMixin$create(int parallelism, ForkJoinPool.ForkJoinWorkerThreadFactory factory,
            Thread.UncaughtExceptionHandler handler, boolean asyncMode, Operation<ForkJoinPool> original) {
        return CompileThreads.create(parallelism, factory, handler, asyncMode, original);
    }

}
