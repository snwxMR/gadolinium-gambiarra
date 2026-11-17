// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

/**
 * GPU culling state stored in each Nothirium section (AbstractRenderChunk), by AbstractRenderChunkGpuMixin.
 * Only read and written on the render thread.
 */
public interface GpuSection {

    /** Stable id of the section in the current renderer (index of the Hi-Z history); -1 = no id yet. */
    int nthgpu$id();

    void nthgpu$setId(int id);

    /** Position of the section in the pass's candidate list (0 = main, 1 = shadow), if the generation matches. */
    int nthgpu$slotIndex(int slot);

    int nthgpu$slotGeneration(int slot);

    void nthgpu$setSlot(int slot, int generation, int index);

    /** In the deferred build list (dirty section outside the frustum). */
    boolean nthgpu$deferred();

    void nthgpu$setDeferred(boolean deferred);

    /**
     * Build requested and no result yet (compileAsync clears "dirty" right away): the section goes into the candidate list even
     * when empty, so the geometry shows as soon as it arrives, without waiting for another traversal.
     */
    boolean nthgpu$compiling();

    void nthgpu$setCompiling(boolean compiling);

    /**
     * Generation of the pass's last traversal (0 = main, 1 = shadow) that visited the section, empty or not. gpuCullingEntities:
     * an entity in the air sits in an empty section, which does not go into the candidate list.
     * gpuCullingSkipNoOpDirtyRebuilds: a section the last traversal did not even visit does not ask for another when marked dirty.
     */
    int nthgpu$visited(int slot);

    void nthgpu$setVisited(int slot, int generation);

}
