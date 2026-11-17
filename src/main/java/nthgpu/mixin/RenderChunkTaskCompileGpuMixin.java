// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import meldexun.nothirium.api.renderer.chunk.RenderChunkTaskResult;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import nthshaders.gpu.FaceCulling;
import nthshaders.gpu.FaceLayout;

/**
 * Per-direction face culling (see nthshaders.gpu.FaceCulling), while a Nothirium section is built:
 *  - start of compileSection(pack): keeps this thread's BufferBuilder pack;
 *  - end of the lambda that finishes each layer (finishDrawing): the opaque layers are reordered there, still on the
 *    build thread;
 *  - before handing the result to the render thread: the layouts go to the task;
 *  - on upload to the VBO (render thread): the new part receives the layout, before setVBOPart.
 *
 * Only applied with gpuFaceCulling on (GpuMixinPlugin). Each injection is counted (GpuInjections); if one is missing,
 * nothing is reordered.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.RenderChunkTaskCompile", remap = false, priority = 1500)
public abstract class RenderChunkTaskCompileGpuMixin {

    @Unique
    private FaceLayout[] nthgpu$faceLayouts;

    @Inject(method = "compileSection(Lnet/minecraft/client/renderer/RegionRenderCacheBuilder;)Lmeldexun/nothirium/api/renderer/chunk/RenderChunkTaskResult;",
            at = @At("HEAD"))
    private void nthgpu$RenderChunkTaskCompileGpuMixin$begin(RegionRenderCacheBuilder pack, CallbackInfoReturnable<RenderChunkTaskResult> cir) {
        FaceCulling.beginCompile(pack);
    }

    /** The "bufferBuilder -> finishDrawing; null if empty" lambda of compileSection (3 exits). */
    @Inject(method = "lambda$compileSection$0", at = @At("RETURN"))
    private static void nthgpu$RenderChunkTaskCompileGpuMixin$finished(BufferBuilder builder, CallbackInfoReturnable<BufferBuilder> cir) {
        BufferBuilder finished = cir.getReturnValue();
        if (finished != null) {
            FaceCulling.onLayerFinished(finished);
        }
    }

    @Inject(method = "compileSection(Lnet/minecraft/client/renderer/RegionRenderCacheBuilder;)Lmeldexun/nothirium/api/renderer/chunk/RenderChunkTaskResult;",
            at = @At(value = "INVOKE",
                    target = "Lmeldexun/nothirium/api/renderer/chunk/IRenderChunkDispatcher;runOnRenderThread(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"))
    private void nthgpu$RenderChunkTaskCompileGpuMixin$handOff(RegionRenderCacheBuilder pack, CallbackInfoReturnable<RenderChunkTaskResult> cir) {
        this.nthgpu$faceLayouts = FaceCulling.takeLayouts();
    }

    /** The lambda that applies the build on the render thread: setVBOPart(pass, buffer(pass, data)). */
    @WrapOperation(method = "lambda$compileSection$2",
            at = @At(value = "INVOKE",
                    target = "Lmeldexun/nothirium/api/renderer/chunk/IChunkRenderer;buffer(Lmeldexun/nothirium/api/renderer/chunk/ChunkRenderPass;Ljava/nio/ByteBuffer;)Lmeldexun/nothirium/api/renderer/IVBOPart;"))
    private IVBOPart nthgpu$RenderChunkTaskCompileGpuMixin$buffer(IChunkRenderer<?> renderer, ChunkRenderPass pass, ByteBuffer data,
            Operation<IVBOPart> original) {
        IVBOPart part = original.call(renderer, pass, data);
        return FaceCulling.attach(part, this.nthgpu$faceLayouts, pass);
    }

}
