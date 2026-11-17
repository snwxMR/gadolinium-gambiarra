// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.Minecraft;
import nthshaders.compat.ShadersCompat;


/**
 * Smart Animations (see SmartAnimationsFix): the shadow pass race. SmartAnimations.isActive() = active &amp;&amp;
 * !Shaders.isShadowPass, and isShadowPass belongs to the render thread. BufferBuilder asks this when it starts building each
 * section, on another thread; with the shadow pass running, the section does not note its animated textures and the animation
 * stops on it. Off the render thread, only "active" counts here. On the render thread, nothing changes.
 */
@Mixin(targets = "net.optifine.SmartAnimations", remap = false, priority = 1500)
public abstract class SmartAnimationsActiveMixin {

    @Shadow
    private static boolean active;

    @WrapMethod(method = "isActive")
    private static boolean nthcompat$SmartAnimationsActiveMixin$isActive(Operation<Boolean> original) {
        boolean result = original.call();
        if (!result && active) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && !mc.isCallingFromMinecraftThread()) {
                ShadersCompat.raceCorrected();
                return true;
            }
        }
        return result;
    }

}
