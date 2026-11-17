// SPDX-License-Identifier: MIT
package nthrc.iceandfire;

import java.util.LinkedHashMap;
import java.util.Map;

import net.ilexiconn.llibrary.server.entity.SensitiveTagCompound;
import net.minecraft.nbt.NBTTagCompound;
import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * skipUnchangedProperties (Ice and Fire): the decision shared by the mixins of each data set (Misc, Frozen, Chain, Siren, Stone), the
 * counters and the side-by-side validation. Only the server thread tracks the properties.
 *
 * Validation (development build only): when the write would be skipped, it runs anyway, and at the end checks that it did not mark a change
 * in the tracking NBT (if it was not marked yet; marked before, there is no way to tell).
 */
public final class PropertiesSkip {

    public static final boolean VALIDATE = WorldgenConfig.validate;

    private static long skipped;
    private static long saved;
    private static long validated;
    private static long mismatches;
    private static NBTTagCompound pending;
    private static String pendingWhat;

    private PropertiesSkip() {
    }

    /** At the start of saveNBTData: true = skip (the values are those of the last write to this NBT). */
    public static boolean skip(String what, NBTTagCompound tag, boolean unchanged) {
        if (!unchanged) {
            saved++;
            return false;
        }
        if (VALIDATE && tag instanceof SensitiveTagCompound && !((SensitiveTagCompound) tag).hasChanged()) {
            pending = tag;
            pendingWhat = what;
            return false;
        }
        skipped++;
        return !VALIDATE;
    }

    /** At the end of saveNBTData (validation): the write that would be skipped must not have marked a change. */
    public static void afterSave(NBTTagCompound tag) {
        if (pending != tag) {
            return;
        }
        pending = null;
        validated++;
        ValidationLog.checked("iceandfire.skipUnchangedProperties");
        if (((SensitiveTagCompound) tag).hasChanged()) {
            mismatches++;
            ValidationLog.diff("iceandfire.skipUnchangedProperties", pendingWhat + " marked a change with the same values");
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION iceandfire.skipUnchangedProperties: {} marked a change with the same values",
                        pendingWhat);
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skipped", skipped);
        m.put("written", saved);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
