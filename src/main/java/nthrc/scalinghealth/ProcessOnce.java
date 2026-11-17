// SPDX-License-Identifier: MIT
package nthrc.scalinghealth;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * processMobsOnce (Scaling Health): DifficultyHandler.isProcessed replaced. The original counts the mob as processed with the "scalinghealth:difficulty" mark different from 0; here, with the mark present (every pass through
 * process writes it). Counts the times the original would have redone the math (mark present with 0). Only the server thread calls it.
 */
public final class ProcessOnce {

    private static final String MARK = "scalinghealth:difficulty";

    private static long avoided;

    private ProcessOnce() {
    }

    public static boolean isProcessed(EntityLivingBase entity) {
        NBTTagCompound data = entity.getEntityData();
        if (!data.hasKey(MARK)) {
            return false;
        }
        if (data.getShort(MARK) == 0) {
            avoided++;
        }
        return true;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("redoneMathAvoided", avoided);
        return m;
    }

}
