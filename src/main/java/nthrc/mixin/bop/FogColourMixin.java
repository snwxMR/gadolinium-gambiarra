// SPDX-License-Identifier: MIT
package nthrc.mixin.bop;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import biomesoplenty.common.biome.BOPBiome;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import nthrc.Hook;
import nthrc.bop.FogColourCache;

/**
 * fogColourOncePerTick: FogEventHandler.getFogBlendColour (fog colour, every frame) reads the biome and its fog colour of (2r+1)^2 columns around the player.
 * The two reads come from FogColourCache until the next tick; everything else in the method stays Biomes O' Plenty's own.
 */
@Mixin(targets = "biomesoplenty.common.handler.FogEventHandler", remap = false, priority = 1500)
public abstract class FogColourMixin {

    @WrapOperation(method = "getFogBlendColour", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;func_180494_b(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;"))
    private static Biome nthrc$FogColourMixin$biome(World world, BlockPos pos, Operation<Biome> original) {
        return Hook.BOP_FOG_COLOUR.ok ? FogColourCache.biome(world, pos, original) : original.call(world, pos);
    }

    @WrapOperation(method = "getFogBlendColour", at = @At(value = "INVOKE",
            target = "Lbiomesoplenty/common/biome/BOPBiome;getFogColor(Lnet/minecraft/util/math/BlockPos;)I"))
    private static int nthrc$FogColourMixin$fogColour(BOPBiome biome, BlockPos pos, Operation<Integer> original) {
        return Hook.BOP_FOG_COLOUR.ok ? FogColourCache.fogColour(biome, pos, original) : original.call(biome, pos);
    }

}
