// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import nthshaders.gpu.EntityGraphCulling;

/**
 * gpuCullingEntities (see nthshaders.gpu.EntityGraphCulling): RenderLib 1.4.5's entity shouldRender also asks whether any
 * section touched by the entity's box was reached by the visibility traversal. Only after the original says "yes".
 */
@Mixin(targets = "meldexun.renderlib.renderer.entity.EntityRenderer", remap = false)
public abstract class RenderLibEntityGraphMixin {

    @WrapMethod(method = "shouldRender")
    private boolean nthgpu$RenderLibEntityGraphMixin$shouldRender(Entity entity, ICamera camera, double camX, double camY, double camZ,
            double renderDistance, Operation<Boolean> original) {
        return original.call(entity, camera, camX, camY, camZ, renderDistance) && EntityGraphCulling.entityVisible(entity, camX, camY, camZ);
    }

}
