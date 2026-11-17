// SPDX-License-Identifier: MIT
package nthrc.mixin.srparasites;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import nthrc.Hook;
import nthrc.srparasites.DisloCache;

/**
 * cacheDisloValues (Scape and Run Parasites 1.10.9): getDisloValues(dim) splits the dimension's code text (dimEPcurrentCodes, at the index where dimEPid has the dimension)
 * by ";" and converts each part. The text is a String, which does not change; SRP puts another String in the list when it changes the codes. So the result is stored
 * together with the String it came from (by identity) and returned as a copy while it stays the same. Dimension missing or repeated in the list: the original.
 *
 * Only the server thread uses SRPSaveData (spawner, world events).
 */
@Mixin(targets = "com.dhanantry.scapeandrunparasites.world.SRPSaveData", remap = false)
public abstract class DisloCacheMixin {

    @Shadow
    private ArrayList<Integer> dimEPid;
    @Shadow
    private ArrayList<String> dimEPcurrentCodes;

    /** Dimension -> {codes String, converted int[]}. Created on the first query (no initializer). */
    @Unique
    private Int2ObjectOpenHashMap<Object[]> nthrc$dislo;

    @WrapMethod(method = "getDisloValues")
    private int[] nthrc$DisloCacheMixin$getDisloValues(int dim, Operation<int[]> original) {
        if (!Hook.SRP_DISLO.ok) {
            return original.call(dim);
        }
        String code = null;
        for (int i = 0; i < dimEPid.size(); i++) {
            if (dimEPid.get(i) == dim) {
                if (code != null) {
                    return original.call(dim);
                }
                code = dimEPcurrentCodes.get(i);
            }
        }
        if (code == null) {
            return original.call(dim);
        }
        if (nthrc$dislo == null) {
            nthrc$dislo = new Int2ObjectOpenHashMap<>();
        }
        Object[] cached = nthrc$dislo.get(dim);
        if (cached != null && cached[0] == code) {
            DisloCache.hit();
            int[] values = ((int[]) cached[1]).clone();
            if (DisloCache.VALIDATE) {
                DisloCache.check(dim, values, original.call(dim));
            }
            return values;
        }
        DisloCache.miss();
        int[] values = original.call(dim);
        nthrc$dislo.put(dim, new Object[] { code, values.clone() });
        return values;
    }

}
