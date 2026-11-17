// SPDX-License-Identifier: MIT
package nthrc.mixin.iceandfire;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * skipUnchangedProperties (Ice and Fire 1.9.1, LLibrary 1.7.20): ChainEntityProperties' saveNBTData writes the connectedEntityUUID list (a new NBT list every
 * tick) to LLibrary's tracking NBT. With the same list as the last write to that same NBT (same UUIDs, same order), the write is skipped: SensitiveTagCompound
 * compares the new list with the old one through equals and would not mark a change. Other NBTs (the world save) always write.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.ChainEntityProperties", remap = false)
public abstract class ChainPropertiesMixin {

    @Shadow
    private List<UUID> connectedEntityUUID;

    /** The tracking NBT of the last write and a copy of the list written to it (no initializer). */
    @Unique
    private NBTTagCompound nthrc$tag;
    @Unique
    private List<UUID> nthrc$uuids;

    @Inject(method = "saveNBTData", at = @At("HEAD"), cancellable = true)
    private void nthrc$ChainPropertiesMixin$skip(NBTTagCompound tag, CallbackInfo ci) {
        boolean same = tag == nthrc$tag && nthrc$uuids != null && nthrc$uuids.equals(connectedEntityUUID);
        if (Hook.IAF_CHAIN.ok && PropertiesSkip.skip("Chain", tag, same)) {
            ci.cancel();
        }
    }

    @Inject(method = "saveNBTData", at = @At("RETURN"))
    private void nthrc$ChainPropertiesMixin$saved(NBTTagCompound tag, CallbackInfo ci) {
        if (Hook.IAF_CHAIN.ok) {
            PropertiesSkip.afterSave(tag);
            if (tag instanceof SensitiveTagCompound && connectedEntityUUID != null) {
                nthrc$tag = tag;
                nthrc$uuids = new ArrayList<>(connectedEntityUUID);
            }
        }
    }

}
