// SPDX-License-Identifier: MIT
package nthrc;

import javax.annotation.Nullable;

/**
 * A mixin of the module and the optimization it belongs to. {@link #ok} only becomes true when the optimization was allowed AND its
 * injections were checked in the final class (HandlerCallAudit): each handler checks its own hook before acting, so nothing acts halfway.
 */
public enum Hook {

    /** A: marks the thread of the maze search (WorldScriptMazeGenerator). */
    MAZE_TASK("MazeTaskMixin", Opt.MAZE_STOP, 1),
    /** A: ends the abandoned search (IvToolkit MazeComponentConnector). */
    MAZE_CHECK("MazeSearchInterruptMixin", Opt.MAZE_STOP, 1),
    /** F1: refusal by limits/overlap before preparation (StructureGenerator.generateInternal). */
    RC_REJECT("RejectBeforePreparationMixin", Opt.RC_REJECT_EARLY, 1),
    /** V: the 9 cell evaluations of SpacedCellularNoise.eval2D. */
    RTG("SpacedCellularNoiseMixin", Opt.RTG_VORONOI, 9),
    /** G: starting height of getGroundFromAbove. */
    ADDITIONAL_STRUCTURES("GroundScanMixin", Opt.AS_GROUND, 1),
    /** Biomes O' Plenty fog: FogEventHandler.onRenderFog (client): the method's wrapper and its 2 renderFog calls. */
    BOP_FOG("FogScanMixin", Opt.BOP_FOG, 3),
    /** Phoenix Dive: the wrong removal in the second loop of onUpdate and the cleanup at the end of the method. */
    PHOENIX_DIVE("PhoenixDiveMixin", Opt.WE_PHOENIX_DIVE, 2),
    /** Scaling Health: the start of DifficultyHandler.isProcessed. */
    SH_PROCESS_ONCE("ProcessOnceMixin", Opt.SH_PROCESS_ONCE, 1),
    /** SRP: the wrapper of SRPSaveData.getDisloValues. */
    SRP_DISLO("DisloCacheMixin", Opt.SRP_DISLO_CACHE, 1),
    /** Forgotten Items: the 2 Set.toArray calls of FIHelper.getForbiddenList and the end of the method. */
    FI_ONE_PASS("ForbiddenListMixin", Opt.FI_ONE_PASS, 3),
    /** Ice and Fire: the start and end of each data set's saveNBTData. */
    IAF_MISC("MiscPropertiesMixin", Opt.IAF_PROPERTIES_UNCHANGED, 2),
    IAF_FROZEN("FrozenPropertiesMixin", Opt.IAF_PROPERTIES_UNCHANGED, 2),
    IAF_CHAIN("ChainPropertiesMixin", Opt.IAF_PROPERTIES_UNCHANGED, 2),
    IAF_SIREN("SirenPropertiesMixin", Opt.IAF_PROPERTIES_UNCHANGED, 2),
    IAF_STONE("StonePropertiesMixin", Opt.IAF_PROPERTIES_UNCHANGED, 2),
    /** Stronghold: the start of MapGenStronghold.getNearestStructurePos. */
    STRONGHOLD_WORLD("StrongholdWorldMixin", Opt.STRONGHOLD_WORLD, 1),

    // Gambiarra 0.4.0.

