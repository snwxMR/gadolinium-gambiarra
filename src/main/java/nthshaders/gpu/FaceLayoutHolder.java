// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

/**
 * The face direction layout stored in each Nothirium VBO part (DynamicVBO.VBOPart, through VBOPartGpuMixin).
 * Written and read on the render thread.
 */
public interface FaceLayoutHolder {

    FaceLayout nthgpu$faceLayout();

    void nthgpu$setFaceLayout(FaceLayout layout);

}
