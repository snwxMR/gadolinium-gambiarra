// SPDX-License-Identifier: MIT
package nthrc.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.WorldServer;
import nthrc.xaero.FlushOffThread;

/**
 * flushChunksOffServerThread: at the end of WorldDataReader.saveUnsavedChunks (Xaero's thread, after waiting for the server thread's Runnable), waits for the game's disk queue to finish,
 * as the flushToDisk that left the Runnable guaranteed.
 */
@Mixin(targets = "xaero.map.file.worldsave.WorldDataReader", remap = false)
public abstract class FlushWaitMixin {

    @Inject(method = "saveUnsavedChunks", at = @At("TAIL"))
    private void nthrc$FlushWaitMixin$waitForDisk(WorldServer world, boolean debugConfig, CallbackInfo ci) throws InterruptedException {
        if (FlushOffThread.active()) {
            FlushOffThread.waitForDisk();
        }
    }

}
