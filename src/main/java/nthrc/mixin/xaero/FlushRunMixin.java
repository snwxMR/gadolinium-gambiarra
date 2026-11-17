// SPDX-License-Identifier: MIT
package nthrc.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.gen.ChunkProviderServer;
import nthrc.xaero.FlushOffThread;

/**
 * flushChunksOffServerThread: the Runnable Xaero's World Map's WorldDataReader sends to the server thread saves the changed chunks (stays) and calls
 * ChunkProviderServer.flushToDisk (func_104112_b), which writes and compresses right there the whole queue of chunks to save. Here the flush leaves; the queue stays for the game's disk
 * thread, and Xaero's thread waits for it (FlushWaitMixin) before reading the files.
 */
@Mixin(targets = "xaero.map.file.worldsave.WorldDataReader$3", remap = false)
public abstract class FlushRunMixin {

    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/ChunkProviderServer;func_104112_b()V"))
    private void nthrc$FlushRunMixin$leaveToDiskThread(ChunkProviderServer provider, Operation<Void> original) {
        if (FlushOffThread.active()) {
            FlushOffThread.skipped();
            return;
        }
        original.call(provider);
    }

}
