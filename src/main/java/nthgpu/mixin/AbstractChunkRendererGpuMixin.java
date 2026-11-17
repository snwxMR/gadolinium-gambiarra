// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import meldexun.nothirium.api.renderer.chunk.IRenderChunkDispatcher;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import meldexun.renderlib.util.Frustum;
import nthshaders.gpu.GpuCulling;

/**
 * GPU culling (see nthshaders.gpu.GpuCulling): Nothirium's visibility traversal (AbstractChunkRenderer.setup) in "capture"
 * mode, when GpuTerrain calls it to build the candidate list. Outside capture, everything behaves as before.
 *
 * During capture:
 *  - each visited section (addToRenderLists) goes into the list;
 *  - the frustum test of the neighbours answers "inside" (the GPU does the frustum test, every frame);
 *  - the fog gets some slack (the list stays valid while the camera moves inside the section);
 *  - the build (compileAsync) only happens if the section really is in the frustum; otherwise it is deferred until it
 *    enters the frustum, as in Nothirium.
 */
@Mixin(targets = "meldexun.nothirium.renderer.chunk.AbstractChunkRenderer", remap = false, priority = 1500)
public abstract class AbstractChunkRendererGpuMixin {

    @Inject(method = "addToRenderLists", at = @At("HEAD"))
    private void nthgpu$AbstractChunkRendererGpuMixin$visited(AbstractRenderChunk renderChunk, CallbackInfo ci) {
        if (GpuCulling.capturing()) {
            GpuCulling.visited(renderChunk);
        }
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE",
            target = "Lmeldexun/nothirium/renderer/chunk/AbstractRenderChunk;compileAsync(Lmeldexun/nothirium/api/renderer/chunk/IChunkRenderer;Lmeldexun/nothirium/api/renderer/chunk/IRenderChunkDispatcher;)V"))
    private void nthgpu$AbstractChunkRendererGpuMixin$compile(AbstractRenderChunk renderChunk, IChunkRenderer<?> renderer,
            IRenderChunkDispatcher dispatcher, Operation<Void> original) {
        if (!GpuCulling.capturing() || GpuCulling.compileNow(renderChunk)) {
            original.call(renderChunk, renderer, dispatcher);
        }
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE",
            target = "Lmeldexun/nothirium/renderer/chunk/AbstractRenderChunk;isFrustumCulled(Lmeldexun/renderlib/util/Frustum;)Z"))
    private boolean nthgpu$AbstractChunkRendererGpuMixin$frustum(AbstractRenderChunk renderChunk, Frustum frustum, Operation<Boolean> original) {
        if (GpuCulling.capturing()) {
            return false;
        }
        return original.call(renderChunk, frustum);
    }

    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lmeldexun/nothirium/renderer/chunk/AbstractRenderChunk;isFogCulled(DDDD)Z"))
    private boolean nthgpu$AbstractChunkRendererGpuMixin$fog(AbstractRenderChunk renderChunk, double cameraX, double cameraY, double cameraZ,
            double fogEndSqr, Operation<Boolean> original) {
        return original.call(renderChunk, cameraX, cameraY, cameraZ, GpuCulling.capturing() ? GpuCulling.paddedFogSqr() : fogEndSqr);
    }

}
