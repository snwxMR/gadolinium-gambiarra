// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import gambiarra.common.ValidationLog;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.ilexiconn.llibrary.client.model.Transform;
import net.minecraft.client.model.ModelRenderer;
import nthcompat.CompatStatus;

/**
 * cacheTabulaPoses: IceAndFireTabulaModelAnimator.moveToPose only runs for the animation that is playing (TabulaAnimatorPoseCacheMixin).
 *
 * The dragon calls moveToPose for each possible animation, every frame (29 times); for each model part (~100 on the dragon) it looks up parts by name and sends the
 * differences to LLibrary's ModelAnimator (rotate/move), which only stores something for the animation that is playing. Outside it, nothing is done here (the original
 * would only do math with no effect). The animation that is playing runs the original, unchanged.
 *
 * Render thread.
 */
public final class TabulaPoseCache {

    private TabulaPoseCache() {
    }

    static long active;
    static long skipped;
    static long validated;
    static long mismatches;
    private static String firstMismatch;

    public static void noteSkipped() {
        skipped++;
    }

    public static void noteActive() {
        active++;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Validation (development build only, -Dnthcompat.iceandfire.validate=true)

    /** The values of each Transform in the animator's map, per part. */
    public static Map<ModelRenderer, float[]> snapshot(HashMap<ModelRenderer, Transform> map) {
        Map<ModelRenderer, float[]> out = new IdentityHashMap<>();
        for (Map.Entry<ModelRenderer, Transform> e : map.entrySet()) {
            Transform t = e.getValue();
            out.put(e.getKey(), new float[] { t.getRotationX(), t.getRotationY(), t.getRotationZ(), t.getOffsetX(), t.getOffsetY(), t.getOffsetZ() });
        }
        return out;
    }

    /** A skipped call must leave the animator's map as it was. */
    public static void compare(Map<ModelRenderer, float[]> before, Map<ModelRenderer, float[]> after) {
        validated++;
        ValidationLog.checked("iceandfire.cacheTabulaPoses.pose");
        String diff = null;
        if (before.size() != after.size()) {
            diff = "parts in the animator: before " + before.size() + ", after " + after.size();
        } else {
            for (Map.Entry<ModelRenderer, float[]> e : before.entrySet()) {
                float[] b = after.get(e.getKey());
                float[] a = e.getValue();
                if (b == null) {
                    diff = "part " + e.getKey().boxName + " only before";
                    break;
                }
                for (int i = 0; i < 6; i++) {
                    if (a[i] != b[i]) {
                        diff = "part " + e.getKey().boxName + ", value " + i + ": before " + a[i] + ", after " + b[i];
                        break;
                    }
                }
                if (diff != null) {
                    break;
                }
            }
        }
        if (diff != null) {
            mismatches++;
            ValidationLog.diff("iceandfire.cacheTabulaPoses.pose", diff);
            if (firstMismatch == null) {
                firstMismatch = diff;
                CompatStatus.LOG.warn("[gambiarra] Ice and Fire, cacheTabulaPoses validation: difference ({})", diff);
            }
        }
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callsInPlayingAnimation", active);
        m.put("callsSkipped", skipped);
        if (IafCompat.VALIDATE) {
            m.put("validation", validated + " calls compared, " + mismatches + " differences" + (firstMismatch != null ? " (first: " + firstMismatch + ")" : ""));
        }
        return m;
    }

}
