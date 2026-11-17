// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.api.renderer.chunk.IRenderChunkProvider;
import meldexun.nothirium.mc.renderer.chunk.ChunkRendererDynamicVbo;
import meldexun.nothirium.mc.renderer.chunk.RenderChunk;
import meldexun.renderlib.util.Frustum;
import meldexun.renderlib.util.GLShader;
import nthshaders.gpu.GpuCulling;
import nthshaders.gpu.GpuTerrain;
import nthshaders.gpu.GpuTerrainHolder;
import nthshaders.render.ShaderModeRenderer;

/**
 * GPU culling on Nothirium's GL43 renderer (see nthshaders.gpu.GpuCulling): GpuTerrain is created in init and replaces
 * setup (the traversal only runs when something changes; culling and commands come from the GPU) and the drawing of each
 * layer (glMultiDrawArraysIndirectCount). Without GPU culling (disabled, unsupported card, failure), everything calls the
 * original.
 *
 * Priority 1600: outside nthshaders' ChunkRendererGL43Mixin (1500), which keeps handling shader mode when GPU culling is
 * not in use.
 *
 * Extends the target's superclass to see the inherited fields (vbos, chunks) and to call its setup (the traversal).
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL43", remap = false, priority = 1600)
public abstract class ChunkRendererGL43GpuMixin extends ChunkRendererDynamicVbo implements GpuTerrainHolder {

    @Shadow
    @Final
    private GLShader shader;

    @Unique
    private GpuTerrain nthgpu$terrain;

    @Inject(method = "init", at = @At("TAIL"))
    private void nthgpu$ChunkRendererGL43GpuMixin$init(int renderDistance, CallbackInfo ci) {
        if (this.nthgpu$terrain != null) {
            this.nthgpu$terrain.dispose();
            this.nthgpu$terrain = null;
        }
        try {
            if (!GpuCulling.usableForNewRenderer()) {
                return;
            }
            Object self = this;
            boolean shaderMode = self instanceof ShaderModeRenderer && ((ShaderModeRenderer) self).nthshaders$isShaderMode();
            this.nthgpu$terrain = new GpuTerrain(renderDistance, shaderMode, this, this.shader, this.vbos, this.chunks);
        } catch (Throwable t) {
            GpuCulling.failInit(t);
            this.nthgpu$terrain = null;
        }
    }

    @Inject(method = "initVAOs", at = @At("TAIL"))
    private void nthgpu$ChunkRendererGL43GpuMixin$initVAOs(ChunkRenderPass pass, CallbackInfo ci) {
        GpuTerrain terrain = this.nthgpu$terrain;
        if (terrain != null) {
            try {
                terrain.rebuildVaos(pass);
            } catch (Throwable t) {
                GpuCulling.failInit(t);
            }
        }
    }

    @Inject(method = "dispose", at = @At("TAIL"))
    private void nthgpu$ChunkRendererGL43GpuMixin$dispose(CallbackInfo ci) {
        GpuTerrain terrain = this.nthgpu$terrain;
        this.nthgpu$terrain = null;
        if (terrain != null) {
            terrain.dispose();
        }
    }

    @WrapMethod(method = "setup")
    private void nthgpu$ChunkRendererGL43GpuMixin$setup(IRenderChunkProvider<RenderChunk> provider, double cameraX, double cameraY,
            double cameraZ, Frustum frustum, int frame, Operation<Void> original) {
        GpuTerrain terrain = this.nthgpu$terrain;
        if (terrain != null && terrain.setup(cameraX, cameraY, cameraZ, frustum, this.isSpectator(),
                () -> this.nthgpu$traverse(provider, cameraX, cameraY, cameraZ, frustum))) {
            return;
        }
        original.call(provider, cameraX, cameraY, cameraZ, frustum, frame);
    }

    /** Just Nothirium's visibility traversal (AbstractChunkRenderer.setup), with its own frame number. */
    @Unique
    private void nthgpu$traverse(IRenderChunkProvider<RenderChunk> provider, double cameraX, double cameraY, double cameraZ, Frustum frustum) {
        super.setup(provider, cameraX, cameraY, cameraZ, frustum, GpuCulling.nextBfsFrame());
    }

    @WrapMethod(method = "renderChunks")
    private void nthgpu$ChunkRendererGL43GpuMixin$renderChunks(ChunkRenderPass pass, Operation<Void> original) {
        nthshaders.render.ShaderOffProbe.atTerrain(this, pass);
        GpuTerrain terrain = this.nthgpu$terrain;
        if (terrain != null && terrain.render(pass, () -> original.call(pass))) {
            return;
        }
        original.call(pass);
    }

    @Override
    public boolean nthgpu$drawsThisPass() {
        GpuTerrain terrain = this.nthgpu$terrain;
        return terrain != null && terrain.drawsThisPass();
    }

}
