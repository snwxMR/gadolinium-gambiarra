// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import org.spongepowered.asm.mixin.Mixin;

import com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.entity.Entity;
import nthcompat.iceandfire.DistantDragonAnimation;
import nthcompat.iceandfire.IafCompat;

/**
 * distantDragonAnimation (see nthcompat.iceandfire.DistantDragonAnimation): the setRotationAngles of Ice and Fire's Tabula model,
 * which computes the dragon's pose, only runs every N frames for a distant dragon.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel", remap = false)
public abstract class TabulaModelDistantAnimationMixin {

    @WrapMethod(method = "func_78087_a")
    private void nthcompat$TabulaModelDistantAnimationMixin$setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks,
            float rotationYaw, float rotationPitch, float scale, Entity entity, Operation<Void> original) {
        IceAndFireTabulaModel self = (IceAndFireTabulaModel) (Object) this;
        if (IafCompat.distantOk && DistantDragonAnimation.reuse(self, entity)) {
            return;
        }
        original.call(limbSwing, limbSwingAmount, ageInTicks, rotationYaw, rotationPitch, scale, entity);
        if (IafCompat.distantOk) {
            DistantDragonAnimation.afterCompute(self, entity);
        }
    }

}
