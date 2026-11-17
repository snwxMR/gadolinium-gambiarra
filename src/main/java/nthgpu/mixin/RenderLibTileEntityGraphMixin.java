// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.tileentity.TileEntity;
import nthshaders.gpu.EntityGraphCulling;

/**
 * gpuCullingEntities (see nthshaders.gpu.EntityGraphCulling): RenderLib 1.4.5's tile entity (chests etc.) shouldRender also
 * asks whether any section touched by the box was reached by the visibility traversal. Only after the original says "yes".
 */
@Mixin(targets = "meldexun.renderlib.renderer.tileentity.TileEntityRenderer", remap = false)
public abstract class RenderLibTileEntityGraphMixin {

    @WrapMethod(method = "shouldRender")
    private boolean nthgpu$RenderLibTileEntityGraphMixin$shouldRender(TileEntity tileEntity, ICamera camera, float partialTicks, double camX,
            double camY, double camZ, Operation<Boolean> original) {
        return original.call(tileEntity, camera, partialTicks, camX, camY, camZ) && EntityGraphCulling.tileEntityVisible(tileEntity);
    }

}
