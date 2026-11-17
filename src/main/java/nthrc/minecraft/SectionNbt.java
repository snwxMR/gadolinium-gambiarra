// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import gambiarra.common.ValidationLog;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStatePaletteRegistry;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.NibbleArray;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * saveSectionsByPalette (see SectionNbtMixin). The original does, for each index i of the section:
 * id = BLOCK_STATE_IDS.get(get(i)), with get(i) = palette.getBlockState(storage.getAt(i)) (null becomes air); writes
 * blockIds[i] = id &gt;&gt; 4 &amp; 255, data = id &amp; 15 and, if id &gt;&gt; 12 &amp; 15 is not 0, the "Add" array.
 *
 * Here the id comes out once per value stored in the storage (the palette index, at most 2^bits values) and is reused at the other positions with
 * the same value: the same math, in the same order, the same bytes. The global palette (more than 8 bits) stays with the original: the stored value
 * is already a registry id, and the array would be too large.
 *
 * May be called from more than one thread (the chunk save and Xaero's): the scratch is per thread.
 */
public final class SectionNbt {

    /** Returned by write when the original must run (global palette, or validation mode). */
    public static final NibbleArray ORIGINAL = new NibbleArray();

    private static final boolean VALIDATE = validateRequested();
    private static final int MAX_BITS = 8;
    private static final int UNSET = Integer.MIN_VALUE;

    private static final ThreadLocal<int[]> IDS = ThreadLocal.withInitial(() -> new int[1 << MAX_BITS]);
    /** Validation: our result, computed before the original. */
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private static final AtomicLong sections = new AtomicLong(), fallback = new AtomicLong(), lookupsSaved = new AtomicLong();
    private static final AtomicLong validated = new AtomicLong(), mismatches = new AtomicLong();

    private static final class Pending {
        final byte[] blockIds = new byte[4096];
        final NibbleArray data = new NibbleArray();
        NibbleArray add;
    }

    private SectionNbt() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    public static NibbleArray write(BitArray storage, IBlockStatePalette palette, int bits, IBlockState air, byte[] blockIds,
            NibbleArray data) {
        if (bits > MAX_BITS || palette instanceof BlockStatePaletteRegistry) {
            fallback.incrementAndGet();
            return ORIGINAL;
        }
        if (VALIDATE) {
            Pending p = new Pending();
            p.add = fill(storage, palette, bits, air, p.blockIds, p.data);
            PENDING.set(p);
            return ORIGINAL;
        }
        sections.incrementAndGet();
        return fill(storage, palette, bits, air, blockIds, data);
    }

    private static NibbleArray fill(BitArray storage, IBlockStatePalette palette, int bits, IBlockState air, byte[] blockIds,
            NibbleArray data) {
        int[] ids = IDS.get();
        Arrays.fill(ids, 0, 1 << bits, UNSET);
        NibbleArray add = null;
        int distinct = 0;
        for (int i = 0; i < 4096; ++i) {
            int v = storage.getAt(i);
            int id = ids[v];
            if (id == UNSET) {
                IBlockState state = palette.getBlockState(v);
                id = Block.BLOCK_STATE_IDS.get(state == null ? air : state);
                ids[v] = id;
                distinct++;
            }
            int x = i & 15;
            int y = i >> 8 & 15;
            int z = i >> 4 & 15;
            if ((id >> 12 & 15) != 0) {
                if (add == null) {
                    add = new NibbleArray();
                }
                add.set(x, y, z, id >> 12 & 15);
            }
            blockIds[i] = (byte) (id >> 4 & 255);
            data.set(x, y, z, id & 15);
        }
        lookupsSaved.addAndGet(4096 - distinct);
        return add;
    }

    /** Validation: at the end of the original, its bytes against ours (computed at the start, on the same section). */
    public static void checkOriginal(byte[] blockIds, NibbleArray data, NibbleArray add) {
        Pending p = PENDING.get();
        if (p == null) {
            return;
        }
        PENDING.remove();
        validated.incrementAndGet();
        boolean same = Arrays.equals(p.blockIds, blockIds) && Arrays.equals(p.data.getData(), data.getData())
                && (p.add == null ? add == null : add != null && Arrays.equals(p.add.getData(), add.getData()));
        ValidationLog.check("minecraft.saveSectionsByPalette", same, "the section's bytes differ from the original");
        if (!same && mismatches.incrementAndGet() <= 10) {
            WorldgenStatus.LOG.error("[gambiarra] VALIDATION minecraft.saveSectionsByPalette: the section's bytes differ from the original");
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sections", sections.get());
        m.put("globalPalette", fallback.get());
        m.put("lookupsSkipped", lookupsSaved.get());
        if (VALIDATE) {
            m.put("checked", validated.get());
            m.put("differences", mismatches.get());
        }
        return m;
    }

}
