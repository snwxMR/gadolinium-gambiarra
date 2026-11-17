// SPDX-License-Identifier: MIT
package nthrc;

/**
 * An optimization of the module: its option in gambiarra-worldgen.cfg (in the group's section) and the fingerprint (SHA-256) of the exact bytes of the classes it touches or whose behaviour
 * it depends on. A different build of the mod, even with the same version number, only turns this optimization off.
 */
public enum Opt {

    MAZE_STOP(Group.RECURRENT_COMPLEX, "stopMazeSearchAfterTimeout",
            "Recurrent Complex builds each maze on a separate thread and gives up on it at mazeTimeout "
                    + "(reccomplex.cfg), but the build does not stop: it keeps wasting a CPU core, sometimes for minutes. This "
                    + "option makes the build stop when Recurrent Complex gives up. It does not change any maze: it only acts "
                    + "after the result has already been discarded.",
            new String[][] {
                    // 2.0.1.2: the whole class; 2.0.1.3 and 2.0.1.4: the submission with mazeTimeout moved to solveRooms.
                    {"ivorius/reccomplex/world/gen/script/WorldScriptMazeGenerator.class",
                            "86ab6a1f25260e1c6cda27b4fc5ddfa9c66f862e0326a5144e23a18c1152943e",
                            "m:solveRooms=7429efcfb2889a921bfe6fcc9c7cc3f1be303dc97e7c245790e3ac1514e33bc7"},
                    {"ivorius/ivtoolkit/maze/components/MazeComponentConnector.class",
                            "02972d7236edafddfa58a9add0bdd712f5daf661b60cddc8fb9ee33cd784fbe3",
                            "m:connectMulti=3238b454909b2026db2d7f89b99dea37ba8ba8c941a3df776a7a2ebb1da96394"},
            }),

    RC_REJECT_EARLY(Group.RECURRENT_COMPLEX, "rejectBeforePreparation",
            "Recurrent Complex prepares each structure (ruins, natural air, mazes) and only then checks whether it "
                    + "fits in the world height and does not overlap another already generated; a large structure can spend "
                    + "seconds preparing only to be refused right after. This option does those two checks before preparing. The "
                    + "structure refused is the same, with the same failure; it just stops preparing for nothing.",
            new String[][] {
                    {"ivorius/reccomplex/world/gen/feature/StructureGenerator.class",
                            "0368a4edfc214a93d4c261a6399a3e1a8103d9428948dbe91ca8cc7b5be62793",
                            "m:generateInternal=d255030aae36d35a1ea0adfcee13ea082bce06b439ebabe045d609c49d3cf2ce"},
            }),

    RTG_VORONOI(Group.RTG, "skipUselessVoronoiCells",
            "For each terrain column, RTG evaluates the Voronoi noise in the 9 cells around, 25 points each; the test "
                    + "that should skip distant ones compares a plain distance with a squared one and never skips. This option "
                    + "skips the cells that cannot change the result. The noise comes out identical, bit for bit; the terrain "
                    + "does not change.",
            new String[][] {
                    {"rtg/api/util/noise/SpacedCellularNoise.class",
                            "52778111f568533e095e1bc3b488768b39496421deb67c04ded27732d291eade"},
                    {"rtg/api/util/noise/VoronoiResult.class",
                            "8ce3024cb46e33483458e9350f349bc33071a9f6b3807d4d15a511609af6825d"},
            }),

    AS_GROUND(Group.ADDITIONAL_STRUCTURES, "groundScanFromTopSection",
            "To find the ground, Additional Structures goes down block by block from the top of the world (y=256), "
                    + "about a million times per 64x64-chunk region; almost all of it is air above the terrain. This option "
                    + "starts right above the chunk's highest section that has blocks: above it everything is air, so the ground "
                    + "found is the same.",
            new String[][] {
                    {"xxrexraptorxx/additionalstructures/world/StructureGenerator.class",
                            "f942fd05f245eae9889c29056d89c52d933da18703b08c9a5810201f43e33802"},
            }),

