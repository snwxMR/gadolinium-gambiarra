// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import nthcompat.optirefine.AnimatedSpriteState;
import nthcompat.optirefine.SmartAnimationsFix;

/**
 * Smart Animations (see SmartAnimationsFix), in the upload of each animated texture every tick:
 *  - the alwaysAnimate list counts as drawn;
 *  - when OptiRefine skips an upload (texture not drawn), it becomes "behind"; when it is drawn again, the next tick uploads its current
 *    frame, without waiting for the animation's next frame change. The animation time never stops: OptiRefine counts the ticks even
 *    without uploading.
 * Applies to the atlas texture and to the shaders' _n/_s maps (same method, each object with its state). Outside OptiRefine's
 * @WrapMethod (higher priority): its upload happens inside the original.
 */
@Mixin(value = TextureAtlasSprite.class, priority = 1500)
public abstract class TextureAtlasSpriteSmartAnimationsMixin implements AnimatedSpriteState {

    @Shadow
    protected List<int[][]> framesTextureData;
    @Shadow
    private AnimationMetadataSection animationMetadata;
    @Shadow
    protected int frameCounter;
    @Shadow
    protected int width;
    @Shadow
    protected int height;
    @Shadow
    protected int originX;
    @Shadow
    protected int originY;

    @Unique
    private int nthcompat$animIndex = AnimatedSpriteState.UNKNOWN;
    @Unique
    private boolean nthcompat$always;
    @Unique
    private boolean nthcompat$stale;

    @Override
    public int nthcompat$animIndex() {
        return this.nthcompat$animIndex;
    }

    @Override
    public void nthcompat$setAnimIndex(int index) {
        this.nthcompat$animIndex = index;
    }

    @Override
    public boolean nthcompat$alwaysAnimate() {
        return this.nthcompat$always;
    }

    @Override
    public void nthcompat$setAlwaysAnimate(boolean always) {
        this.nthcompat$always = always;
    }

    @WrapMethod(method = "updateAnimation")
    private void nthcompat$TextureAtlasSpriteSmartAnimationsMixin$updateAnimation(Operation<Void> original) {
        AnimationMetadataSection meta = this.animationMetadata;
        if (meta == null) {
            original.call();
            return;
        }
        TextureAtlasSprite self = (TextureAtlasSprite) (Object) this;
        SmartAnimationsFix.beforeUpdate(self);
        int before = this.frameCounter;
        original.call();
        boolean changed = this.frameCounter != before;
        if (!SmartAnimationsFix.animationActive(self)) {
            // No upload this tick: if the image should have changed (frame change or interpolation), the GPU's fell behind.
            if (changed || meta.isInterpolate()) {
                this.nthcompat$stale = true;
            }
            return;
        }
        if (!this.nthcompat$stale) {
            return;
        }
        this.nthcompat$stale = false;
        // The original already uploaded: interpolated (uploads every tick) or switching to another frame.
        if (meta.isInterpolate() || changed && meta.getFrameIndex(before) != meta.getFrameIndex(this.frameCounter)) {
            return;
        }
        int frame = meta.getFrameIndex(this.frameCounter);
        if (frame >= 0 && frame < this.framesTextureData.size()) {
            TextureUtil.uploadTextureMipmap(this.framesTextureData.get(frame), this.width, this.height, this.originX, this.originY, false,
                    SmartAnimationsFix.spriteSingle(self));
            SmartAnimationsFix.caughtUp();
        }
    }

}
