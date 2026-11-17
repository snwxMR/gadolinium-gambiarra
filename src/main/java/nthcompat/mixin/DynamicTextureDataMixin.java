// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.texture.DynamicTexture;
import nthcompat.optirefine.DynamicTextureDataFix;

/**
 * Option fixOptiRefineDynamicTextureData (see optirefine.DynamicTextureDataFix): each DynamicTexture is made with the array OptiRefine
 * 78ff8ce would swap in when shaders turn on (width x height x 3), so the array whoever kept it writes into (the lightmap's, the maps')
 * stays the one the texture uploads. Only applied with 78ff8ce's MixinDynamicTexture.
 */
@Mixin(DynamicTexture.class)
public abstract class DynamicTextureDataMixin {

    @Shadow
    @Final
    @Mutable
    private int[] dynamicTextureData;

    @Inject(method = "<init>(II)V", at = @At("RETURN"))
    private void nthcompat$keepDataArray(int textureWidth, int textureHeight, CallbackInfo ci) {
        int[] data = this.dynamicTextureData;
        long size = (long) textureWidth * textureHeight * 3L;
        if (data != null && data.length < size && size <= Integer.MAX_VALUE - 8) {
            this.dynamicTextureData = Arrays.copyOf(data, (int) size);
            DynamicTextureDataFix.expanded(textureWidth, textureHeight, (int) size);
        }
    }

}
