// SPDX-License-Identifier: LGPL-3.0-only
package nthgpu.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthshaders.gpu.GpuTerrainHolder;

/**
 * GPU culling (see nthshaders.gpu.GpuCulling): Nothirium's render of each layer only draws if the layer's list is not
 * empty. With GPU culling that list comes from the last traversal, and a section that gained the layer after it is already
 * on the GPU: the layer is always drawn (with 0 commands, it costs nothing).
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.MinecraftChunkRenderer", remap = false, priority = 1500)
public abstract class MinecraftChunkRendererGpuMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean nthgpu$MinecraftChunkRendererGpuMixin$isEmpty(List<?> list, Operation<Boolean> original) {
        Object self = this;
        if (self instanceof GpuTerrainHolder && ((GpuTerrainHolder) self).nthgpu$drawsThisPass()) {
            return false;
        }
        return original.call(list);
    }

}
