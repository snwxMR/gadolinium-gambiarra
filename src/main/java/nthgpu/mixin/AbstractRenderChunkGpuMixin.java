// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import meldexun.nothirium.api.renderer.chunk.IRenderChunkDispatcher;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import meldexun.nothirium.util.VisibilitySet;
import nthshaders.gpu.GpuCulling;
import nthshaders.gpu.GpuSection;

/**
 * GPU culling (see nthshaders.gpu.GpuCulling): the state of each Nothirium section (GpuSection) and the events that
 * change the candidate list:
 *  - new VBO part (a build finished, the section was unloaded): updates its position in the GPU buffer;
 *  - new visibility that opens a path (setVisibility, or an empty section that becomes "all visible"): the traversal runs
 *    again within 25 ms;
 *  - dirty section (a block changed): joins the deferred builds and is built when it is in the frustum.
 */
@Mixin(targets = "meldexun.nothirium.renderer.chunk.AbstractRenderChunk", remap = false, priority = 1500)
public abstract class AbstractRenderChunkGpuMixin implements GpuSection {

    @Unique
    private int nthgpu$id = -1;
    @Unique
    private int nthgpu$generation0 = -1;
    @Unique
    private int nthgpu$generation1 = -1;
    @Unique
    private int nthgpu$index0;
    @Unique
    private int nthgpu$index1;
    @Unique
    private boolean nthgpu$deferred;
    @Unique
    private boolean nthgpu$compiling;
    @Unique
    private int nthgpu$visited0 = -1;
    @Unique
    private int nthgpu$visited1 = -1;

    @Shadow
    public abstract VisibilitySet getVisibility();

    @Override
    public int nthgpu$id() {
        return this.nthgpu$id;
    }

    @Override
    public void nthgpu$setId(int id) {
        this.nthgpu$id = id;
    }

    @Override
    public int nthgpu$slotIndex(int slot) {
        return slot == 0 ? this.nthgpu$index0 : this.nthgpu$index1;
    }

    @Override
    public int nthgpu$slotGeneration(int slot) {
        return slot == 0 ? this.nthgpu$generation0 : this.nthgpu$generation1;
    }

    @Override
    public void nthgpu$setSlot(int slot, int generation, int index) {
        if (slot == 0) {
            this.nthgpu$generation0 = generation;
            this.nthgpu$index0 = index;
        } else {
            this.nthgpu$generation1 = generation;
            this.nthgpu$index1 = index;
        }
    }

    @Override
    public boolean nthgpu$deferred() {
        return this.nthgpu$deferred;
    }

    @Override
    public void nthgpu$setDeferred(boolean deferred) {
        this.nthgpu$deferred = deferred;
    }

    @Override
    public boolean nthgpu$compiling() {
        return this.nthgpu$compiling;
    }

    @Override
    public void nthgpu$setCompiling(boolean compiling) {
        this.nthgpu$compiling = compiling;
    }

    @Override
    public int nthgpu$visited(int slot) {
        return slot == 0 ? this.nthgpu$visited0 : this.nthgpu$visited1;
    }

    @Override
    public void nthgpu$setVisited(int slot, int generation) {
        if (slot == 0) {
            this.nthgpu$visited0 = generation;
        } else {
            this.nthgpu$visited1 = generation;
        }
    }

    @Inject(method = "setVBOPart", at = @At("TAIL"))
    private void nthgpu$AbstractRenderChunkGpuMixin$part(ChunkRenderPass pass, IVBOPart part, CallbackInfo ci) {
        GpuCulling.onPartChanged((AbstractRenderChunk) (Object) this, pass, part);
    }

    @Inject(method = "setVisibility", at = @At("HEAD"))
    private void nthgpu$AbstractRenderChunkGpuMixin$visibility(VisibilitySet next, CallbackInfo ci) {
        GpuCulling.onVisibilityChanged((AbstractRenderChunk) (Object) this, this.getVisibility(), next);
    }

    @Inject(method = "markDirty", at = @At("TAIL"))
    private void nthgpu$AbstractRenderChunkGpuMixin$dirty(CallbackInfo ci) {
        GpuCulling.onMarkDirty((AbstractRenderChunk) (Object) this);
    }

    /** compileAsync of a section with nothing to build: it becomes "all visible" (it may open a path). */
    @Inject(method = "compileAsync", at = @At(value = "INVOKE", target = "Lmeldexun/nothirium/util/VisibilitySet;setAllVisible()V"))
    private void nthgpu$AbstractRenderChunkGpuMixin$allVisible(IChunkRenderer<?> renderer, IRenderChunkDispatcher dispatcher, CallbackInfo ci) {
        if (!this.getVisibility().allVisible()) {
            GpuCulling.onVisibilityOpened((AbstractRenderChunk) (Object) this);
        }
    }

}
