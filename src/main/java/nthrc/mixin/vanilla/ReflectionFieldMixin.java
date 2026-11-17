// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import java.lang.reflect.Field;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import nthrc.Hook;
import nthrc.minecraft.FieldHandles;

/**
 * reflectionFieldHandles: the most used accessors of meldexun.reflectionutil.ReflectionField (the same class in RenderLib and in RLTweaker, with the same code) go through a
 * MethodHandle (FieldHandles). Anything out of the ordinary falls to the original path.
 */
@Mixin(targets = "meldexun.reflectionutil.ReflectionField", remap = false)
public abstract class ReflectionFieldMixin {

    @Shadow
    @Final
    private Field field;

    @Unique
    private FieldHandles nthrc$handles;

    @Unique
    private FieldHandles nthrc$handles() {
        FieldHandles h = nthrc$handles;
        if (h == null) {
            h = FieldHandles.of(field);
            nthrc$handles = h;
        }
        return h;
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$set(Object instance, Object value, CallbackInfo ci) {
        if (Hook.REFLECTION_FIELD.active() && nthrc$handles().set(instance, value)) {
            ci.cancel();
        }
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$get(Object instance, CallbackInfoReturnable<Object> cir) {
        if (Hook.REFLECTION_FIELD.active()) {
            Object v = nthrc$handles().get(instance);
            if (v != FieldHandles.NOT_DONE) {
                cir.setReturnValue(v);
            }
        }
    }

    @Inject(method = "getBoolean", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$getBoolean(Object instance, CallbackInfoReturnable<Boolean> cir) {
        if (Hook.REFLECTION_FIELD.active() && nthrc$handles().hasBoolean()) {
            try {
                cir.setReturnValue(nthrc$handles().getBoolean(instance));
            } catch (Throwable t) {
                // The original path runs and throws whatever it has to throw.
            }
        }
    }

    @Inject(method = "setBoolean", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$setBoolean(Object instance, boolean value, CallbackInfo ci) {
        if (Hook.REFLECTION_FIELD.active() && nthrc$handles().setBoolean(instance, value)) {
            ci.cancel();
        }
    }

    @Inject(method = "getInt", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$getInt(Object instance, CallbackInfoReturnable<Integer> cir) {
        if (Hook.REFLECTION_FIELD.active() && nthrc$handles().hasInt()) {
            try {
                cir.setReturnValue(nthrc$handles().getInt(instance));
            } catch (Throwable t) {
                // The original path runs and throws whatever it has to throw.
            }
        }
    }

    @Inject(method = "setInt", at = @At("HEAD"), cancellable = true)
    private void nthrc$ReflectionFieldMixin$setInt(Object instance, int value, CallbackInfo ci) {
        if (Hook.REFLECTION_FIELD.active() && nthrc$handles().setInt(instance, value)) {
            ci.cancel();
        }
    }

}
