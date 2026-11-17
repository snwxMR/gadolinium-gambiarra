// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.ilexiconn.llibrary.client.model.Transform;
import net.minecraft.client.model.ModelRenderer;

/**
 * cacheTabulaPoses: whether the animation in progress is the keyframe's (rotate/move only act with it) and, in validation, the
 * keyframe's transform map.
 */
@Mixin(targets = "net.ilexiconn.llibrary.client.model.ModelAnimator", remap = false)
public interface ModelAnimatorAccessor {

    @Accessor("correctAnimation")
    boolean nthcompat$correctAnimation();

    @Accessor("transformMap")
    HashMap<ModelRenderer, Transform> nthcompat$transformMap();

}
