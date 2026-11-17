// SPDX-License-Identifier: MIT
package nthrc;

import java.io.IOException;

import javax.annotation.Nullable;

import gambiarra.common.JarUtil;

/**
 * A target mod of the server module: its mixin group (mixins.nthrc.&lt;id&gt;.json), its section in gambiarra-worldgen.cfg and the versions it
 * must have. Each group turns on and off by itself: a mod absent or of another version only turns off its own optimizations.
 *
 * Versions read from the mcmod.info of the jar holding the marker class, without loading classes of the mod.
 */
public enum Group {

    RECURRENT_COMPLEX("recurrentcomplex", "Recurrent Complex Volts", "rc",
            new Requirement("Recurrent Complex Volts", "ivorius/reccomplex/RecurrentComplex.class", "reccomplex", "2.0.1.2", "2.0.1.4"),
            new Requirement("IvToolkit", "ivorius/ivtoolkit/IvToolkit.class", "IvToolkit", "1.3.3-1.12")),

    // Pulsar left in addon 0.5.0: Pulsar 0.5.0 mixes the light map key (mixChunkKey) and skips the 6 neighbours when there is no contextual
    // light source, which solves the expensive lookup its optimization attacked. It came back in Gambiarra 0.4.0 with another optimization
    // (the resend of deferred chunks, see Opt.PULSAR_RETRY_ORDER); the old section of the config (rememberLastChunk) is still deleted by the
    // migration.

    PULSAR("pulsar", "Pulsar", "pulsar",
            new Requirement("Pulsar", "com/sumirelabs/pulsar/light/WorldLightManager.class", "pulsar", "0.6.2", "0.6.4-beta")),

    RTG("rtg", "RTG Continued", "rtg",
            new Requirement("RTG Continued", "rtg/api/util/noise/SpacedCellularNoise.class", "rtgc", "1.0.7")),

    ADDITIONAL_STRUCTURES("additionalstructures", "Additional Structures", "addstructures",
            new Requirement("Additional Structures", "xxrexraptorxx/additionalstructures/world/StructureGenerator.class",
                    "additionalstructures", "2.5.0")),

    /** Client side (fog): the mixin group only has a "client" list. */
    BIOMES_O_PLENTY("biomesoplenty", "Biomes O' Plenty", "bop",
            new Requirement("Biomes O' Plenty", "biomesoplenty/core/BiomesOPlenty.class", "biomesoplenty", "7.0.1")),

    /** Crash fix (not a worldgen one): Phoenix Dive brings the server down. */
    WONDERFUL_ENCHANTMENTS("wonderfulenchantments", "Wonderful Enchantments", "wenchants",
            new Requirement("Wonderful Enchantments", "com/wonderfulenchantments/WonderfulEnchantments.class",
                    "wonderful_enchantments", "0.6.0")),

    // Gambiarra 0.3.0: the first 5 of docs/analise-mods.md (server tick and a crash).

    SCALING_HEALTH("scalinghealth", "Scaling Health", "scalinghealth",
            new Requirement("Scaling Health", "net/silentchaos512/scalinghealth/ScalingHealth.class", "scalinghealth", "1.3.42")),

    SRPARASITES("srparasites", "Scape and Run Parasites", "srparasites",
            new Requirement("Scape and Run Parasites", "com/dhanantry/scapeandrunparasites/SRPMain.class", "srparasites", "1.10.9")),

    FORGOTTEN_ITEMS("forgottenitems", "Forgotten Items", "forgottenitems",
            new Requirement("Forgotten Items", "tschipp/forgottenitems/FIM.class", "forgottenitems", "1.3.1.4")),

    /** The properties Ice and Fire puts on every mob, synced by LLibrary. */
    ICE_AND_FIRE("iceandfire", "Ice and Fire", "iceandfire",
            new Requirement("Ice and Fire", "com/github/alexthe666/iceandfire/IceAndFire.class", "iceandfire", "1.9.1"),
            new Requirement("LLibrary", "net/ilexiconn/llibrary/LLibrary.class", "llibrary", "1.7.20-1.12.2")),

