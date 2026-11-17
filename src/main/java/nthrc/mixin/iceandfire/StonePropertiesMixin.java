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
 * skipUnchangedProperties (Ice and Fire 1.9.1, LLibrary 1.7.20): StoneEntityProperties' saveNBTData writes the fields below to LLibrary's tracking NBT
 * (SensitiveTagCompound) every tick. With the same values as the last write to that same NBT, the write is skipped: it would put the same values and would not
 * mark a change. Other NBTs (the world save) always write.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.StoneEntityProperties", remap = false)
public abstract class StonePropertiesMixin {

    @Shadow
    public boolean isStone;
    @Shadow
    public int breakLvl;

    /** The tracking NBT of the last write and the values written to it (no initializer: valid from the first save). */
    @Unique
    private NBTTagCompound nthrc$tag;
    @Unique
    private boolean nthrc$isStone;
    @Unique
    private int nthrc$breakLvl;

    @Inject(method = "saveNBTData", at = @At("HEAD"), cancellable = true)
    private void nthrc$StonePropertiesMixin$skip(NBTTagCompound tag, CallbackInfo ci) {
        boolean same = tag == nthrc$tag && isStone == nthrc$isStone && breakLvl == nthrc$breakLvl;
        if (Hook.IAF_STONE.ok && PropertiesSkip.skip("Stone", tag, same)) {
            ci.cancel();
        }
    }

    @Inject(method = "saveNBTData", at = @At("RETURN"))
    private void nthrc$StonePropertiesMixin$saved(NBTTagCompound tag, CallbackInfo ci) {
        if (Hook.IAF_STONE.ok) {
            PropertiesSkip.afterSave(tag);
            if (tag instanceof SensitiveTagCompound) {
                nthrc$tag = tag;
                nthrc$isStone = isStone;
                nthrc$breakLvl = breakLvl;
            }
        }
    }

}
