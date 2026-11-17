// SPDX-License-Identifier: MIT
package nthrc.addstructures;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Optimization G (Additional Structures): the StructureGenerator.getGroundFromAbove scan starts right above the chunk's highest
 * section that has blocks, and not at the top of the world.
 *
 * The method goes down from y = getActualHeight() (256) calling world.getBlockState block by block until it finds something that is
 * not air, grass, snow, leaves, log, flower, cactus, vine or double plant. Above the last non-empty section of the chunk, every block
 * is air (Blocks.AIR), which the loop only passes through: starting there gives the SAME ground. The chunk is fetched the same way the
 * original's first read would fetch it (World.getChunk), so loading or generating the chunk happens the same.
 */
public final class GroundScan {

    /** Test (development build only): does not shorten, checks that the skipped stretch is only air. */
    public static final boolean VALIDATE = validateRequested();

    private static long calls;
    private static long skippedReads;
    private static long validated;
    private static long mismatches;

    private GroundScan() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /** Height where the getGroundFromAbove loop starts (the original uses top). */
    public static int start(World world, int x, int z, int top) {
        Chunk chunk = world.getChunk(x >> 4, z >> 4);
        int start = Math.min(top, chunk.getTopFilledSegment() + 16);
        calls++;
        skippedReads += top - start;
        if (VALIDATE) {
            validated++;
            ValidationLog.checked("additionalstructures.groundScanFromTopSection");
            for (int y = start; y < top; y++) {
                if (world.getBlockState(new BlockPos(x, y, z)).getBlock() != Blocks.AIR) {
                    mismatches++;
                    ValidationLog.diff("additionalstructures.groundScanFromTopSection", "non-air block at " + x + "," + y + "," + z);
                    if (mismatches <= 10) {
                        WorldgenStatus.LOG.error("[gambiarra] VALIDATION additionalstructures.groundScanFromTopSection: non-air block at {},{},{}",
                                x, y, z);
                    }
                    break;
                }
            }
            return top;
        }
        return start;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", calls);
        m.put("readsSkipped", skippedReads);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
        }
        return m;
    }

}
