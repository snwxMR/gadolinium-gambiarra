// SPDX-License-Identifier: MIT
package nthrc.mixin.wenchants;

import java.util.Collection;
import java.util.HashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraftforge.fml.common.gameevent.TickEvent;
import nthrc.Hook;
import nthrc.wenchants.PhoenixDiveFix;

/**
 * Phoenix Dive crash (see PhoenixDiveFix): the only call to Collection.remove of onUpdate (the particleTimers.values().remove(id) of the second loop) stops touching the map during
 * the scan, and the noted entries leave by key at the end of the method.
 */
@Mixin(targets = "com.wonderfulenchantments.enchantments.PhoenixDiveEnchantment", remap = false, priority = 1500)
public abstract class PhoenixDiveMixin {

    @Shadow
    protected static HashMap<Integer, Integer> particleTimers;

    @WrapOperation(method = "onUpdate", at = @At(value = "INVOKE", target = "Ljava/util/Collection;remove(Ljava/lang/Object;)Z"))
    private static boolean nthrc$PhoenixDiveMixin$remove(Collection<?> values, Object id, Operation<Boolean> original) {
        return Hook.PHOENIX_DIVE.ok ? PhoenixDiveFix.deferRemoval(id) : original.call(values, id);
    }

    @Inject(method = "onUpdate", at = @At("RETURN"))
    private static void nthrc$PhoenixDiveMixin$flush(TickEvent.WorldTickEvent event, CallbackInfo ci) {
        if (Hook.PHOENIX_DIVE.ok) {
            PhoenixDiveFix.flush(particleTimers);
        }
    }

}
