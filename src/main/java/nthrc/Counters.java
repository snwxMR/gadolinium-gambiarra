// SPDX-License-Identifier: MIT
package nthrc;

import java.util.Collections;
import java.util.Map;

import nthrc.addstructures.GroundScan;
import nthrc.bop.FogScanCache;
import nthrc.rc.EarlyReject;
import nthrc.rc.MazeSearchGuard;
import nthrc.rtg.VoronoiCells;
import nthrc.wenchants.PhoenixDiveFix;

/**
 * Counters of each optimization (what it did in the session). Each optimization class uses classes of the target mod, so it is only
 * touched here if the optimization was allowed (WorldgenStatus.describe).
 */
final class Counters {

    private Counters() {
    }

    /** These classes touch client classes: on a dedicated server, do not even load them. */
    private static boolean clientOnly(Opt o) {
        return o == Opt.BOP_FOG || o == Opt.BOP_FOG_COLOUR || o == Opt.XAERO_FLUSH_OFF_THREAD || o == Opt.XAERO_BIOME_COLOUR || o == Opt.XAERO_VRAM;
    }

    static Map<String, Object> of(Opt o) {
        if (gambiarra.common.LaunchSide.isDedicatedServer() && clientOnly(o)) {
            return Collections.emptyMap();
        }
        switch (o) {
            case MAZE_STOP:
                return MazeSearchGuard.describe();
            case RC_REJECT_EARLY:
                return EarlyReject.describe();
            case RTG_VORONOI:
                return VoronoiCells.describe();
            case AS_GROUND:
                return GroundScan.describe();
            case BOP_FOG:
                return FogScanCache.describe();
            case WE_PHOENIX_DIVE:
                return PhoenixDiveFix.describe();
            case SH_PROCESS_ONCE:
                return nthrc.scalinghealth.ProcessOnce.describe();
            case SRP_DISLO_CACHE:
                return nthrc.srparasites.DisloCache.describe();
            case FI_ONE_PASS:
                return nthrc.forgottenitems.ForbiddenListOnePass.describe();
            case IAF_PROPERTIES_UNCHANGED:
                return nthrc.iceandfire.PropertiesSkip.describe();
            case STRONGHOLD_WORLD:
                return nthrc.minecraft.StrongholdWorld.describe();
            case PULSAR_RETRY_ORDER:
                return nthrc.pulsar.RetryOrder.describe();
            case BOP_FOG_COLOUR:
                return nthrc.bop.FogColourCache.describe();
            case CHUNK_NBT_PALETTE:
                return nthrc.minecraft.SectionNbt.describe();
            case ITEM_REST:
                return nthrc.minecraft.ItemRest.describe();
            case RC_DATA_OFF_THREAD:
                return nthrc.rc.StructureDataWriter.describe();
            case XAERO_FLUSH_OFF_THREAD:
                return nthrc.xaero.FlushOffThread.describe();
            case SESSION_LOCK:
                return nthrc.minecraft.SessionLock.describe();
            case ORE_VEIN:
                return nthrc.minecraft.OreVein.describe();
            case REFLECTION_FIELD:
                return nthrc.minecraft.FieldHandles.describe();
            case FLUID_CORNER:
                return nthrc.minecraft.FluidCorner.describe();
            default:
                return Collections.emptyMap();
        }
    }

}
