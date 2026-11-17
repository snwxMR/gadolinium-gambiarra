// SPDX-License-Identifier: MIT
package nthrc.bop;

import java.util.LinkedHashMap;
import java.util.Map;

import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Biomes O' Plenty fog (client): FogEventHandler.onRenderFog queries the biome of 41x41 blocks around the player on every fog event
 * (several per frame with shaders) and ends in renderFog(mode, distance, start). Its per-block cache never hits: it compares floor(posX)
 * with the stored posX without rounding.
 *
 * Here the computed distance and start are stored until the next client tick (ClientTicks), for the same world, the same exact position
 * of the entity and the same render distance of the event. Biomes O' Plenty's computation only depends on that and on the world's biomes,
 * which only change from one tick to the next (packets and chunks are processed in the tick), so the result is the same. The position
 * is the exact one, not the block: the border columns weigh by its fractional part (rc4 found 240.00002 against a cached 239.99998). Up to 4 render distances per tick
 * (the sky and the terrain ask for different distances). Client thread only.
 */
public final class FogScanCache {

    /** Test (development build only): on each hit, also computes and checks the two values. */
    public static final boolean VALIDATE = validateRequested();

    private static final int SLOTS = 4;

    private static Object world;
    private static long tick = Long.MIN_VALUE;
    private static long xBits, yBits, zBits;
    private static int used;
    private static final int[] farPlaneBits = new int[SLOTS];
    private static final float[] far = new float[SLOTS], start = new float[SLOTS];

    /** Capture of Biomes O' Plenty's renderFog arguments during a computation. */
    private static boolean capturing, captured;
    private static float capturedFar, capturedStart;

    private static long calls, hits, validated, mismatches;

    private FogScanCache() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /** Index of the entry for this key, or -1. A new world, tick or position empties the cache. */
    public static int find(Object w, double posX, double posY, double posZ, float farPlane) {
        calls++;
        long t = ClientTicks.now();
        long bx = Double.doubleToRawLongBits(posX);
        long by = Double.doubleToRawLongBits(posY);
        long bz = Double.doubleToRawLongBits(posZ);
        if (w != world || t != tick || bx != xBits || by != yBits || bz != zBits) {
            world = w;
            tick = t;
            xBits = bx;
            yBits = by;
            zBits = bz;
            used = 0;
            return -1;
        }
        int bits = Float.floatToIntBits(farPlane);
        for (int i = 0; i < used; i++) {
            if (farPlaneBits[i] == bits) {
                return i;
            }
        }
        return -1;
    }

    public static float far(int i) {
        return far[i];
    }

    public static float start(int i) {
        return start[i];
    }

    public static void hit() {
        hits++;
    }

    /** Before calling the original computation: starts capturing the renderFog arguments. */
    public static void beginCapture() {
        capturing = true;
        captured = false;
    }

    /** Called by the mixin on the original computation's renderFog. */
    public static void capture(float farValue, float startValue) {
        if (capturing) {
            capturedFar = farValue;
            capturedStart = startValue;
            captured = true;
        }
    }

    /** After the original computation: stores the result for the key (if renderFog was called). */
    public static void endCapture(float farPlane) {
        capturing = false;
        if (captured && used < SLOTS) {
            farPlaneBits[used] = Float.floatToIntBits(farPlane);
            far[used] = capturedFar;
            start[used] = capturedStart;
            used++;
        }
    }

    /** Validation: the original computation (already captured) against cache entry i. */
    public static void endValidation(int i) {
        capturing = false;
        hits++;
        validated++;
        ValidationLog.checked("biomesoplenty.fogScanOncePerTick");
        boolean same = captured && Float.floatToIntBits(capturedFar) == Float.floatToIntBits(far[i])
                && Float.floatToIntBits(capturedStart) == Float.floatToIntBits(start[i]);
        if (!same) {
            mismatches++;
            ValidationLog.diff("biomesoplenty.fogScanOncePerTick", "cache (" + far[i] + ", " + start[i] + ") and computed (" + capturedFar + ", " + capturedStart + ")");
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION biomesoplenty.fogScanOncePerTick: cache ({}, {}) and computed ({}, {}) differ",
                        far[i], start[i], capturedFar, capturedStart);
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fogEvents", calls);
        m.put("hits", hits);
        m.put("hitPct", calls > 0 ? Math.round(1000.0 * hits / calls) / 10.0 : 0);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