    /**
     * The game itself (no version to check: the mixin only applies if the target method exists, and the injection is counted). The
     * optimizations in classes the game loads early (EntityItem, BlockStateContainer) stay in the "vanilla" mixin group (VanillaMixinPlugin),
     * in the same config section.
     */
    MINECRAFT("minecraft", "Minecraft", "minecraft",
            new Requirement("Minecraft", "net/minecraft/world/gen/structure/MapGenStronghold.class", null)),

    /** Client side (the map's save reader only exists in single-player): the mixin group only has a "client" list. */
    XAERO_WORLD_MAP("xaeroworldmap", "Xaero's World Map", "xaero",
            new Requirement("Xaero's World Map", "xaero/map/WorldMap.class", "xaeroworldmap", "1.47.0"));

    /**
     * A required version: the jar is found by the marker class and the version comes from its mcmod.info. More than one version is accepted
     * only when the classes the optimizations touch are the same (Opt's fingerprints match).
     */
    public static final class Requirement {
        final String label;
        final String marker;
        final String modid;
        final java.util.List<String> versions;

        Requirement(String label, String marker, String modid, String... versions) {
            this.label = label;
            this.marker = marker;
            this.modid = modid;
            this.versions = java.util.Arrays.asList(versions);
        }

        /** null modid = the game itself: there is no mcmod.info, and the game's version is always 1.12.2. */
        boolean accepts(@Nullable String found) {
            return modid == null || versions.contains(found);
        }

        /** "0.3.2" or "0.3.2 or 0.4.0", for the log. */
        String expected() {
            return modid == null ? VANILLA : String.join(" or ", versions);
        }

        /** The game itself is always present (its classes do not come from a mod jar JarUtil can find). */
        boolean present() {
            return modid == null || JarUtil.jarOf(marker) != null;
        }

        @Nullable
        String found() throws IOException {
            return modid == null ? VANILLA : JarUtil.modVersion(marker, modid);
        }

        private static final String VANILLA = "1.12.2 (the game)";
    }

    /** Section in the config and short name in the log. */
    public final String id;
    public final String label;
    /** Mixin group suffix (mixins.nthrc.&lt;suffix&gt;.json, package nthrc.mixin.&lt;suffix&gt;). */
    public final String suffix;
    final Requirement[] requirements;

    Group(String id, String label, String suffix, Requirement... requirements) {
        this.id = id;
        this.label = label;
        this.suffix = suffix;
        this.requirements = requirements;
    }

    public String mixinConfig() {
        return "mixins.nthrc." + suffix + ".json";
    }

    /** The target mod's packages (internal name, with "/"): our classes' links to them are checked (CodeLock). */
    String[] packages() {
        switch (this) {
            case RECURRENT_COMPLEX:
                return new String[] { "ivorius/reccomplex/", "ivorius/ivtoolkit/" };
            case RTG:
                return new String[] { "rtg/" };
            case ADDITIONAL_STRUCTURES:
                return new String[] { "xxrexraptorxx/additionalstructures/" };
            case BIOMES_O_PLENTY:
                return new String[] { "biomesoplenty/" };
            case WONDERFUL_ENCHANTMENTS:
                return new String[] { "com/wonderfulenchantments/" };
            case SCALING_HEALTH:
                return new String[] { "net/silentchaos512/scalinghealth/" };
            case SRPARASITES:
                return new String[] { "com/dhanantry/scapeandrunparasites/" };
            case FORGOTTEN_ITEMS:
                return new String[] { "tschipp/forgottenitems/" };
            case ICE_AND_FIRE:
                return new String[] { "com/github/alexthe666/iceandfire/", "net/ilexiconn/llibrary/" };
            case PULSAR:
                return new String[] { "com/sumirelabs/pulsar/" };
            case XAERO_WORLD_MAP:
                return new String[] { "xaero/map/" };
            default:
                return new String[0];
        }
    }

    /** The group's main mod (the first requirement) is on the classpath. */
    boolean present() {
        return requirements[0].present();
    }

    @Nullable
    static Group ofMixinPackage(String mixinPackage) {
        for (Group g : values()) {
            if (mixinPackage.equals("nthrc.mixin." + g.suffix)) {
                return g;
            }
        }
        return null;
    }

}
