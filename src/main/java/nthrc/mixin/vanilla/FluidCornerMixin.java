// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.fluids.BlockFluidBase;
import nthrc.Hook;
import nthrc.minecraft.FluidCorner;

/** fluidCornerClamp: the 6 withProperty calls of BlockFluidBase.getExtendedState receive a value within range (FluidCorner). */
@Mixin(value = BlockFluidBase.class, remap = false)
public abstract class FluidCornerMixin {

    @WrapOperation(method = "getExtendedState", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/common/property/IExtendedBlockState;withProperty(Lnet/minecraftforge/common/property/IUnlistedProperty;Ljava/lang/Object;)Lnet/minecraftforge/common/property/IExtendedBlockState;"))
    @SuppressWarnings("rawtypes")
    private IExtendedBlockState nthrc$FluidCornerMixin$clamp(IExtendedBlockState state, IUnlistedProperty property, Object value, Operation<IExtendedBlockState> original) {
        return original.call(state, property, Hook.FLUID_CORNER.active() ? FluidCorner.clamp(property, value) : value);
    }

}
