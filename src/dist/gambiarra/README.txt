Gambiarra ${version} - test package (${date})
===============================================

Mod for Minecraft 1.12.2 on Cleanroom with optimizations and fixes for other
mods that do not depend on Nothirium or OptiFine. Up to Nothirium Shaders
0.10.0, all of this came inside it ("nthcompat" and "nthrc"); since 0.11.0 it
is a separate mod. With Gadolinium ${shadersVersion} (the other package; it was
called Nothirium Shaders until 1.0.0-rc7), you have everything 0.10.0 had. Each
one works alone.

Runs on the client and on the server (dedicated or integrated). Whoever joins a
server with it does not need to have it, and vice versa.

Build: commit ${commit}. License: MIT (LICENSE and NOTICE inside the jar).
By Dragonborne (GitHub: snwxMR). Needs Cleanroom ${cleanroomRequired} or newer.


IN THIS PACKAGE
---------------
  ${addonJar}
      the mod (release build: no smoketest mod, no probes and no test hooks)
      sha256 ${addonSha}
  CHANGELOG.md
      what changed in each version (this is ${version})
  O-QUE-TESTAR.md
      what to check in the game in this version


WHAT IT DOES (each thing only acts with the target mod in the validated version)
--------------------------------------------------------------------------------
  Worldgen (where the world is generated: integrated or dedicated server)
  - Recurrent Complex Volts 2.0.1.2 and 2.0.1.4 (+ IvToolkit 1.3.3):
      stopMazeSearchAfterTimeout: the build of an abandoned maze kept running
        for minutes; now it stops in ~30 ms.
      rejectBeforePreparation: checks height and overlap BEFORE preparing the
        structure (StatueTemple spent ~3 s for nothing).
  - RTG Continued 1.0.7, skipUselessVoronoiCells: skips noise math that changes
    nothing (~3%).
  - Additional Structures 2.5.0, groundScanFromTopSection: looks for the ground
    from the terrain, not from the top of the world (~3.4%).
  - Biomes O' Plenty 7.0.1 (on the client), fogScanOncePerTick: its fog queried
    41x41 biomes on every fog event; now the result holds until the next tick.
    The fog comes out the same.
    fogColourOncePerTick (EXPERIMENTAL, off): keeps each column's biome and fog
    colour until the next tick.
  - Wonderful Enchantments 0.6.0, fixPhoenixDiveCrash (crash fix): Phoenix Dive
    brought the server down (ConcurrentModificationException), more easily when
    flying fast.
  Each optimization gives exactly the same result (checked by running the
  original alongside). Mod absent: only its optimizations stay out.
  New version of a mod (since 0.3.1): the optimization stays on if the code it
  depends on is the same (the same class, or its methods with the same
  "fingerprint") and if everything it calls in the mod still exists; the log
  warns that the version was not the checked one. If that code changed, only
  that optimization turns off and warns in the log ([gambiarra] ... OFF).

  Server tick (since 0.3.0)
  - Scaling Health 1.3.42, processMobsOnce: with difficulty below 1, it redid
    the health and damage math of each mob every tick; now once, as the mod
    intends.
  - Scape and Run Parasites 1.10.9, cacheDisloValues: its spawner converted the
    same code text on each attempt; now it keeps the result.
  - Forgotten Items 1.3.1.4, forbiddenListOnePass: the banned item list copied
    the whole registry twice per item (a 1 to 2 s freeze when opening the world);
    now once.
  - Ice and Fire 1.9.1 + LLibrary 1.7.20, skipUnchangedProperties: the data Ice
    and Fire puts on every mob was written to an NBT every tick just to see
    whether it changed; now only when it changes.
  - Minecraft, fixStrongholdLocateCrash (crash fix): looking for the stronghold
    (eye of ender, /locate, Charm's SpectreHaunting) before the generator built a
    chunk brought the server down; now it works.

  Ice and Fire 1.9.1 with LLibrary 1.7.20 (on the client)
  - cacheTabulaPoses: the dragon and sea serpent animation skips the animations
    that are not playing and no longer looks most parts up by name every frame;
    same animation.
  - distantDragonAnimation (off): distant dragons recompute their pose every N
    frames.

  OptiRefine (on the client; only with it installed)
  - fixOptiRefineAccPlaceholders (TEMPORARY, OptiRefine 78ff8ce): avoids the crash
    when releasing zoom and in water/lava with custom resource pack colours.
  - fixOptiRefineFloatBuffer (TEMPORARY, OptiRefine 78ff8ce): with shaders, the
    bent chest lid and stretched strips coming out of mobs.
    OptiRefine v0.1.0-alpha fixed both (its issues #5 and #6): with it these two
    stay off by themselves, and the log summary lists them as "not needed".
  - fixOptiRefineVintageFix (crash fix, since 1.0.0-rc9): OptiRefine v0.1.0-alpha
    and VintageFix's resource pack optimization (mixin.resourcepacks) both change
    DefaultResourcePack.resourceExists, and the game does not start. With this,
    OptiRefine's mixin counts as one priority above VintageFix's on that class
    only, so both apply as they are written; nothing is turned off. Only with
    the exact OptiRefine v0.1.0-alpha and VintageFix 0.7.2 code; with another
    code it changes nothing and the log gives the other ways out. With
    Nothirium, v0.1.0-alpha still does not start (another clash, with
    Nothirium itself): there, use OptiRefine 78ff8ce.
  - fixOptiRefineDynamicTextureData (since 1.0.0-rc11, OptiRefine 78ff8ce): after
    a shader pack is turned on and off in the same session, the terrain stays
    black and the item in the hand disappears, until a restart. The first time
    shaders upload a dynamic texture made before them (the lightmap, maps),
    OptiRefine swaps its data array for a new one, and the game keeps writing
    the old one. With this, each dynamic texture gets the larger array when it
    is made, as OptiFine and OptiRefine v0.1.0-alpha do (2 x width x height
    ints more each). Only with the exact 78ff8ce code; with v0.1.0-alpha it is
    "not needed".
  - fixSmartAnimations: makes OptiFine's "Smart Animations" option (Video >
    Performance) work on OptiRefine. With Nothirium, it needs Naughthirium.
  - fixRenderLibOptiRefine: without it the game does not start with OptiRefine +
    RenderLib.

  Pulsar 0.6.1 and later (on the client; since 0.2.1)
  - fixSmoothLightCrash (crash fix): with smooth lighting on and OptiRefine, the
    game crashed building chunks (NullPointerException in
    SmoothLightOcclusion.hiddenSamples, "scratch" is null). Pulsar keeps a scratch
    position on each face, but it is not created on the faces OptiRefine builds.
    The fix hands out a thread-local one; Pulsar's feature (removing the light from
    hidden corners of stairs and slabs) works the same.

  Others (on the client)
  - fixSmartLeavesVertexData: inside-out leaves (the face towards the camera
    vanishes) with LoliASM + OptiFine, with and without Nothirium.
  - OptiFine HD U G5 dynamic lights (optifinedynamiclights section):
      lockFreeLookup=true      the per-block light lookup without the lock the
                               build threads fought over (same math, checked bit
                               for bit)
      reachedSectionsOnly=true with Nothirium 0.4.9: when a source moves, only the
                               sections the light reaches are rebuilt; the image
                               does not change
      updatePerBlock=false     the source only updates when it changes block (the
                               light moves block by block); off until seen in the
                               game

  Entity LOD (on the client, since 0.2.0; image only: AI, collision, hits and sound
  do not change). Never on players, bosses, named mobs, mounts and the entity
  under the crosshair.
  - Level 1, subPixelParts (on): a model part smaller than 1 pixel on screen (the
    eye, tooth or claw of a distant mob) is not drawn. It uses the real field of
    view (including OptiFine's zoom) and the resolution. Not in the shadow pass.
    For now it only acts on Tabula models (Ice and Fire dragons) and Lycanites
    models: with OptiRefine, the hook on ordinary vanilla models (ModelRenderer,
    used by vanilla mobs and many mods) never runs, because OptiRefine replaces that
    method whole, so those mobs are always drawn in full.
  - Level 2, animationBySize (on): an Ice and Fire dragon or a Lycanites mob small
    on screen recomputes its pose every 2 to 4 frames and repeats it on the others
    (from afar, the animation is a little less smooth). Not on vanilla mobs: their
    armour recomputes the pose by itself and would end up out of step with the body.
  - Level 3, impostors (OFF, experimental): a mob under 40 pixels tall on screen
    (the larger of its collision box and its measured model) becomes an image of
    itself, redone from time to time, with a dithered switch. Never within
    impostorMinDistance (32 blocks) or 8 blocks, nor on a mob attacking the player
    or attacked by them. Only without a shader pack; ~32 MB of video memory. Only
    on the same models as level 1.
  - F3 shows one line for the mob under the crosshair (or just beside it): model,
    image or switching, its size on screen, the distance and why.

  Tick recorder (measurement; diagnostics.recordTicks=true, off)
  - Only reads the game. Applies when opening the world, even with enabled=false.
  - logs/nthrc-ticks.csv: one line per server tick (duration, chunks, entities,
    chunks sent, chunks still missing, recorder cost).
  - logs/nthrc-readiness.csv: after joining, teleporting, changing dimension or
    respawning, how long until the server has sent all chunks around.
  - When leaving the world, the log shows the summary (average tick, P95, P99, %
    above 50 ms, maximum, recorder cost).


INSTALL
-------
  Client: copy the jar to the mods folder. Coming from Nothirium Shaders 0.10.0
  (or earlier), also install Gadolinium (or Nothirium Shaders 0.11.0 or newer): the old jar
  carried both parts, and the two together would repeat the fixes.
  Dedicated server: only this jar in the server's mods folder. The client parts
  turn themselves off there.
  Your old options come by themselves on first start: nthcompat.cfg becomes
  gambiarra.cfg (without the keys that went to gadolinium.cfg) and nthrc.cfg becomes
  gambiarra-worldgen.cfg. The log shows each migration ("Config migrated"); the old
  files stay as they were. Old Portuguese value names are converted too ("Config
  gambiarra.cfg converted").


CONFIGS (config folder; created on first run)
---------------------------------------------
  gambiarra.cfg            - client fixes
      general: fixRenderLibOptiRefine, fixOptiRefineFloatBuffer,
               fixOptiRefineFloatBufferReuseView, fixOptiRefineAccPlaceholders,
               fixOptiRefineVintageFix, fixOptiRefineDynamicTextureData,
               fixSmartLeavesVertexData, fixSmartAnimations,
               smartAnimationsMarking=sprite (or coordinates),
               smartAnimationsAlwaysAnimate= (textures always updated; name, or
               with * at the end)
      iceandfire: cacheTabulaPoses=true, distantDragonAnimation=false,
               distantDragonAnimationDistance=64,
               distantDragonAnimationEveryFrames=2
      optifinedynamiclights: lockFreeLookup=true, reachedSectionsOnly=true,
               updatePerBlock=false
      pulsar: fixSmoothLightCrash=true
      lod: subPixelParts=true, partPixels=1.0, animationBySize=true,
               animationFullPixels=64, animationMaxFrames=4, impostors=false,
               impostorPixels=40, impostorUpdateFrames=6, impostorFadeFrames=8,
               impostorMinDistance=32, impostorEdgeAlpha=0.5,
               excludeMods= (mod ids, e.g. lycanitesmobs),
               excludeEntities= (ids, e.g. minecraft:zombie; * at the end = prefix)
  gambiarra-worldgen.cfg   - worldgen and server, one section per mod
      general:  enabled=true, forceUnknownVersion=false, validationMode=false,
                experimental=false
      recurrentcomplex:  stopMazeSearchAfterTimeout, rejectBeforePreparation,
                         saveStructureDataOffThread (experimental)
      rtg:               skipUselessVoronoiCells
      additionalstructures:  groundScanFromTopSection
      biomesoplenty:     fogScanOncePerTick, fogColourOncePerTick (experimental)
      wonderfulenchantments:  fixPhoenixDiveCrash (crash fix)
      scalinghealth:     processMobsOnce
      srparasites:       cacheDisloValues
      forgottenitems:    forbiddenListOnePass
      iceandfire:        skipUnchangedProperties
      pulsar:            retryNeighboursFirst
      minecraft:         fixStrongholdLocateCrash, fluidCornerClamp (crash fixes),
                         sessionLockOncePerSecond, oreVeinChunkCache,
                         reflectionFieldHandles, saveSectionsByPalette and
                         restingItemsSkipCollision (both experimental)
      xaeroworldmap:     biomeColourMethodHandle, flushChunksOffServerThread and
                         vramQueryFourPerSecond (both experimental)
      diagnostics:  recordTicks=false, maxFileSizeMB=16, oldFilesToKeep=3
  All apply after restarting the game (the tick recorder: when opening the world).


RECOMMENDED SETTINGS (other mods)
---------------------------------
  LoliASM (config/loliasm.cfg):
      B:vertexDataCanonicalization=false
      With it on (the default), LoliASM + OptiFine leave leaves inside out. The
      fixSmartLeavesVertexData fix already solves it with the option on; turning
      the option off also solves it without this mod. Both together were tested:
      correct leaves.
  Recurrent Complex (config/reccomplex.cfg):
      I:mazeTimeout=3000
      A maze that does not close holds the server until mazeTimeout (with 30000,
      ~31 s stalled). In the tests, those that closed took under 3 s.
  RLTweaker (config/rltweaker.cfg, "recurrent complex" section):
      B:ENABLED=false
      The author of Recurrent Complex Volts recommends turning RLTweaker's RC
      patches off.


TURNING IT OFF
--------------
  Only worldgen (or one optimization):
      gambiarra-worldgen.cfg -> enabled=false (or its option = false). The target
      mods go back to running exactly as without this mod.
  One client fix: its option = false in gambiarra.cfg.
  Everything: take out the jar. With Nothirium + OptiRefine + RenderLib, Gadolinium
  still opens the game (it has a copy of the RenderLib fix), but with OptiRefine
  78ff8ce the zoom crash and the bent chest lid come back.


IF SOMETHING GOES WRONG
-----------------------
  Send latest.log (logs folder) and, if Gadolinium is installed,
  gadolinium-diagnostics.txt (it carries the state of Gambiarra's fixes). The version
  and commit show on the mod's first line in latest.log:
      [gambiarra] Gambiarra ${version} (commit ${commit}), client
  (or "dedicated server"). All of Gambiarra's lines start with [gambiarra]. When
  the server stops, the log records what each worldgen optimization did
  in the session.
