// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import nthcompat.optirefine.AnimatedSpriteState;
import nthcompat.optirefine.SmartAnimationsFix;

/**
 * Smart Animations, "sprite" form (see SmartAnimationsFix): whoever asks for the coordinates of an animated texture on the render
 * thread (a TESR drawing a fluid in a tank, a particle) is going to draw it; it counts as drawn. The cheap test comes first (the
 * animation index stored in the sprite; -1/-2 for non-animated ones): measured in Phase 2, the hook costs ~0 per call.
 */
@Mixin(value = TextureAtlasSprite.class, priority = 1500)
public abstract class TextureAtlasSpriteGetterMarkingMixin {

    @Inject(method = { "getMinU", "getMaxU", "getMinV", "getMaxV", "getInterpolatedU", "getInterpolatedV" }, at = @At("HEAD"))
    private void nthcompat$TextureAtlasSpriteGetterMarkingMixin$coordinates(CallbackInfoReturnable<Float> cir) {
        // capable() is only true if all mixins of the package applied, including the one that gives the sprite the AnimatedSpriteState.
        if (SmartAnimationsFix.capable()) {
            int idx = ((AnimatedSpriteState) this).nthcompat$animIndex();
            if (idx >= 0) {
                SmartAnimationsFix.markFromGetter(idx);
            }
        }
    }

}
