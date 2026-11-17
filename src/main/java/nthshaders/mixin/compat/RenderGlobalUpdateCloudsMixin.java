// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.compat;

import java.util.Iterator;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.util.math.BlockPos;
import nthshaders.compat.ShadersCompat;

/**
 * Avoids the crash in OptiRefine's updateClouds with Nothirium.
 *
 * OptiRefine reimplements updateClouds (@WrapMethod, without calling the original) and, when there are pending light updates,
 * calls renderDispatcher.hasNoFreeRenderBuilders(). Nothirium never creates the renderDispatcher, and the @Redirect it used to
 * cover that call is in the original body, which no longer runs: NullPointerException.
 *
 * Here the pending light updates are processed BEFORE OptiRefine's wrapper, exactly as vanilla code would (markBlocksForUpdate
 * of +-1 block, which Nothirium redirects to its provider). OptiRefine finds the queue empty and does not touch the
 * renderDispatcher.
 *
 * It must be a @WrapMethod with a higher priority than OptiRefine's: MixinExtras applies @WrapMethod after the other injectors,
 * so an @Inject would land inside the original body, which OptiRefine never calls.
 */
@Mixin(value = RenderGlobal.class, priority = 1500)
public abstract class RenderGlobalUpdateCloudsMixin {

    @Shadow
    private ChunkRenderDispatcher renderDispatcher;

    @Shadow
    @Final
    private Set<BlockPos> setLightUpdates;

    @Shadow
    protected abstract void markBlocksForUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean updateImmediately);

    @WrapMethod(method = "updateClouds")
    private void nthcompat$RenderGlobalUpdateCloudsMixin$updateClouds(Operation<Void> original) {
        // renderDispatcher == null only happens when the vanilla chunk pipeline was turned off (Nothirium). With vanilla active, nothing
        // changes.
        if (this.renderDispatcher == null && !this.setLightUpdates.isEmpty()) {
            ShadersCompat.markRan("updateClouds", this.setLightUpdates.size() + " light updates");
            Iterator<BlockPos> iterator = this.setLightUpdates.iterator();
            while (iterator.hasNext()) {
                BlockPos pos = iterator.next();
                iterator.remove();
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();
                this.markBlocksForUpdate(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1, false);
            }
        }
        original.call();
    }

}