    /** Pulsar: the start of the resend test (WorldLightManager.lambda$retryInitialChunkSends$1). */
    PULSAR_RETRY("RetryOrderMixin", Opt.PULSAR_RETRY_ORDER, 1, false),
    /** Biomes O' Plenty fog: the biome and fog colour reads of FogEventHandler.getFogBlendColour (client). */
    BOP_FOG_COLOUR("FogColourMixin", Opt.BOP_FOG_COLOUR, 2, false),
    /** Saving the section by palette: the start and end of BlockStateContainer.getDataForNBT. */
    SECTION_NBT("SectionNbtMixin", Opt.CHUNK_NBT_PALETTE, 2, true),
    /**
     * Resting items: the start of EntityItem.onUpdate, the pushOutOfBlocks and the move inside it, and the start and end of
     * EntityItem.handleWaterMovement (a single return).
     */
    ITEM_REST("ItemRestMixin", Opt.ITEM_REST, 5, true),
    /** Resting items: the write count of each section (end of BlockStateContainer.set). */
    ITEM_REST_SECTIONS("SectionVersionMixin", Opt.ITEM_REST, 1, true),
    /** Resting items: Entity's fire accessor (no injection; it is where the Entity.move fingerprint is checked). */
    ITEM_REST_FIELDS("EntityFieldsMixin", Opt.ITEM_REST, 0, true),
    /** RC structures file: the wrapper of MapStorage.saveData. */
    RC_DATA("MapStorageSaveMixin", Opt.RC_DATA_OFF_THREAD, 1, true),
    /** Xaero: the flushToDisk of WorldDataReader$3.run (on the server thread). */
    XAERO_FLUSH_RUN("FlushRunMixin", Opt.XAERO_FLUSH_OFF_THREAD, 1, false),
    /** Xaero: the wait for the disk queue at the end of WorldDataReader.saveUnsavedChunks (on Xaero's thread). */
    XAERO_FLUSH_WAIT("FlushWaitMixin", Opt.XAERO_FLUSH_OFF_THREAD, 1, false),
    /** session.lock: the start and end of SaveHandler.checkSessionLock. */
    SESSION_LOCK("SessionLockMixin", Opt.SESSION_LOCK, 2, true),
    /** Ores: the wrapper of WorldGenMinable.generate. */
    ORE_VEIN("OreVeinMixin", Opt.ORE_VEIN, 1, true),
    /** The start of the 6 most used accessors of ReflectionField (RenderLib and RLTweaker). */
    REFLECTION_FIELD("ReflectionFieldMixin", Opt.REFLECTION_FIELD, 6, true),
    /** Xaero: the start of BiomeColorCalculator.resolve. */
    XAERO_BIOME_COLOUR("BiomeColourMixin", Opt.XAERO_BIOME_COLOUR, 1, false),
    /** Xaero: the start of MapLimiter.updateAvailableVRAM. */
    XAERO_VRAM("VramQueryMixin", Opt.XAERO_VRAM, 1, false),
    /** Fluid: the 6 withProperty calls of BlockFluidBase.getExtendedState. */
    FLUID_CORNER("FluidCornerMixin", Opt.FLUID_CORNER, 6, true);

    /** Simple name of the mixin; also the piece of the handler names (nthrc$&lt;Mixin&gt;$...). */
    public final String mixin;
    public final Opt opt;
    /** Injections the mixin leaves in the target class. */
    final int expected;
    /**
     * Mixin of the "vanilla" group (mixins.nthrc.vanilla.json, VanillaMixinPlugin), applied before the optimization is decided: ok only says the
     * injections applied, and the handler checks {@link #active()}.
     */
    final boolean early;

    /** Allowed and checked in the final class. Read by the handlers. */
    public volatile boolean ok;

    Hook(String mixin, Opt opt, int expected) {
        this(mixin, opt, expected, false);
    }

    Hook(String mixin, Opt opt, int expected, boolean early) {
        this.mixin = mixin;
        this.opt = opt;
        this.expected = expected;
        this.early = early;
    }

    /** The injections applied, the optimization was allowed and the fingerprints of the game's classes matched. */
    public boolean active() {
        return ok && opt.active();
    }

    /** All mixins of this optimization are active (an optimization with several mixins only acts whole). */
    public static boolean allActive(Opt o) {
        for (Hook h : values()) {
            if (h.opt == o && !h.active()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    static Hook ofMixin(String simpleName) {
        for (Hook h : values()) {
            if (h.mixin.equals(simpleName)) {
                return h;
            }
        }
        return null;
    }

}
