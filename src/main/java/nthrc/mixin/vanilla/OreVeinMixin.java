// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import java.util.Random;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.base.Predicate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenMinable;
import nthrc.Hook;
import nthrc.minecraft.OreVein;

/**
 * oreVeinChunkCache: WorldGenMinable.generate (func_180709_b) read through a stored "last chunk" (OreVein). A @WrapMethod, and not a swap of the getBlockState inside it:
 * wrapping each read would create one object per block.
 */
@Mixin(value = WorldGenMinable.class, remap = false)
public abstract class OreVeinMixin {

    @Shadow
    @Final
    private IBlockState field_175920_a;
    @Shadow
    @Final
    private int field_76541_b;
    @Shadow
    @Final
    private Predicate<IBlockState> field_175919_c;

    @WrapMethod(method = "func_180709_b")
    private boolean nthrc$OreVeinMixin$generate(World world, Random rand, BlockPos position, Operation<Boolean> original) {
        if (!Hook.ORE_VEIN.active()) {
            return original.call(world, rand, position);
        }
        return OreVein.generate(world, rand, position, field_175920_a, field_76541_b, field_175919_c);
    }

}
