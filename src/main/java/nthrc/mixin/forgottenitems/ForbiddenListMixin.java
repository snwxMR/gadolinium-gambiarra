// SPDX-License-Identifier: MIT
package nthrc.mixin.forgottenitems;

import java.util.ArrayList;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthrc.Hook;
import nthrc.forgottenitems.ForbiddenListOnePass;

/**
 * forbiddenListOnePass (Forgotten Items 1.3.1.4): the two Item.REGISTRY.getKeys().toArray() of the FIHelper.getForbiddenList loop return the same array
 * during the build (see ForbiddenListOnePass); at the end, it is forgotten.
 */
@Mixin(targets = "tschipp.forgottenitems.util.FIHelper", remap = false)
public abstract class ForbiddenListMixin {

    @WrapOperation(method = "getForbiddenList", at = @At(value = "INVOKE", target = "Ljava/util/Set;toArray()[Ljava/lang/Object;"))
    private static Object[] nthrc$ForbiddenListMixin$toArray(Set<?> keys, Operation<Object[]> original) {
        return Hook.FI_ONE_PASS.ok ? ForbiddenListOnePass.toArray(keys, original) : original.call(keys);
    }

    @Inject(method = "getForbiddenList", at = @At("RETURN"))
    private static void nthrc$ForbiddenListMixin$done(CallbackInfoReturnable<ArrayList<String>> cir) {
        ForbiddenListOnePass.forget();
    }

}