    BOP_FOG(Group.BIOMES_O_PLENTY, "fogScanOncePerTick",
            "Client. On each fog event (several per frame with shaders), Biomes O' Plenty queries the biome of 41x41 "
                    + "blocks around the player to compute the fog distance. It has a per-block cache, but it never hits (it "
                    + "compares the rounded position with the unrounded one). This option stores the computed distance until the "
                    + "next tick, for the same block and the same render distance; the world only changes from one tick to the "
                    + "next, so the fog comes out the same.",
            new String[][] {
                    {"biomesoplenty/common/handler/FogEventHandler.class",
                            "91ab17b846adc07dcfa825c6a017094070f8dad923354dee3f3920924ad61eb9"},
            }),

    WE_PHOENIX_DIVE(Group.WONDERFUL_ENCHANTMENTS, "fixPhoenixDiveCrash",
            "Crash fix. Wonderful Enchantments keeps the entities with Phoenix Dive boots to emit particles at their "
                    + "feet and, when clearing those that disappeared, deletes by the wrong value in the middle of the scan: the "
                    + "server crashes with a ConcurrentModificationException (PhoenixDiveEnchantment.onUpdate), more easily when "
                    + "flying fast. This option defers the cleanup until after the scan and only deletes the entry of an entity "
                    + "that does not exist in any loaded world. The particles come out the same; the enchantment's fall damage "
                    + "and jump do not pass through here.",
            new String[][] {
                    {"com/wonderfulenchantments/enchantments/PhoenixDiveEnchantment.class",
                            "53c0209cecbb4a976dcb5ce93ae032de48efecb02a7639df0e26c87c6be477da"},
            }),

    SH_PROCESS_ONCE(Group.SCALING_HEALTH, "processMobsOnce",
            "Scaling Health adjusts each mob's health and damage once, at spawn, and marks the mob with the difficulty "
                    + "used. But it only counts as marked if the difficulty is 1 or more: with difficulty below 1 (in this pack, "
                    + "always 0), it redoes the whole math every tick for each mob (area average, new health and damage "
                    + "modifiers). This option counts the mob as marked when the mark exists, with any value. With difficulty at "
                    + "0, the redone math always gives the same result, so nothing changes in the game; with difficulty between "
                    + "0 and 1, the mob's health stops being re-rolled every tick (which is what the mod intends).",
            new String[][] {
                    {"net/silentchaos512/scalinghealth/event/DifficultyHandler.class",
                            "508d023f1d82a124ec296e017d8ab62e3170b40548cb070c579284361e40f7cc"},
            }),

    SRP_DISLO_CACHE(Group.SRPARASITES, "cacheDisloValues",
            "On each attempt of Scape and Run Parasites' own spawner (several per tick), getDisloValues splits the "
                    + "dimension's code text into parts and converts each to a number. This option stores the result together "
                    + "with the text it came from; while the text is the same, it returns a copy of the result. SRP replaces the "
                    + "text when it changes the codes, and then the math is redone. Same result.",
            new String[][] {
                    {"com/dhanantry/scapeandrunparasites/world/SRPSaveData.class",
                            "c82c7cf413dc20a8f1ce6b4fecedb3b8d79e94dca9b5aff64d7b9a7fbf8370f4"},
            }),

    FI_ONE_PASS(Group.FORGOTTEN_ITEMS, "forbiddenListOnePass",
            "The first time Forgotten Items queries the banned item list (right when opening the world), it builds it "
                    + "by copying the whole item registry to a new array twice per item, for each entry with * in the list "
                    + "(O(N^2)): the server freezes for 1 to 2 s. This option reuses the same array during the build. The same "
                    + "list, in the same order.",
            new String[][] {
                    {"tschipp/forgottenitems/util/FIHelper.class",
                            "871349562a3e13eda71a244dc2bd49c2434003e887730637c2cfba804512c282"},
            }),

