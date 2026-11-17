// SPDX-License-Identifier: MIT
package gambiarra.mixin.lod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gambiarra.lod.Lod;
import net.minecraft.client.model.ModelRenderer;

/**
 * LOD level 1 (gambiarra.lod.Lod): a part smaller than partPixels on screen is not drawn, with its children. At the start of render, before any GL state change.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelRendererLodMixin {

    @Inject(method = { "render", "renderWithRotation" }, at = @At("HEAD"), cancellable = true)
    private void gambiarra$ModelRendererLodMixin$render(float scale, CallbackInfo ci) {
        if (Lod.skipVanillaPart((ModelRenderer) (Object) this, scale)) {
            ci.cancel();
        }
    }

}
