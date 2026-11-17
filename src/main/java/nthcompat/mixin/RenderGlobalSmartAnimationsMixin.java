// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.RenderGlobal;
import nthcompat.optirefine.SmartAnimationsFix;

/**
 * Smart Animations (see SmartAnimationsFix): calls SmartAnimations.update() at the start of loadRenderers, as OptiFine does and
 * OptiRefine does not. At the start: the chunks rebuilt right after already note the animated textures. If OptiRefine starts calling
 * it (the plugin checks the bytecode), this call does not happen.
 */
@Mixin(value = RenderGlobal.class, priority = 1500)
public abstract class RenderGlobalSmartAnimationsMixin {

    @WrapMethod(method = "loadRenderers")
    private void nthcompat$RenderGlobalSmartAnimationsMixin$loadRenderers(Operation<Void> original) {
        SmartAnimationsFix.beforeLoadRenderers();
        original.call();
    }

}
