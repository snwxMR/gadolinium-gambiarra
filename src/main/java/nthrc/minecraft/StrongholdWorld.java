// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.World;

/**
 * fixStrongholdLocateCrash: the world MapGenStronghold.getNearestStructurePos receives goes to the generator when it does not have one yet (see
 * StrongholdWorldMixin). Counts how many times this avoided the crash.
 */
public final class StrongholdWorld {

    private static volatile long filled;

    private StrongholdWorld() {
    }

    /** The world to use: the generator's, or the lookup's if the generator's is null. */
    public static World fill(World current, World fromCall) {
        if (current != null) {
            return current;
        }
        filled++;
        return fromCall;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worldFilled", filled);
        return m;
    }

}
