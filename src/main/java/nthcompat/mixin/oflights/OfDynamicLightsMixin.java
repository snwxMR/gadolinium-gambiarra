// SPDX-License-Identifier: MIT
package nthcompat.mixin.oflights;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.optifine.DynamicLightsMap;
import nthcompat.oflights.OfLightsCompat;

/**
 * optifineDynamicLightsSnapshot (see nthcompat.oflights.OfLightsSnapshot): after each change in the state of OptiFine's dynamic lights
 * (update, entityRemoved, removeLights, clear), the copy is published with the map's lock still held, and the per-block lookup uses
 * the copy, without the lock. Higher priority than Nothirium's: wraps the method with what it injects already there.
 */
@Mixin(targets = "net.optifine.DynamicLights", remap = false, priority = 1500)
public abstract class OfDynamicLightsMixin {

    @Shadow
    private static DynamicLightsMap mapDynamicLights;

    @WrapMethod(method = "update")
    private static void nthcompat$OfDynamicLightsMixin$update(RenderGlobal renderGlobal, Operation<Void> original) {
        DynamicLightsMap map = mapDynamicLights;
        if (!OfLightsCompat.snapshotActive() || map == null) {
            original.call(renderGlobal);
            return;
        }
        synchronized (map) {
            original.call(renderGlobal);
            OfLightsCompat.publish(map);
        }
    }

    @WrapMethod(method = "entityRemoved")
    private static void nthcompat$OfDynamicLightsMixin$entityRemoved(Entity entity, RenderGlobal renderGlobal, Operation<Void> original) {
        DynamicLightsMap map = mapDynamicLights;
        if (!OfLightsCompat.snapshotActive() || map == null) {
            original.call(entity, renderGlobal);
            return;
        }
        synchronized (map) {
            original.call(entity, renderGlobal);
            OfLightsCompat.publish(map);
        }
    }

    @WrapMethod(method = "removeLights")
    private static void nthcompat$OfDynamicLightsMixin$removeLights(RenderGlobal renderGlobal, Operation<Void> original) {
        DynamicLightsMap map = mapDynamicLights;
        if (!OfLightsCompat.snapshotActive() || map == null) {
            original.call(renderGlobal);
            return;
        }
        synchronized (map) {
            original.call(renderGlobal);
            OfLightsCompat.publish(map);
        }
    }

    @WrapMethod(method = "clear")
    private static void nthcompat$OfDynamicLightsMixin$clear(Operation<Void> original) {
        DynamicLightsMap map = mapDynamicLights;
        if (!OfLightsCompat.snapshotActive() || map == null) {
            original.call();
            return;
        }
        synchronized (map) {
            original.call();
            OfLightsCompat.publish(map);
        }
    }

    @WrapMethod(method = "getLightLevel(Lnet/minecraft/util/math/BlockPos;)D")
    private static double nthcompat$OfDynamicLightsMixin$getLightLevel(BlockPos pos, Operation<Double> original) {
        return OfLightsCompat.levelAt(pos, mapDynamicLights, original);
    }

}
