// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import java.util.Iterator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;
import nthcompat.iceandfire.IafCompat;
import nthcompat.iceandfire.TabulaCubeCache;

/**
 * cacheTabulaPoses (see nthcompat.iceandfire.TabulaCubeCache): the fire dragon animator's by-name lookups every frame
 * (setRotationAngles, genderMob and isWing) without looking up. The parts loop's Iterator.next marks the current part.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.client.model.animator.FireDragonTabulaModelAnimator", remap = false)
public abstract class FireDragonCubeLookupMixin {

    @Unique
    private AdvancedModelRenderer nthcompat$current;

    @WrapOperation(method = "setRotationAngles(Lcom/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel;"
            + "Lcom/github/alexthe666/iceandfire/entity/EntityFireDragon;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;"))
    private Object nthcompat$FireDragonCubeLookupMixin$next(Iterator<?> iterator, Operation<Object> original) {
        Object cube = original.call(iterator);
        this.nthcompat$current = cube instanceof AdvancedModelRenderer ? (AdvancedModelRenderer) cube : null;
        return cube;
    }

    @WrapOperation(method = { "setRotationAngles(Lcom/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel;"
            + "Lcom/github/alexthe666/iceandfire/entity/EntityFireDragon;FFFFFF)V", "genderMob", "isWing" },
            at = @At(value = "INVOKE", target = "Lcom/github/alexthe666/iceandfire/client/model/util/IceAndFireTabulaModel;"
                    + "getCube(Ljava/lang/String;)Lnet/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer;"))
    private AdvancedModelRenderer nthcompat$FireDragonCubeLookupMixin$getCube(IceAndFireTabulaModel model, String name,
            Operation<AdvancedModelRenderer> original) {
        return TabulaCubeCache.get(IafCompat.fireLookupOk, this.nthcompat$current, model, name, original);
    }

}
