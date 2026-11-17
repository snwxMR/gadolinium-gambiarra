// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.item.ItemStack;
import nthcompat.optirefine.SmartAnimationsFix;

/**
 * Smart Animations, "sprite" form (see SmartAnimationsFix): notes the sprite of each quad of an item, as OptiFine does in renderQuad.
 * OptiRefine 78ff8ce only notes them with shaders and outside the GUI; without this, animated items in the hotbar, in the inventory
 * and (without shaders) anywhere would stop. Outside OptiRefine's @WrapMethod (higher priority). OptiRefine v0.1.0-alpha already
 * notes them in both branches (no @WrapMethod there any more): noting again what OptiRefine already noted changes nothing.
 */
@Mixin(value = RenderItem.class, priority = 1500)
public abstract class RenderItemSpriteMarkingMixin {

    @WrapMethod(method = "renderQuads")
    private void nthcompat$RenderItemSpriteMarkingMixin$renderQuads(BufferBuilder renderer, List<BakedQuad> quads, int color, ItemStack stack, Operation<Void> original) {
        SmartAnimationsFix.markQuads(quads);
        original.call(renderer, quads, color, stack);
    }

}
