// SPDX-License-Identifier: MIT
package nthrc.mixin.iceandfire;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.ilexiconn.llibrary.server.entity.SensitiveTagCompound;
import net.minecraft.nbt.NBTTagCompound;
import nthrc.Hook;
import nthrc.iceandfire.PropertiesSkip;

/**
 * skipUnchangedProperties (Ice and Fire 1.9.1, LLibrary 1.7.20): FrozenEntityProperties' saveNBTData writes the fields below to LLibrary's tracking NBT
 * (SensitiveTagCompound) every tick. With the same values as the last write to that same NBT, the write is skipped: it would put the same values and would not
 * mark a change. Other NBTs (the world save) always write.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.FrozenEntityProperties", remap = false)
public abstract class FrozenPropertiesMixin {

    @Shadow
    public boolean isFrozen;
    @Shadow
    public int ticksUntilUnfrozen;

    /** The tracking NBT of the last write and the values written to it (no initializer: valid from the first save). */
    @Unique
    private NBTTagCompound nthrc$tag;
    @Unique
    private boolean nthrc$isFrozen;
    @Unique
    private int nthrc$ticksUntilUnfrozen;

    @Inject(method = "saveNBTData", at = @At("HEAD"), cancellable = true)
    private void nthrc$FrozenPropertiesMixin$skip(NBTTagCompound tag, CallbackInfo ci) {
        boolean same = tag == nthrc$tag && isFrozen == nthrc$isFrozen && ticksUntilUnfrozen == nthrc$ticksUntilUnfrozen;
        if (Hook.IAF_FROZEN.ok && PropertiesSkip.skip("Frozen", tag, same)) {
            ci.cancel();
        }
    }

    @Inject(method = "saveNBTData", at = @At("RETURN"))
    private void nthrc$FrozenPropertiesMixin$saved(NBTTagCompound tag, CallbackInfo ci) {
        if (Hook.IAF_FROZEN.ok) {
            PropertiesSkip.afterSave(tag);
            if (tag instanceof SensitiveTagCompound) {
                nthrc$tag = tag;
                nthrc$isFrozen = isFrozen;
                nthrc$ticksUntilUnfrozen = ticksUntilUnfrozen;
            }
        }
    }

}
