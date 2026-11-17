// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import com.google.common.base.Predicate;

import gambiarra.common.ValidationLog;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import nthrc.WorldgenConfig;

/**
 * oreVeinChunkCache (see OreVeinMixin): the game's WorldGenMinable.generate, with a single difference. To read the block at each vein position,
 * instead of World.getBlockState (which looks the chunk up in the chunk map for every block), it uses the last chunk looked up while the position
 * stays in it. A vein fits in 1 to 2 chunks. The same math, the same random draws in the same order, the same write (World.setBlockState, with
 * light, height and the notice to clients).
 *
 * Only the server thread generates the world; the counters do not need a lock.
 */
public final class OreVein {

    private static final boolean VALIDATE = WorldgenConfig.validate;

    private static long veins, reads, lookupsSaved;

    private OreVein() {
    }

    /** The vein's read: the stored chunk, if the position is still in it. The first of each chunk goes through the world. */
    private static final class Reader {
        private final World world;
        private Chunk chunk;

        Reader(World world) {
            this.world = world;
        }

        IBlockState read(BlockPos pos) {
            reads++;
            IBlockState state;
            if (pos.getY() < 0 || pos.getY() >= 256) {
                state = Blocks.AIR.getDefaultState();
            } else {
                int cx = pos.getX() >> 4;
                int cz = pos.getZ() >> 4;
                if (chunk == null || chunk.x != cx || chunk.z != cz) {
                    chunk = world.getChunk(cx, cz);
                } else {
                    lookupsSaved++;
                }
                state = chunk.getBlockState(pos);
            }
            if (VALIDATE) {
                IBlockState expected = world.getBlockState(pos);
                ValidationLog.check("minecraft.oreVeinChunkCache", expected == state, "at " + pos + ": world " + expected + ", stored " + state);
                return expected;
            }
            return state;
        }
    }

    /** The body of WorldGenMinable.generate (func_180709_b), in the same order of operations. */
    public static boolean generate(World worldIn, Random rand, BlockPos position, IBlockState oreBlock, int numberOfBlocks,
            Predicate<IBlockState> predicate) {
        veins++;
        Reader reader = new Reader(worldIn);
        float f = rand.nextFloat() * (float) Math.PI;
        double d0 = (double) ((float) (position.getX() + 8) + MathHelper.sin(f) * (float) numberOfBlocks / 8.0F);
        double d1 = (double) ((float) (position.getX() + 8) - MathHelper.sin(f) * (float) numberOfBlocks / 8.0F);
        double d2 = (double) ((float) (position.getZ() + 8) + MathHelper.cos(f) * (float) numberOfBlocks / 8.0F);
        double d3 = (double) ((float) (position.getZ() + 8) - MathHelper.cos(f) * (float) numberOfBlocks / 8.0F);
        double d4 = (double) (position.getY() + rand.nextInt(3) - 2);
        double d5 = (double) (position.getY() + rand.nextInt(3) - 2);

        for (int i = 0; i < numberOfBlocks; ++i) {
            float f1 = (float) i / (float) numberOfBlocks;
            double d6 = d0 + (d1 - d0) * (double) f1;
            double d7 = d4 + (d5 - d4) * (double) f1;
            double d8 = d2 + (d3 - d2) * (double) f1;
            double d9 = rand.nextDouble() * (double) numberOfBlocks / 16.0D;
            double d10 = (double) (MathHelper.sin((float) Math.PI * f1) + 1.0F) * d9 + 1.0D;
            double d11 = (double) (MathHelper.sin((float) Math.PI * f1) + 1.0F) * d9 + 1.0D;
            int j = MathHelper.floor(d6 - d10 / 2.0D);
            int k = MathHelper.floor(d7 - d11 / 2.0D);
            int l = MathHelper.floor(d8 - d10 / 2.0D);
            int i1 = MathHelper.floor(d6 + d10 / 2.0D);
            int j1 = MathHelper.floor(d7 + d11 / 2.0D);
            int k1 = MathHelper.floor(d8 + d10 / 2.0D);

            for (int l1 = j; l1 <= i1; ++l1) {
                double d12 = ((double) l1 + 0.5D - d6) / (d10 / 2.0D);
                if (d12 * d12 < 1.0D) {
                    for (int i2 = k; i2 <= j1; ++i2) {
                        double d13 = ((double) i2 + 0.5D - d7) / (d11 / 2.0D);
                        if (d12 * d12 + d13 * d13 < 1.0D) {
                            for (int j2 = l; j2 <= k1; ++j2) {
                                double d14 = ((double) j2 + 0.5D - d8) / (d10 / 2.0D);
                                if (d12 * d12 + d13 * d13 + d14 * d14 < 1.0D) {
                                    BlockPos blockpos = new BlockPos(l1, i2, j2);
                                    IBlockState state = reader.read(blockpos);
                                    if (state.getBlock().isReplaceableOreGen(state, worldIn, blockpos, predicate)) {
                                        worldIn.setBlockState(blockpos, oreBlock, 2);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("veins", veins);
        m.put("reads", reads);
        m.put("lookupsSkipped", lookupsSaved);
        return m;
    }

}
