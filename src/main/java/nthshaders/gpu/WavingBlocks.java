// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.lang.reflect.Field;
import java.util.BitSet;

import net.optifine.shaders.BlockAlias;
import net.optifine.shaders.BlockAliases;

/**
 * With shaders: the mc_Entity ids that go to the ANY group in per-direction culling (FaceSorter).
 *
 * A pack can move a block's vertices in the vertex shader (leaves and plants in the wind, water, lava), choosing by
 * mc_Entity.x. OptiFine writes there the id from the pack's block.properties when the block is in it (BlockAliases); otherwise,
 * the block's numeric id. These go in:
 *  - all ids from the pack's block.properties (and from mods' block.properties): the addon does not know which the pack moves;
 *  - the vanilla numeric ids that old packs move without block.properties (leaves, plants, vines, lily pads, crops, lava).
 * More blocks in ANY only draws more faces; never fewer.
 *
 * Rebuilt by itself when OptiFine swaps the block.properties (pack change, F3+T): its list becomes another object.
 */
final class WavingBlocks {

    private static final int[] LEGACY = {
            6, 10, 11, 18, 30, 31, 32, 37, 38, 39, 40, 59, 83, 104, 105, 106, 111, 115, 141, 142, 161, 175, 207 };

    private static final Object NOT_READ = new Object();
    private static volatile Field aliasesField;
    private static volatile boolean fieldTried;
    private static volatile Object lastAliases = NOT_READ;
    private static volatile BitSet ids;
    private static volatile int aliasCount;

    private WavingBlocks() {
    }

    static BitSet ids() {
        Object current = aliases();
        BitSet set = ids;
        if (set != null && current == lastAliases) {
            return set;
        }
        synchronized (WavingBlocks.class) {
            if (ids != null && current == lastAliases) {
                return ids;
            }
            BitSet next = new BitSet(65536);
            for (int id : LEGACY) {
                next.set(id);
            }
            int n = 0;
            if (current instanceof BlockAlias[][]) {
                for (BlockAlias[] list : (BlockAlias[][]) current) {
                    if (list == null) {
                        continue;
                    }
                    for (BlockAlias a : list) {
                        if (a != null) {
                            next.set(a.getBlockAliasId() & 0xFFFF);
                            n++;
                        }
                    }
                }
            }
            aliasCount = n;
            lastAliases = current;
            ids = next;
            return next;
        }
    }

    private static Object aliases() {
        if (!fieldTried) {
            synchronized (WavingBlocks.class) {
                if (!fieldTried) {
                    try {
                        Field f = BlockAliases.class.getDeclaredField("blockAliases");
                        f.setAccessible(true);
                        aliasesField = f;
                    } catch (Throwable t) {
                        aliasesField = null;
                    }
                    fieldTried = true;
                }
            }
        }
        Field f = aliasesField;
        if (f == null) {
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static String describe() {
        BitSet set = ids;
        if (set == null) {
            return "not read yet";
        }
        return set.cardinality() + " ids (" + aliasCount + " block.properties entries" + (aliasesField == null ? ", OptiFine list unreadable" : "")
                + ")";
    }

}
