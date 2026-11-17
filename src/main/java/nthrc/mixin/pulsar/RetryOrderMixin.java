// SPDX-License-Identifier: MIT
package nthrc.mixin.pulsar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sumirelabs.pulsar.light.WorldLightManager;

import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import nthrc.Hook;
import nthrc.pulsar.RetryOrder;

/**
 * retryNeighboursFirst: Pulsar's resend test (the retryInitialChunkSends lambda) is isPopulated &amp;&amp; canSendUpdatedChunkLight &amp;&amp; areNeighboursLightReady. The middle
 * one queries 9 chunks in 5 maps, three with a lock; the last, 4 chunks with no lock. Here, after isPopulated, the neighbour test comes first: if it is false, the
 * result is false without the expensive query. If it is true, the original runs whole.
 */
@Mixin(value = WorldLightManager.class, remap = false)
public abstract class RetryOrderMixin {

    @Inject(method = "lambda$retryInitialChunkSends$1", at = @At("HEAD"), cancellable = true)
    private void nthrc$RetryOrderMixin$neighboursFirst(PlayerChunkMapEntry entry, Chunk chunk, CallbackInfoReturnable<Boolean> cir) {
        if (Hook.PULSAR_RETRY.ok && RetryOrder.waitsForNeighbours((WorldLightManager) (Object) this, chunk)) {
            cir.setReturnValue(false);
        }
    }

}
