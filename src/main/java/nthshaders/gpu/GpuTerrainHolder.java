// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

/** The GL43 renderer with GPU culling (ChunkRendererGL43GpuMixin). */
public interface GpuTerrainHolder {

    /** The pass in progress is being drawn by the GPU (its setup was done in GPU culling). */
    boolean nthgpu$drawsThisPass();

}
