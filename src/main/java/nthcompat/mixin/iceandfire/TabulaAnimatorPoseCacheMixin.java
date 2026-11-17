// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;

import com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.model.ModelRenderer;
import nthcompat.iceandfire.IafCompat;
import nthcompat.iceandfire.TabulaPoseCache;

/**
 * cacheTabulaPoses (see nthcompat.iceandfire.TabulaPoseCache): Ice and Fire's moveToPose only runs for the animation that is playing. Applies to all Tabula animators
 * (fire dragon, ice dragon and sea serpent), which inherit this method.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.client.model.animator.IceAndFireTabulaModelAnimator", remap = false)
public abstract class TabulaAnimatorPoseCacheMixin {

    @WrapMethod(method = "moveToPose")
    private void nthcompat$TabulaAnimatorPoseCacheMixin$moveToPose(IceAndFireTabulaModel model, IceAndFireTabulaModel modelTo,
            Operation<Void> original) {
        if (!IafCompat.poseCacheOk) {
            original.call(model, modelTo);
            return;
        }
        ModelAnimatorAccessor animator = (ModelAnimatorAccessor) (Object) model.llibAnimator;
        if (animator.nthcompat$correctAnimation()) {
            TabulaPoseCache.noteActive();
            original.call(model, modelTo);
            return;
        }
        // Another animation: LLibrary's rotate/move would do nothing; the rest is math with no effect.
        TabulaPoseCache.noteSkipped();
        if (IafCompat.validating()) {
            // Dev: the skipped call really changes nothing.
            Map<ModelRenderer, float[]> before = TabulaPoseCache.snapshot(animator.nthcompat$transformMap());
            original.call(model, modelTo);
            TabulaPoseCache.compare(before, TabulaPoseCache.snapshot(animator.nthcompat$transformMap()));
        }
    }

}
