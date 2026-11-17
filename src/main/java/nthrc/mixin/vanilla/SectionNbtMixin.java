// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;
import nthrc.Hook;
import nthrc.minecraft.SectionNbt;

/**
 * saveSectionsByPalette: BlockStateContainer.getDataForNBT (func_186017_a) builds the section's arrays for the chunk NBT looking up the number of each of the 4096 blocks in
 * Block.BLOCK_STATE_IDS. Here the number is looked up once per palette index (SectionNbt). In validation, the original runs and the end of the method checks the bytes.
 *
 * SRG names and remap=false, like the rest of the module (the group has no refmap).
 */
@Mixin(value = BlockStateContainer.class, remap = false)
public abstract class SectionNbtMixin {

    @Shadow
    protected BitArray field_186021_b;
    @Shadow
    protected IBlockStatePalette field_186022_c;
    @Shadow
    private int field_186024_e;
    @Shadow
    @Final
    protected static IBlockState field_186020_a;

    @Inject(method = "func_186017_a", at = @At("HEAD"), cancellable = true)
    private void nthrc$SectionNbtMixin$byPalette(byte[] blockIds, NibbleArray data, CallbackInfoReturnable<NibbleArray> cir) {
        if (Hook.SECTION_NBT.active()) {
            NibbleArray add = SectionNbt.write(field_186021_b, field_186022_c, field_186024_e, field_186020_a, blockIds, data);
            if (add != SectionNbt.ORIGINAL) {
                cir.setReturnValue(add);
            }
        }
    }

    @Inject(method = "func_186017_a", at = @At("RETURN"))
    private void nthrc$SectionNbtMixin$check(byte[] blockIds, NibbleArray data, CallbackInfoReturnable<NibbleArray> cir) {
        if (Hook.SECTION_NBT.active()) {
            SectionNbt.checkOriginal(blockIds, data, cir.getReturnValue());
        }
    }

}
