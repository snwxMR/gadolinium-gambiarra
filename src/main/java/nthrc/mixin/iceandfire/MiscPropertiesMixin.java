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
 * skipUnchangedProperties (Ice and Fire 1.9.1, LLibrary 1.7.20): MiscEntityProperties' saveNBTData writes the fields below to LLibrary's tracking NBT
 * (SensitiveTagCompound) every tick. With the same values as the last write to that same NBT, the write is skipped: it would put the same values and would not
 * mark a change. Other NBTs (the world save) always write.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.MiscEntityProperties", remap = false)
public abstract class MiscPropertiesMixin {

    @Shadow
    public boolean hasDismountedDragon;
    @Shadow
    public int specialWeaponDmg;
    @Shadow
    public int lastEnteredDreadPortalX;
    @Shadow
    public int lastEnteredDreadPortalY;
    @Shadow
    public int lastEnteredDreadPortalZ;

    /** The tracking NBT of the last write and the values written to it (no initializer: valid from the first save). */
    @Unique
    private NBTTagCompound nthrc$tag;
    @Unique
    private boolean nthrc$hasDismountedDragon;
    @Unique
    private int nthrc$specialWeaponDmg;
    @Unique
    private int nthrc$portalX;
    @Unique
    private int nthrc$portalY;
    @Unique
    private int nthrc$portalZ;

    @Inject(method = "saveNBTData", at = @At("HEAD"), cancellable = true)
    private void nthrc$MiscPropertiesMixin$skip(NBTTagCompound tag, CallbackInfo ci) {
        boolean same = tag == nthrc$tag && hasDismountedDragon == nthrc$hasDismountedDragon
                && specialWeaponDmg == nthrc$specialWeaponDmg && lastEnteredDreadPortalX == nthrc$portalX
                && lastEnteredDreadPortalY == nthrc$portalY && lastEnteredDreadPortalZ == nthrc$portalZ;
        if (Hook.IAF_MISC.ok && PropertiesSkip.skip("Misc", tag, same)) {
            ci.cancel();
        }
    }

    @Inject(method = "saveNBTData", at = @At("RETURN"))
    private void nthrc$MiscPropertiesMixin$saved(NBTTagCompound tag, CallbackInfo ci) {
        if (Hook.IAF_MISC.ok) {
            PropertiesSkip.afterSave(tag);
            if (tag instanceof SensitiveTagCompound) {
                nthrc$tag = tag;
                nthrc$hasDismountedDragon = hasDismountedDragon;
                nthrc$specialWeaponDmg = specialWeaponDmg;
                nthrc$portalX = lastEnteredDreadPortalX;
                nthrc$portalY = lastEnteredDreadPortalY;
                nthrc$portalZ = lastEnteredDreadPortalZ;
            }
        }
    }

}
