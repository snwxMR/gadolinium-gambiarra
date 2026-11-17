// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.nothirium;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import nthshaders.render.RendererSelection;

/**
 * Optifine#createChunkRenderer is where Nothirium falls back to GL15 with shaders ("IS_SHADERS ? GL15 : config"). The wrapper
 * decides first (RendererSelection): with the addon active, it keeps GL43 in shader mode, and recreates the renderer when the
 * vertex format changes (shaders on/off). In the other cases it calls the original.
 */
@Mixin(targets = "meldexun.nothirium.mc.integration.Optifine", remap = false, priority = 1500)
public abstract class OptifineRendererMixin {

    @WrapMethod(method = "createChunkRenderer", require = 0)
    private static IChunkRenderer<?> nthshaders$OptifineRendererMixin$createChunkRenderer(IChunkRenderer<?> oldChunkRenderer,
            Operation<IChunkRenderer<?>> original) {
        return RendererSelection.create(oldChunkRenderer, old -> original.call(old));
    }

}