    IAF_PROPERTIES_UNCHANGED(Group.ICE_AND_FIRE, "skipUnchangedProperties",
            "Ice and Fire keeps five data sets on every living mob (frozen, chained, stone, charmed by the siren and "
                    + "others), and LLibrary writes them to an NBT every tick, on every mob, just to find out whether something "
                    + "changed and send it to the client. This option skips the write when no value has changed since the last "
                    + "write to that same NBT: it would give the same values and mark no change at all. What goes to the client "
                    + "and to the world save does not change.",
            new String[][] {
                    {"com/github/alexthe666/iceandfire/entity/MiscEntityProperties.class",
                            "9c0dffd55f8a38de2fddd7013f6089dfa186f168629a51f9b1086f6bd8ac7d04"},
                    {"com/github/alexthe666/iceandfire/entity/FrozenEntityProperties.class",
                            "cc3d3a88788ed419e0150fab429f086cbe391b182fe614a6abe2cbb8c0a84f06"},
                    {"com/github/alexthe666/iceandfire/entity/ChainEntityProperties.class",
                            "7f47c7472ca7fc244d59c736202eda583fa0885666c36d9bef1887ea48d43c56"},
                    {"com/github/alexthe666/iceandfire/entity/SirenEntityProperties.class",
                            "b49fa70fae0f4c9bf7e08d3fa0ee2bb0f5fa25cd87c1b1b3f6f3f9363f661784"},
                    {"com/github/alexthe666/iceandfire/entity/StoneEntityProperties.class",
                            "aff374684d3e600e581ca733424e253fb276170c74e34c0feeb1fbe8a22fab9a"},
                    {"net/ilexiconn/llibrary/server/entity/PropertiesTracker.class",
                            "1ff98ce8bed94e1047727565499dce81cfd7608abae1517b16d35e85725774f6"},
                    {"net/ilexiconn/llibrary/server/entity/SensitiveTagCompound.class",
                            "9aa132cbbe46d4f2332a37e13d4e4070d55506dbb4b00cf315bd536f6d542bd6"},
                    {"net/ilexiconn/llibrary/server/entity/EntityProperties.class",
                            "6c3319c0885991e11ad0ca76baebf1288c9b4d665878df6685cb1d6254c7ee14"},
            }),

    STRONGHOLD_WORLD(Group.MINECRAFT, "fixStrongholdLocateCrash",
            "Crash fix. Looking for the nearest stronghold (eye of ender, /locate, Charm's SpectreHaunting) calls "
                    + "MapGenStronghold.getNearestStructurePos, which uses the generator's world without filling it: the game "
                    + "only fills it when generating a chunk. With Recurrent Complex wrapping the generator, the lookup can "
                    + "arrive before that and the server crashes (NullPointerException in generatePositions). This option fills "
                    + "the world with the one the lookup received, when it is still empty. The positions come out the same (they "
                    + "come from the world seed).",
            new String[0][]),

    // Gambiarra 0.4.0: the first 5 of the 04/11 analysis (Flare recordings; docs/analise-mods.md, entries 24-29 and 33).

    PULSAR_RETRY_ORDER(Group.PULSAR, "retryNeighboursFirst",
            "Every tick, Pulsar rechecks all chunks whose first send to the player is waiting for light: for each, it "
                    + "queries 9 chunks around in 5 maps (three of them with a lock, contended with the light threads) and only "
                    + "then checks whether the 4 neighbours already have light. Chunks at the edge of the view distance are "
                    + "always waiting and pay the expensive query every tick. This option does the cheap neighbour test first; "
                    + "both tests only read, and the chunk goes out on the same tick it would have.",
            new String[][] {
                    {"com/sumirelabs/pulsar/light/WorldLightManager.class",
                            "52a25fc8e40d5398fc565e1f40ed1cae8d23b9d855e3aa6c7dcff12f90351393",
                            "m:lambda$retryInitialChunkSends$1=971caf7f4ef82da91c4041b9215fdd6eef00b02c42d07fc14af86702d757399c,"
                                    + "areNeighboursLightReady=1e8be32bfa3be5551998201beb0c84dff5d51c4394c3548f4dc92b1df8af102d,"
                                    + "canSendUpdatedChunkLight=2b70cdc0b4a0f5e8aa399051da9caa34329017dfdd5c2757fdf7302171e2d879,"
                                    + "hasPendingLightWorkNear=f1fb6f14ca9978d2436d5b8c7a407b453625e49b0f5a26205ee542dea01c4cbb"},
            }),

