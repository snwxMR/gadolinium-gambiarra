// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.nothirium;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.api.renderer.chunk.IRenderChunk;
import meldexun.renderlib.util.Frustum;
import nthshaders.render.ShadowArea;

/**
 * GL43 shadow pass (ShadowArea): a section outside the volume OptiFine would draw counts as out of the frustum. Nothirium's
 * setup does not even queue it (or follow the search through it), as with those the camera does not see. Before the original
 * test, which is skipped when the section is already outside the volume.
 */
@Mixin(targets = "meldexun.nothirium.renderer.chunk.AbstractRenderChunk", remap = false, priority = 1500)
public abstract class RenderChunkShadowAreaMixin {

    @WrapMethod(method = "isFrustumCulled")
    private boolean nthshaders$RenderChunkShadowAreaMixin$isFrustumCulled(Frustum frustum, Operation<Boolean> original) {
        IRenderChunk chunk = (IRenderChunk) this;
        if (ShadowArea.excludes(chunk.getSectionX(), chunk.getSectionY(), chunk.getSectionZ())) {
            return true;
        }
        return original.call(frustum);
    }

}
