// SPDX-License-Identifier: MIT
package nthrc.wenchants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Phoenix Dive crash (Wonderful Enchantments 0.6.0). PhoenixDiveEnchantment.onUpdate (WorldTickEvent) keeps a static map particleTimers (entity id -> counter of the foot particles) and, in the second loop, tries to delete the entries of
 * entities not in the event's world with particleTimers.values().remove(id):
 *  - it deletes the first entry whose VALUE (a counter) equals the id, and not the id's entry;
 *  - when it deletes one, the map changes mid-scan and the next next() throws ConcurrentModificationException, which brings the server down (line 120 of the crash = the for of the second loop).
 *
 * Here the wrong removal becomes: note the id (only if the entity does not exist in ANY loaded world) and delete by key after the scan, at the end of onUpdate. An entity id that vanished from all worlds never comes back (a reloaded entity
 * gets a new id), so the entry would only pile up. An entity in another dimension keeps its entry, as today (the original could never delete it by key). The particles come out the same; the enchantment's fall damage and jump do not pass
 * through here. Server thread only.
 */
public final class PhoenixDiveFix {

    private static final List<Integer> pending = new ArrayList<>();

    private static long wrongRemovalsAvoided, keptInOtherWorld, removed;

    private PhoenixDiveFix() {
    }

    /** Instead of particleTimers.values().remove(id): nothing leaves the map during the scan. */
    public static boolean deferRemoval(Object id) {
        wrongRemovalsAvoided++;
        if (id instanceof Integer && missingEverywhere((Integer) id)) {
            pending.add((Integer) id);
        } else {
            keptInOtherWorld++;
        }
        return false;
    }

    private static boolean missingEverywhere(int id) {
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world != null && world.getEntityByID(id) != null) {
                return false;
            }
        }
        return true;
    }

    /** At the end of onUpdate, outside the scan: deletes the noted entries by key. */
    public static void flush(Map<Integer, Integer> particleTimers) {
        if (pending.isEmpty()) {
            return;
        }
        if (particleTimers != null) {
            for (Integer id : pending) {
                if (particleTimers.remove(id) != null) {
                    removed++;
                }
            }
        }
        pending.clear();
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wrongRemovalsAvoided", wrongRemovalsAvoided);
        m.put("entriesDeleted", removed);
        m.put("keptEntityInAnotherWorld", keptInOtherWorld);
        return m;
    }

}