    BOP_FOG_COLOUR(Group.BIOMES_O_PLENTY, "fogColourOncePerTick",
            "Client. Every frame (more than once with shaders), Biomes O' Plenty computes the fog colour by adding up "
                    + "the colour of the biomes of up to 69x69 blocks around the player. This option keeps each column's biome "
                    + "and fog colour until the next tick; Biomes O' Plenty still does the sum and the mix with the time of day "
                    + "every frame. Biomes only change from one tick to the next, so the colour comes out the same. Independent of "
                    + "fogScanOncePerTick (the distance).",
            new String[][] {
                    {"biomesoplenty/common/handler/FogEventHandler.class",
                            "91ab17b846adc07dcfa825c6a017094070f8dad923354dee3f3920924ad61eb9",
                            "m:getFogBlendColour=0664f1854bea898c75eadd535a8bdd5f6fa0d9fc5beecdfb75e6836a7dd48028"},
            }),

    CHUNK_NBT_PALETTE(Group.MINECRAFT, "saveSectionsByPalette",
            "When saving a chunk, the game looks up the number of each of the 4096 blocks of each section in a map "
                    + "with all the pack's block states (on autosave, on unload and on Xaero's map flush). The section already "
                    + "keeps a palette with few states; this option looks the number up once per palette state and builds the "
                    + "section by index. The same bytes in the region file.",
            new String[0][],
            new String[][] {
                    {"net.minecraft.world.chunk.BlockStateContainer", "func_186017_a=f3ca48a067350c94d8be80298c8d409ebe9ae5cd0787dc888037a412d8d5122a,"
                            + "func_186015_a=3cbc980cb8a5bf6f4283abb3c0ba9840589e0cfc2555488f97ce1da50654d5c8"},
            }),

    ITEM_REST(Group.MINECRAFT, "restingItemsSkipCollision",
            "Every item on the ground redoes the collision with blocks, the water check and the check for leaving the "
                    + "inside of a block every tick, even when still. This option remembers the last tick's result for a still "
                    + "item and repeats it while nothing has changed: the item in the same place and with the same velocity, no "
                    + "block changed in the sections around it, no tile entity or hard-box entity (boat, shulker, minecart) "
                    + "nearby, the world border still and far away. Otherwise, the tick runs whole. The item's position, "
                    + "velocity and state come out the same, tick by tick.",
            new String[0][],
            new String[][] {
                    {"net.minecraft.entity.item.EntityItem", "func_70071_h_=85423f9dc12de389e8138d5a78a279ea5f1e6613f85b5ae2ea3bae25079e1469,"
                            + "func_70072_I=9d60d8b2eeae08f2efe44a0e00aed5bbf0eb49593424be63c9fa923837b722e2"},
                    {"net.minecraft.entity.Entity", "func_70091_d=39d6c070207370b4e5f9336f161c3631f5ac79daf60201e1306c5d6296a5a94c,"
                            + "func_145771_j=e6392488885586f9564bcd595ecb525c9e153dde220783928071878e116d3fc7"},
                    {"net.minecraft.world.chunk.BlockStateContainer", "func_186014_b=29858169bdd82cec84e059cd4278b118708cddeede05ba101a98ce741db1d47d"},
            }),

