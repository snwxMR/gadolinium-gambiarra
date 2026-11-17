// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import nthcompat.optirefine.SmartAnimationsFix;
import nthcompat.optirefine.SmartAnimationsGate;

/**
 * Smart Animations, "sprite" form (see SmartAnimationsFix): notes the sprite of each quad of renderModelBrightnessColorQuads (blocks
 * in minecarts and in the enderman's hand, models mods draw in a TESR), as OptiFine does and OptiRefine does not. If OptiRefine starts
 * noting them (the plugin checks the bytecode), it does not note them again.
 */
@Mixin(value = BlockModelRenderer.class, priority = 1500)
public abstract class BlockModelRendererSpriteMarkingMixin {

    @WrapMethod(method = "renderModelBrightnessColorQuads")
    private void nthcompat$BlockModelRendererSpriteMarkingMixin$mark(float brightness, float red, float green, float blue, List<BakedQuad> quads, Operation<Void> original) {
        if (!SmartAnimationsGate.optiRefineMarksBrightnessQuads()) {
            SmartAnimationsFix.markQuads(quads);
        }
        original.call(brightness, red, green, blue, quads);
    }

}
