// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthshaders.compat.CompilePriority;
import nthshaders.compat.CompileQueue;

/**
 * chunkCompileOrder (see nthshaders.compat.CompilePriority): the builds of the dispatcher's runAsync(Supplier) go through its
 * queue (CompileQueue) before the pool; on each setup (update) the camera cone is updated and the queue moves; on dispose,
 * everything waiting goes to the pool before it closes.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.RenderChunkDispatcher", remap = false, priority = 1500)
public abstract class NothiriumCompilePriorityMixin {

    @Unique
    private volatile CompileQueue nthcompat$queue;

    @WrapOperation(method = "runAsync(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<?> nthcompat$NothiriumCompilePriorityMixin$submit(Supplier<?> supplier, Executor executor,
            Operation<CompletableFuture<?>> original) {
        CompileQueue queue = this.nthcompat$queue;
        if (queue == null) {
            queue = new CompileQueue(executor);
            this.nthcompat$queue = queue;
            CompilePriority.attach(queue);
        }
        return original.call(supplier, CompilePriority.executorFor(queue, supplier, executor));
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void nthcompat$NothiriumCompilePriorityMixin$update(CallbackInfo ci) {
        CompilePriority.frame(this.nthcompat$queue);
    }

    @Inject(method = "dispose", at = @At("HEAD"))
    private void nthcompat$NothiriumCompilePriorityMixin$dispose(CallbackInfo ci) {
        CompileQueue queue = this.nthcompat$queue;
        if (queue != null) {
            queue.flush();
        }
    }

}