    RC_DATA_OFF_THREAD(Group.RECURRENT_COMPLEX, "saveStructureDataOffThread",
            "Recurrent Complex keeps in a single file (data/reccomplex-structuredata.dat) each generated structure and "
                    + "each chunk already checked; the file only grows with exploration, and the game rewrites it whole, "
                    + "compressed, on the server thread at every autosave (hundreds of ms in an explored world). This option "
                    + "copies the data on the server thread and leaves the compression and writing to the game's disk thread "
                    + "(the same as for chunks), to a temporary file swapped for the final one at the end. When closing the "
                    + "world, the game waits for that thread to finish. The same bytes in the file.",
            new String[][] {
                    {"ivorius/reccomplex/world/gen/feature/WorldStructureGenerationData.class",
                            "68cdd46094e52022ac07014a8538fa67cd15b6666161bb2060fba0108eb2dca8",
                            "m:func_189551_b=bc73b9d3a583a7158d29b2406f90b3c7c73825d9ac1bb8bf54b017f4cf03bbaa"},
            },
            new String[][] {
                    {"net.minecraft.world.storage.MapStorage", "func_75747_a=8ae308a469d5f13ed209dc9c87513dc1ca56648064b0a2a9795f66cb451a6eba"},
            }),

    XAERO_FLUSH_OFF_THREAD(Group.XAERO_WORLD_MAP, "flushChunksOffServerThread",
            "Client (single-player). To read the save's map, Xaero's World Map makes the server thread save the "
                    + "changed chunks and write and compress right there the whole queue of chunks to save (hundreds of ms at "
                    + "once). This option leaves the writing to the game's disk thread and makes Xaero's own thread wait for the "
                    + "queue to finish before reading. The same chunks in the files before the read, as today.",
            new String[][] {
                    {"xaero/map/file/worldsave/WorldDataReader$3.class",
                            "96f9fada2a586bcc695b3a776f2778b617b6863a624403610159ab05c70eb53f",
                            "m:run=c72341f19ff6d6a6e8e5f1e2cf006407f178cf057744cb984996e501a1f802a7"},
                    {"xaero/map/file/worldsave/WorldDataReader.class",
                            "1e87a2d5b4aa8898184ff25c71690726785c7e247b5f2419d36fd696b342e630",
                            "m:saveUnsavedChunks=66d681991d685883308e858c646e4aec2ea1306a833fc74252a161fa5503dfdc"},
            }),

    // 1.0.0-rc1: Stage 2 of the 09/11 request.

    SESSION_LOCK(Group.MINECRAFT, "sessionLockOncePerSecond",
            "For every chunk saved, the game opens and reads session.lock to see whether another process has opened "
                    + "the same world. This option makes that check hold for 1 second on the same save: in an autosave, hundreds "
                    + "of file opens become one. If another process takes the world, the warning comes up to 1 second later.",
            new String[0][],
            new String[][] {
                    {"net.minecraft.world.storage.SaveHandler", "func_75762_c=e63dfc99dd1485d5d397e62de67e5444400e9e3ce02b69738319030dc94135b0"},
            }),

    ORE_VEIN(Group.MINECRAFT, "oreVeinChunkCache",
            "Ore generation (most of each chunk's decoration) looks the chunk up in the chunk map to read each block "
                    + "of the vein. This option keeps the last chunk looked up while the position stays in it. The same vein: "
                    + "the same math, the same random draws in the same order, the same block writes.",
            new String[0][],
            new String[][] {
                    {"net.minecraft.world.gen.feature.WorldGenMinable", "func_180709_b=a3a2856e5590706e422c2d86d344c32f0c15db61ef34cd8c3bd14bd4a396c7f8"},
            }),

