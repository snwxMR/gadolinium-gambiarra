// SPDX-License-Identifier: MIT
package nthrc.bop;

import java.util.LinkedHashMap;
import java.util.Map;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import biomesoplenty.common.biome.BOPBiome;
import gambiarra.common.ValidationLog;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Biomes O' Plenty fog colour (client, see FogColourMixin). The expensive part of getFogBlendColour is reading, for each column around the player, the biome and
 * its fog colour. Both only depend on the world and the column, and only change from one tick to the next: here each answer stays stored until the next client tick
 * (ClientTicks).
 *
 * A direct-mapped table of 128 x 128 columns, wider than the largest scan (69 x 69), so no two columns of a scan share a slot. Client thread only.
 */
public final class FogColourCache {

    private static final int SIZE = 128;
    private static final int MASK = SIZE - 1;

    /** Test (development build only): on each hit, also asks the game and checks the answer. */
    public static final boolean VALIDATE = validateRequested();

    private static final long[] columns = new long[SIZE * SIZE];
    private static final int[] biomeGeneration = new int[SIZE * SIZE];
    private static final int[] fogGeneration = new int[SIZE * SIZE];
    private static final Biome[] biomes = new Biome[SIZE * SIZE];
    private static final int[] fogColours = new int[SIZE * SIZE];

    private static Object world;
    private static long tick = Long.MIN_VALUE;
    /** Goes up when the world or the tick changes: a slot of another generation is empty. */
    private static int generation = 1;

    private static long calls, hits, validated, mismatches;

    private FogColourCache() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    private static int slot(BlockPos pos) {
        return (pos.getX() & MASK) << 7 | pos.getZ() & MASK;
    }

    private static long column(BlockPos pos) {
        return (long) pos.getX() << 32 | pos.getZ() & 0xFFFFFFFFL;
    }

    public static Biome biome(World w, BlockPos pos, Operation<Biome> original) {
        calls++;
        long t = ClientTicks.now();
        if (w != world || t != tick) {
            world = w;
            tick = t;
            generation++;
        }
        int i = slot(pos);
        long column = column(pos);
        if (biomeGeneration[i] == generation && columns[i] == column) {
            hits++;
            if (VALIDATE) {
                check(biomes[i] == original.call(w, pos), "biome of column " + pos);
            }
            return biomes[i];
        }
        Biome biome = original.call(w, pos);
        columns[i] = column;
        biomes[i] = biome;
        biomeGeneration[i] = generation;
        return biome;
    }

    /** Always after {@link #biome} for the same column, in the same scan. */
    public static int fogColour(BOPBiome biome, BlockPos pos, Operation<Integer> original) {
        int i = slot(pos);
        if (fogGeneration[i] == generation && columns[i] == column(pos)) {
            if (VALIDATE) {
                check(fogColours[i] == original.call(biome, pos), "fog colour of column " + pos);
            }
            return fogColours[i];
        }
        int colour = original.call(biome, pos);
        if (biomeGeneration[i] == generation && columns[i] == column(pos)) {
            fogColours[i] = colour;
            fogGeneration[i] = generation;
        }
        return colour;
    }

    private static void check(boolean same, String what) {
        validated++;
        ValidationLog.check("biomesoplenty.fogColourOncePerTick", same, what);
        if (!same) {
            mismatches++;
            if (mismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION biomesoplenty.fogColourOncePerTick: the stored {} differs from the game's", what);
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", calls);
        m.put("hits", hits);
        m.put("hitPct", calls > 0 ? Math.round(1000.0 * hits / calls) / 10.0 : 0);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
