// SPDX-License-Identifier: MIT
package gambiarra.mixin.lod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gambiarra.lod.Lod;
import net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;

/**
 * LOD level 1 on LLibrary 1.7.20's Tabula parts (Ice and Fire): AdvancedModelRenderer replaces the whole render of ModelRenderer (the vanilla hook does not see it). The part's own scale enters the math.
 */
@Mixin(targets = "net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer", remap = false)
public abstract class AdvancedModelRendererLodMixin {

    @Inject(method = "func_78785_a", at = @At("HEAD"), cancellable = true)
    private void gambiarra$AdvancedModelRendererLodMixin$render(float scale, CallbackInfo ci) {
        AdvancedModelRenderer self = (AdvancedModelRenderer) (Object) this;
        float own = Math.max(Math.abs(self.scaleX), Math.max(Math.abs(self.scaleY), Math.abs(self.scaleZ)));
        if (Lod.skipLlibraryPart(self, scale, own)) {
            ci.cancel();
        }
    }

}