    REFLECTION_FIELD(Group.MINECRAFT, "reflectionFieldHandles",
            "Client and server. RenderLib and RLTweaker read and write fields of other classes through reflection, "
                    + "every frame; on Java 25 Cleanroom recomputes the field's position on every use. This option replaces the "
                    + "most used accessors with a MethodHandle, found once per field. The same values.",
            new String[0][],
            new String[][] {
                    {"meldexun.reflectionutil.ReflectionField",
                            "set=ccf095f1ffb67e38dceedf0d9a845d974cc210a4241e8eaf6e0d78a88a83d04c,"
                                    + "get=53379b8c255374a7d9ada1ac11d8852fec4c659232bea5fca78de09e840b4132,"
                                    + "getBoolean=58dbc745e7f54db2fcf9ee97f566e119f7faaaa3d6b015a6dc8b4d0213bfe026,"
                                    + "setBoolean=e7f04fb93e41d961093e5b78d6562339e8c566d51b62ee3ef9e60ae726f5373c,"
                                    + "getInt=a3b1c103f46c38694459bde1e49c9087a44ece721676cc6d5b8383f4914c741f,"
                                    + "setInt=6772c5fc47773e185d5c9daac0473e366efbb3b2e98d5adaaa77b01a410bba23"},
            }),

    XAERO_BIOME_COLOUR(Group.XAERO_WORLD_MAP, "biomeColourMethodHandle",
            "Client. Xaero's map asks for the biome colour through reflection (Method.invoke) for each pixel, several "
                    + "times with biome blending on. This option makes the same call through a MethodHandle, found once. The "
                    + "same colour.",
            new String[][] {
                    {"xaero/map/biome/BiomeColorCalculator.class",
                            "m:resolve=afa9fd0aa1dd54a5a0f0285b7a8540e5b1b61bc2ef4685fb50c27a9a0c8cbc1b"},
            }),

    XAERO_VRAM(Group.XAERO_WORLD_MAP, "vramQueryFourPerSecond",
            "Client. Xaero's map asks the GPU how much video memory is left every frame, only to decide how many "
                    + "textures to keep. This option asks 4 times per second; the number arrives up to 250 ms late.",
            new String[][] {
                    {"xaero/map/MapLimiter.class",
                            "m:updateAvailableVRAM=9b3ebe619cc6530b2f3b4cfbe14345de64a4c0d729a432ac40bdc69753513dcd"},
            }),

    FLUID_CORNER(Group.MINECRAFT, "fluidCornerClamp",
            "Crash fix. A fluid corner's height can go past 1 (Biomes O' Plenty honey), which brings down the chunk "
                    + "compile. This option limits the corner to the accepted range. Only the image of that corner changes, from "
                    + "1.06 to 1.",
            new String[0][],
            new String[][] {
                    {"net.minecraftforge.fluids.BlockFluidBase", "getExtendedState=061c51a679f45cdb4669f6a7a8073313a78a7d5b35414f7ea5e12bce68cf06c6"},
            });




    public final Group group;
    /** Key in the config, in the group's section. */
    public final String key;
    final String comment;
    /** {class resource, accepted values: the class's SHA-256 or "m:method=fingerprint[,...]" (gambiarra.lock.CodeLock)}. */
    final String[][] classes;
    /**
     * Game classes its mixins touch (or whose method they depend on): {class name with ".", "method=fingerprint[,...]"}. They are not in a jar JarUtil can find; the fingerprint is checked on the
     * ClassNode Mixin delivers before applying any mixin (VanillaMixinPlugin.preApply). The expected ones come from ferramentas/vanilla (the game's jar with Cleanroom's patches and the SRG rename); another
     * mod's transformer that touches the method turns the optimization off, and the log shows the fingerprint found.
     */
    final String[][] vanilla;

    /** Option in the config. */
    /** Off by default: touches the world's file or how the item moves, or was rewritten and has not been through a real session yet. */
    boolean experimental() {
        switch (this) {
            case ITEM_REST:
            case BOP_FOG_COLOUR:
            case CHUNK_NBT_PALETTE:
            case RC_DATA_OFF_THREAD:
            case XAERO_FLUSH_OFF_THREAD:
            case XAERO_VRAM:
                return true;
            default:
                return false;
        }
    }

