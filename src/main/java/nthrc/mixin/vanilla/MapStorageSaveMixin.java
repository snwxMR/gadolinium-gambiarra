// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import nthrc.Hook;
import nthrc.rc.StructureDataWriter;

/**
 * saveStructureDataOffThread: MapStorage.saveData (func_75747_a) writes a dirty WorldSavedData, compressed, on the server thread. Only for Recurrent Complex's structures
 * file, the write goes to the game's disk queue (StructureDataWriter). The other data follows the game's path.
 *
 * Stays in the "vanilla" group (MapStorage is the game's); the optimization's decision belongs to the Recurrent Complex group, checked in the handler (Hook.active).
 */
@Mixin(value = MapStorage.class, remap = false)
public abstract class MapStorageSaveMixin {

    @Shadow
    @Final
    private ISaveHandler field_75751_a;

    @Inject(method = "func_75747_a", at = @At("HEAD"), cancellable = true)
    private void nthrc$MapStorageSaveMixin$offThread(WorldSavedData data, CallbackInfo ci) {
        if (Hook.RC_DATA.active() && field_75751_a != null && StructureDataWriter.handles(data)
                && StructureDataWriter.queue(field_75751_a, data)) {
            ci.cancel();
        }
    }

}
