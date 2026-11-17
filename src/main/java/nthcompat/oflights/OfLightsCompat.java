// SPDX-License-Identifier: MIT
package nthcompat.oflights;

import gambiarra.common.ValidationLog;
import java.util.LinkedHashMap;
import java.util.Map;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import meldexun.nothirium.util.SectionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.optifine.DynamicLights;
import net.optifine.DynamicLightsMap;
import nthcompat.CompatStatus;

/**
 * What the OptiFine dynamic light mixins call (see OfLightsGate). Uses OptiFine types: only loads from the handlers, which only exist
 * with it.
 */
public final class OfLightsCompat {

    /** 7.5 squared, with some slack for rounding. */
    private static final double REACH_SQ = 56.25 + 1e-9;

    private static long updatesSkipped;
    private static long updatesRun;
    private static long validated;
    private static long mismatches;
    private static String firstMismatch;
    /** The source update in progress (only its thread reads and writes the rest). */
    private static Thread lightThread;
    private static double oldX, oldY, oldZ, newX, newY, newZ;
    private static boolean oldLit, newLit;
    private static long marksKept;
    private static long marksOutOfReach;
    private static long marksEmpty;

    private OfLightsCompat() {
    }

    /** The change handlers (update, entityRemoved, removeLights, clear) take the map's lock around the original and the copy. */
    public static boolean snapshotActive() {
        return OfLightsGate.snapshotOk;
    }

    /**
     * After a change in the state of the sources, with the map's lock still held (the same one OptiFine takes inside): no lookup sees the
     * old copy after the new state exists. Only the render thread gets here; lookups through the copy do not take the lock. Any error
     * turns the copy off (lookups go back to OptiFine).
     */
    public static void publish(DynamicLightsMap map) {
        try {
            OfLightsSnapshot.publish(map.valueList());
        } catch (Throwable t) {
            OfLightsGate.snapshotOk = false;
            CompatStatus.LOG.warn("[gambiarra] OptiFine dynamic lights: the lock-free copy was turned off ({}); lookups go back to OptiFine", t.toString());
            gambiarra.common.StartupReport.failed("optifinedynamiclights.lockFreeLookup", t.toString());
        }
    }

