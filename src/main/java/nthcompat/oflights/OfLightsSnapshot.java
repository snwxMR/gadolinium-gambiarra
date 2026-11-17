// SPDX-License-Identifier: MIT
package nthcompat.oflights;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.optifine.DynamicLight;

/**
 * optifineDynamicLightsSnapshot (0.10.0, item e of docs/item2-luz-dinamica.md): OptiFine's per-block dynamic light lookup
 * (DynamicLights.getLightLevel(BlockPos)) without the global lock.
 *
 * OptiFine walks all sources inside a synchronized block on the lights map, for each block built (Nothirium's build threads) and for
 * each entity drawn. Each source's state (position, level, underwater) only changes inside DynamicLights.update and when sources leave
 * (entityRemoved, removeLights, clear), on the render thread. Right after each of those, with the lock still held, an immutable copy of
 * the state is published (volatile), and the lookup uses the copy, with the same math as OptiFine, in the same order: level x (1 -
 * distance/7.5), up to 7.5 blocks; underwater without "clear water", level - 2 and the squared distance doubled; at most 15.
 */
public final class OfLightsSnapshot {

    private static final double MAX_DIST = 7.5;
    private static final double MAX_DIST_SQ = 56.25;

    /** Sources with level > 0: position, level (already adjusted for underwater) and the squared distance factor. */
    private static final class Lights {
        final double[] x, y, z, level, distFactor;
        final int n;
        final boolean clearWater;

        Lights(int n, boolean clearWater) {
            x = new double[n];
            y = new double[n];
            z = new double[n];
            level = new double[n];
            distFactor = new double[n];
            this.n = n;
            this.clearWater = clearWater;
        }
    }

    /** Before the first update OptiFine's map is empty: the empty copy answers the same (0). */
    private static volatile Lights current = new Lights(0, false);
    private static MethodHandle isClearWater;
    private static boolean clearWaterTried;
    private static long publishes;

    private OfLightsSnapshot() {
    }

    /**
     * Render thread, with the map's lock held. Called every frame (DynamicLights.update only works every 50 ms): with no change, it creates
     * nothing.
     */
    static void publish(List<DynamicLight> lights) {
        boolean clearWater = clearWater();
        int count = 0;
        for (int i = 0; i < lights.size(); i++) {
            if (lights.get(i).getLastLightLevel() > 0) {
                count++;
            }
        }
        Lights old = current;
        if (count == old.n && clearWater == old.clearWater && same(old, lights, clearWater)) {
            return;
        }
        Lights next = new Lights(count, clearWater);
        int k = 0;
        for (int i = 0; i < lights.size(); i++) {
            DynamicLight l = lights.get(i);
            int level = l.getLastLightLevel();
            if (level <= 0) {
                continue;
            }
            next.x[k] = l.getLastPosX();
            next.y[k] = l.getLastPosY();
            next.z[k] = l.getLastPosZ();
            next.level[k] = levelOf(l, level, clearWater);
            next.distFactor[k] = factorOf(l, clearWater);
            k++;
        }
        current = next;
        publishes++;
    }

    private static boolean same(Lights old, List<DynamicLight> lights, boolean clearWater) {
        int k = 0;
        for (int i = 0; i < lights.size(); i++) {
            DynamicLight l = lights.get(i);
            int level = l.getLastLightLevel();
            if (level <= 0) {
                continue;
            }
            if (old.x[k] != l.getLastPosX() || old.y[k] != l.getLastPosY() || old.z[k] != l.getLastPosZ()
                    || old.level[k] != levelOf(l, level, clearWater) || old.distFactor[k] != factorOf(l, clearWater)) {
                return false;
            }
            k++;
        }
        return true;
    }

    /** Underwater without clear water: Config.limit(level - 2, 0, 15), as OptiFine. */
    private static int levelOf(DynamicLight l, int level, boolean clearWater) {
        return l.isUnderwater() && !clearWater ? Math.max(0, Math.min(15, level - 2)) : level;
    }

    private static double factorOf(DynamicLight l, boolean clearWater) {
        return l.isUnderwater() && !clearWater ? 2.0 : 1.0;
    }

    /** The same math as DynamicLights.getLightLevel(BlockPos), on the copy, without the lock. */
    static double levelAt(BlockPos pos) {
        Lights s = current;
        double max = 0.0;
        double bx = pos.getX();
        double by = pos.getY();
        double bz = pos.getZ();
        for (int i = 0; i < s.n; i++) {
            double dx = bx - s.x[i];
            double dy = by - s.y[i];
            double dz = bz - s.z[i];
            // x 1.0 is exact: out of water, the same value as OptiFine.
            double distSq = (dx * dx + dy * dy + dz * dz) * s.distFactor[i];
            if (!(distSq > MAX_DIST_SQ)) {
                double light = (1.0 - Math.sqrt(distSq) / MAX_DIST) * s.level[i];
                if (light > max) {
                    max = light;
                }
            }
        }
        // Config.limit(max, 0, 15): max is never less than 0.
        return max > 15.0 ? 15.0 : max;
    }

    /**
     * OptiFine's Config.isClearWater() (a class with no package on 1.12.2; by reflection). Without it, the copy is not valid (the caller
     * turns it off).
     */
    private static boolean clearWater() {
        if (!clearWaterTried) {
            clearWaterTried = true;
            for (String name : new String[] { "Config", "net.optifine.Config" }) {
                try {
                    isClearWater = MethodHandles.publicLookup().findStatic(Class.forName(name), "isClearWater", MethodType.methodType(boolean.class));
                    break;
                } catch (Throwable ignored) {
                    // tries the other name
                }
            }
        }
        if (isClearWater == null) {
            throw new IllegalStateException("OptiFine's Config.isClearWater() not found");
        }
        try {
            return (boolean) isClearWater.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("OptiFine's Config.isClearWater() failed", t);
        }
    }

    static long publishes() {
        return publishes;
    }

    static int lights() {
        return current.n;
    }

}
