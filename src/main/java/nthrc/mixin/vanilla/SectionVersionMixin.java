// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.BlockStateContainer;
import nthrc.minecraft.SectionVersion;

/**
 * restingItemsSkipCollision: counts the writes of each section (the end of BlockStateContainer.set(index, state), func_186014_b). The public set(x, y, z, state) and the palette
 * swap go through it. It only adds; it changes nothing.
 */
@Mixin(value = BlockStateContainer.class, remap = false)
public abstract class SectionVersionMixin implements SectionVersion {

    @Unique
    private int nthrc$version;

    @Inject(method = "func_186014_b", at = @At("TAIL"))
    private void nthrc$SectionVersionMixin$count(int index, IBlockState state, CallbackInfo ci) {
        nthrc$version++;
    }

    @Override
    public int nthrc$version() {
        return nthrc$version;
    }

}
