// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import nthshaders.gpu.FaceLayout;
import nthshaders.gpu.FaceLayoutHolder;

/** Per-direction face culling: the mesh layout stored in its VBO part (see nthshaders.gpu.FaceCulling). */
@Mixin(targets = "meldexun.nothirium.opengl.DynamicVBO$VBOPart", remap = false)
public abstract class VBOPartGpuMixin implements FaceLayoutHolder {

    @Unique
    private FaceLayout nthgpu$faceLayout;

    @Override
    public FaceLayout nthgpu$faceLayout() {
        return this.nthgpu$faceLayout;
    }

    @Override
    public void nthgpu$setFaceLayout(FaceLayout layout) {
        this.nthgpu$faceLayout = layout;
    }

}