    boolean enabledInConfig = true;
    /** Config + versions + fingerprints: its mixins are applied (decided once, early). */
    volatile boolean allowed;
    /** The fingerprints of the game's (vanilla) classes matched in all classes already transformed. */
    volatile boolean vanillaLockOk = true;

    Opt(Group group, String key, String comment, String[][] classes) {
        this(group, key, comment, classes, new String[0][]);
    }

    Opt(Group group, String key, String comment, String[][] classes, String[][] vanilla) {
        this.group = group;
        this.key = key;
        this.comment = comment;
        this.classes = classes;
        this.vanilla = vanilla;
    }

    public boolean allowed() {
        return allowed;
    }

    /** Allowed and with the game classes' fingerprints matched (the handlers of the "vanilla" group's mixins read this). */
    public boolean active() {
        return allowed && vanillaLockOk;
    }

    /** The expected fingerprints for a method of a game class, or null if this optimization does not lock this class. */
    String vanillaSpec(String className) {
        for (String[] v : vanilla) {
            if (v[0].equals(className)) {
                return v[1];
            }
        }
        return null;
    }

    /** Our classes of this optimization (resources): their links to the target mod are checked (CodeLock). */
    String[] ours() {
        switch (this) {
            case MAZE_STOP:
                return ours("mixin/rc/MazeTaskMixin", "mixin/rc/MazeSearchInterruptMixin", "rc/MazeSearchGuard");
            case RC_REJECT_EARLY:
                return ours("mixin/rc/RejectBeforePreparationMixin", "rc/EarlyReject");
            case RTG_VORONOI:
                return ours("mixin/rtg/SpacedCellularNoiseMixin", "rtg/VoronoiCells");
            case AS_GROUND:
                return ours("mixin/addstructures/GroundScanMixin", "addstructures/GroundScan");
            case BOP_FOG:
                return ours("mixin/bop/FogScanMixin", "bop/FogScanCache");
            case WE_PHOENIX_DIVE:
                return ours("mixin/wenchants/PhoenixDiveMixin", "wenchants/PhoenixDiveFix");
            case SH_PROCESS_ONCE:
                return ours("mixin/scalinghealth/ProcessOnceMixin", "scalinghealth/ProcessOnce");
            case SRP_DISLO_CACHE:
                return ours("mixin/srparasites/DisloCacheMixin", "srparasites/DisloCache");
            case FI_ONE_PASS:
                return ours("mixin/forgottenitems/ForbiddenListMixin", "forgottenitems/ForbiddenListOnePass");
            case IAF_PROPERTIES_UNCHANGED:
                return ours("mixin/iceandfire/MiscPropertiesMixin", "mixin/iceandfire/FrozenPropertiesMixin",
                        "mixin/iceandfire/ChainPropertiesMixin", "mixin/iceandfire/SirenPropertiesMixin",
                        "mixin/iceandfire/StonePropertiesMixin", "iceandfire/PropertiesSkip");
            case PULSAR_RETRY_ORDER:
                return ours("mixin/pulsar/RetryOrderMixin", "pulsar/RetryOrder");
            case BOP_FOG_COLOUR:
                return ours("mixin/bop/FogColourMixin", "bop/FogColourCache");
            case RC_DATA_OFF_THREAD:
                return ours("mixin/vanilla/MapStorageSaveMixin", "rc/StructureDataWriter");
            case XAERO_FLUSH_OFF_THREAD:
                return ours("mixin/xaero/FlushRunMixin", "mixin/xaero/FlushWaitMixin", "xaero/FlushOffThread");
            case XAERO_BIOME_COLOUR:
                return ours("mixin/xaero/BiomeColourMixin");
            case XAERO_VRAM:
                return ours("mixin/xaero/VramQueryMixin");
            default:
                return new String[0];
        }
    }

    private static String[] ours(String... paths) {
        String[] out = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            out[i] = "nthrc/" + paths[i] + ".class";
        }
        return out;
    }

}
