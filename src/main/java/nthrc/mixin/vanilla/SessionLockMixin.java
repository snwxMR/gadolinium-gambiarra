// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.storage.SaveHandler;
import nthrc.Hook;
import nthrc.minecraft.SessionLock;

/**
 * sessionLockOncePerSecond: SaveHandler.checkSessionLock (func_75762_c) opens and reads session.lock for every chunk saved. The check that passed holds for 1 second for the
 * same save (SessionLock).
 */
@Mixin(value = SaveHandler.class, remap = false)
public abstract class SessionLockMixin {

    @Unique
    private long nthrc$lastCheck;

    @Inject(method = "func_75762_c", at = @At("HEAD"), cancellable = true)
    private void nthrc$SessionLockMixin$recent(CallbackInfo ci) {
        if (Hook.SESSION_LOCK.active() && SessionLock.skip(nthrc$lastCheck)) {
            ci.cancel();
        }
    }

    @Inject(method = "func_75762_c", at = @At("RETURN"))
    private void nthrc$SessionLockMixin$passed(CallbackInfo ci) {
        nthrc$lastCheck = System.nanoTime();
    }

}