    /** DynamicLights.getLightLevel(BlockPos). */
    public static double levelAt(BlockPos pos, DynamicLightsMap map, Operation<Double> original) {
        if (!OfLightsGate.snapshotOk) {
            return original.call(pos);
        }
        if (!OfLightsGate.VALIDATE) {
            return OfLightsSnapshot.levelAt(pos);
        }
        double expected;
        double found;
        synchronized (map) {
            found = OfLightsSnapshot.levelAt(pos);
            expected = original.call(pos);
            validated++;
            ValidationLog.checked("optifinedynamiclights.lockFreeLookup");
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(found)) {
                mismatches++;
                ValidationLog.diff("optifinedynamiclights.lockFreeLookup", pos + ": OptiFine " + expected + ", copy " + found);
                if (firstMismatch == null) {
                    firstMismatch = pos + ": OptiFine " + expected + ", copy " + found;
                    CompatStatus.LOG.warn("[gambiarra] OptiFine dynamic lights, copy validation: {}", firstMismatch);
                }
            }
        }
        return expected;
    }

    /**
     * Before a source's update (OfDynamicLightMixin). optifineDynamicLightsBlockStep: is the source in the same block (the same position
     * math as OptiFine's and Nothirium's update: position - 0.5, plus offsetY on height) and at the same level as the last update that
     * counted? Then it is skipped (true). optifineDynamicLightsReachedOnly: keeps the old and new light positions for the markDirty of the
     * sections it marks (skipLightMark).
     */
    public static boolean beforeLightUpdate(Entity entity, double offsetY, double lastPosX, double lastPosY, double lastPosZ, int lastLightLevel) {
        boolean step = OfLightsGate.blockStepOk;
        boolean context = OfLightsGate.lightMarksHooked;
        if ((!step && !context) || entity == null) {
            return false;
        }
        double x = entity.posX - 0.5;
        double y = entity.posY - 0.5 + offsetY;
        double z = entity.posZ - 0.5;
        int level = DynamicLights.getLightLevel(entity);
        if (step) {
            if (MathHelper.floor(x) == MathHelper.floor(lastPosX) && MathHelper.floor(y) == MathHelper.floor(lastPosY)
                    && MathHelper.floor(z) == MathHelper.floor(lastPosZ) && level == lastLightLevel) {
                updatesSkipped++;
                return true;
            }
        }
        updatesRun++;
        if (context) {
            oldX = lastPosX;
            oldY = lastPosY;
            oldZ = lastPosZ;
            oldLit = lastLightLevel > 0;
            newX = x;
            newY = y;
            newZ = z;
            newLit = level > 0;
            lightThread = Thread.currentThread();
        }
        return false;
    }

    public static void afterLightUpdate() {
        lightThread = null;
    }

    /**
     * The markDirty of a section (NothiriumMarkDirtyLightMixin). Only during a source's update, on its thread: the section is left out
     * (true, with optifineDynamicLightsReachedOnly) if the light does not reach it at either the old or the new position, or if it has no
     * block (its build comes out empty anyway). Reach: OptiFine's light only goes from 0 to less than 7.5 blocks, and the section's box
     * goes one block beyond on each side (the faces and smooth lighting of border blocks read the neighbouring block's light).
     */
    public static boolean skipLightMark(AbstractRenderChunk chunk) {
        if (lightThread != Thread.currentThread()) {
            return false;
        }
        SectionPos pos = chunk.getPos();
        boolean reached = (oldLit && reaches(oldX, oldY, oldZ, pos)) || (newLit && reaches(newX, newY, newZ, pos));
        if (reached && !hasNoBlocks(pos)) {
            marksKept++;
            return false;
        }
        if (reached) {
            marksEmpty++;
        } else {
            marksOutOfReach++;
        }
        return OfLightsGate.reachedOnlyOk;
    }

    private static boolean reaches(double x, double y, double z, SectionPos s) {
        double d = axis(x, s.getX() << 4) + axis(y, s.getY() << 4) + axis(z, s.getZ() << 4);
        return d <= REACH_SQ;
    }

    private static double axis(double p, int base) {
        double lo = base - 1;
        double hi = base + 16;
        double d = p < lo ? lo - p : p > hi ? p - hi : 0.0;
        return d * d;
    }

    /** The section only has air in the client world (no ExtendedBlockStorage, or 0 blocks). Column not loaded: no. */
    private static boolean hasNoBlocks(SectionPos pos) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null) {
            return false;
        }
        Chunk column = world.getChunkProvider().getLoadedChunk(pos.getX(), pos.getZ());
        int sy = pos.getY();
        if (column == null || sy < 0 || sy >= 16) {
            return false;
        }
        ExtendedBlockStorage storage = column.getBlockStorageArray()[sy];
        return storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty();
    }

    /** For the smoketest: {updates done, skipped, marks kept, out of reach, without blocks, sources in the copy (-1 without it)}. */
    static long[] counters() {
        return new long[] { updatesRun, updatesSkipped, marksKept, marksOutOfReach, marksEmpty, OfLightsGate.snapshotOk ? OfLightsSnapshot.lights() : -1 };
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (OfLightsGate.snapshotOk) {
            m.put("sourcesInCopy", OfLightsSnapshot.lights());
            m.put("copiesPublished", OfLightsSnapshot.publishes());
        }
        if (OfLightsGate.blockStepOk || OfLightsGate.lightMarksHooked) {
            m.put("updatesSkipped", updatesSkipped);
            m.put("updatesDone", updatesRun);
        }
        if (OfLightsGate.lightMarksHooked) {
            m.put("lightMarks", "kept " + marksKept + ", out of reach " + marksOutOfReach + ", sections without blocks " + marksEmpty
                    + (OfLightsGate.reachedOnlyOk ? " (the last two skipped)" : " (counted only)"));
        }
        if (OfLightsGate.VALIDATE) {
            m.put("validation", validated + " lookups compared, " + mismatches + " differences"
                    + (firstMismatch != null ? " (first: " + firstMismatch + ")" : ""));
        }
        return m;
    }

}
