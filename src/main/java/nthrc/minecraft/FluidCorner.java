// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import gambiarra.common.ValidationLog;
import net.minecraftforge.common.property.IUnlistedProperty;

/**
 * fluidCornerClamp (see FluidCornerMixin). A fluid corner's height can come out slightly above 1 (BoP honey: 1.0628), and the property only
 * accepts 0 to 1: withProperty throws an exception and brings down the chunk compile (two crashes on 14/10). Here only the value out of range
 * changes, to its limit. Runs on the compile threads.
 */
public final class FluidCorner {

    private static final AtomicLong clamped = new AtomicLong();

    private FluidCorner() {
    }

    /** The value to use: the same, unless it is a Float out of range and the limit fits in it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object clamp(IUnlistedProperty property, Object value) {
        if (!(value instanceof Float) || property.isValid(value)) {
            return value;
        }
        Float limited = Math.max(0.0F, Math.min(1.0F, (Float) value));
        if (!property.isValid(limited)) {
            return value;
        }
        clamped.incrementAndGet();
        ValidationLog.checked("minecraft.fluidCornerClamp");
        return limited;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cornersClamped", clamped.get());
        return m;
    }

}
