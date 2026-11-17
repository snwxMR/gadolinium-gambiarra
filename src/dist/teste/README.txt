Gadolinium ${version} - test package (${date})
================================================

Gadolinium: shaders and GPU culling for Nothirium (formerly Nothirium
Shaders). For Minecraft 1.12.2 on Cleanroom. With an OptiFine shader pack on,
Nothirium keeps using the GL 4.3 renderer (it used to fall back to GL 1.5).
It also brings terrain culling on the GPU and the compatibility fixes that
make Nothirium and OptiRefine work together.

Since 0.11.0, what does not depend on Nothirium (worldgen for Recurrent
Complex, RTG and Additional Structures, Ice and Fire, general OptiRefine
fixes, SmartLeaves leaves, OptiFine dynamic lights) lives in a separate mod,
Gambiarra (its own package, version ${gambiarraVersion}). To have everything
0.10.0 had, install both. Each one works alone.

Build: commit ${commit}. License: LGPL-3.0 (LICENSE and NOTICE inside the
jar). By Dragonborne (GitHub: snwxMR).


IN THIS PACKAGE
---------------
  ${addonJar}
      the mod (release build: no smoketest mod, no probes, no test hooks
      and with the diagnostic options off)
      sha256 ${addonSha}
  ${optiRefineJar}
      OptiRefine compiled from source at commit ${optiRefineCommit}
      (github.com/Ecdcaeb/OptiRefine), the build every test used. MIT
      license, Copyright (c) 2026 Hileb: LICENSE-OptiRefine.txt
      sha256 ${optiRefineSha}
  CHANGELOG.md
      what changed in each version (this is ${version})
  O-QUE-TESTAR.md
      what to check in the game in this version


REQUIRED MODS (exact versions tested)
-------------------------------------
  Cleanroom ${cleanroomRequired} or newer (Java 25), through cleanroom-relauncher
  Nothirium-1.12.2-0.4.9-beta.jar
  RenderLib-1.12.2-1.4.5.jar
  OptiFine_1.12.2_HD_U_G5.jar        (download from optifine.net; not included)
  ${optiRefineJar}   (from this package)
      OptiRefine's v0.1.0-alpha release is not compatible with Nothirium yet:
      the game stops at start (see KNOWN LIMITATIONS). Use this 78ff8ce
      build. Any other OptiRefine build turns the shader part off, with a
      line in the log.
  ${addonJar}   (from this package)
  Recommended: Gambiarra-1.12.2-${gambiarraVersion}.jar (the other package)

The tests also had +Fugue-0.24.4.jar and scalar-3.9.0.jar present. Whether
they are mandatory was not checked.

Without Nothirium (or with another version), the game starts the same way:
only the shader part turns off.


INSTALL
-------
  1. Back up the mods folder and the config folder.
  2. REMOVE OptiNotFine-1.0.jar: it cannot live with OptiRefine.
  3. Keep OptiFine_1.12.2_HD_U_G5.jar.
  4. Take out the old NothiriumShaders jar (Gadolinium replaces it: with both
     in the folder, Gadolinium turns itself off and says why) and the old
     OptiRefine jar. Copy the two jars of this package to the mods folder
     (and the Gambiarra jar, from the other package).
  Coming from Nothirium Shaders: on first start, nthshaders.cfg is copied to
  gadolinium.cfg with all your options, and the log says so ("Config
  migrated"). The old nthshaders.cfg stays as it was; it can be deleted.
  Coming from 0.10.0 (or earlier): your options from nthcompat.cfg are carried
  over by themselves, on first start, to gadolinium.cfg (compat section) and to
  Gambiarra's gambiarra.cfg; the log shows each one ("Config migrated"). The
  old nthcompat.cfg stays as it was.
  Coming from a version with Portuguese option names: they are converted on
  first start and the log shows each one ("Config gadolinium.cfg converted").


CONFIGS (the instance's config folder; created on first run)
------------------------------------------------------------
  gadolinium.cfg  - general section: shader part and GPU culling
      enabled=true                      turns the shader part on/off
      forceOptiRefineFingerprint=false  accepts another OptiRefine build
      limitShadowPass=true              in the shadow pass, only the sections
                                        within the shadow distance the pack asks
                                        for (as OptiFine does); without this,
                                        the whole view distance
      unfocusedFpsLimit=15              FPS limit with the window out of focus
                                        (0 = no limit)
      gpuCulling=true                   terrain culling on the GPU, with and
                                        without shaders: Nothirium's visibility
                                        traversal only runs when something
                                        changes and the GPU picks, every frame,
                                        what is in the frustum (less CPU). Needs
                                        OpenGL 4.6 or ARB_indirect_parameters
      gpuShadowLightCulling=true        with shaders: the shadow pass also on
                                        the GPU, by the shadow map volume
                                        (replaces limitShadowPass)
      gpuOcclusionCulling=false         EXPERIMENTAL: Hi-Z (does not draw what
                                        is behind solid terrain). Only pays
                                        off in dense scenes (forests, cities,
                                        caves); in open terrain it costs more
                                        than it saves. Check it with
                                        measureGpuTime
      gpuOcclusionAuto=false            EXPERIMENTAL: Hi-Z turned on by itself
                                        while the GPU is the bottleneck
      gpuFaceCulling=true               with gpuCulling: opaque faces are split
                                        by direction at build time, and only
                                        those that can face the camera are drawn
      gpuFaceCullingShadow=true         with gpuFaceCulling and shaders: in the
                                        shadow pass, only the faces towards the
                                        light
      gpuCullingEntities=true           with gpuCulling: entities and chests are
                                        only drawn if the traversal reached a
                                        section their box touches
      gpuCullingSkipNoOpDirtyRebuilds=true
                                        with gpuCulling: a section outside the
                                        list marked again only asks for the
                                        traversal if it could change
      [diagnostics] measureBufferSync=false
                                        counts buffer writes made with the GPU
                                        busy (costs ~3% FPS)
      [diagnostics] measureGpuTime=false
                                        GPU time of culling, Hi-Z and the
                                        terrain draw (F3 and log); with
                                        gpuOcclusionCulling, Hi-Z alternates
                                        2 s on, 2 s off to compare
                                        All apply after restarting the game.
  gadolinium.cfg  - compat section: Nothirium x OptiRefine (up to 0.10.0 they
                    were in nthcompat.cfg)
      fixUpdateClouds=true          avoids a crash in updateClouds
      fixEntityRendering=true       makes entities and chests show up
      entityFixFastAccess=true      the fix above without reflection every frame
      limitChunkCompileBuffers=true limits Nothirium's chunk compile buffers
                                    (without the limit they pile up and are
                                    never returned)
      chunkCompileBuffersPerThread=2
                                    sets per compile thread (total limit =
                                    threads x this value)
      chunkCompileThreads=0         Nothirium's chunk build threads: 0 =
                                    automatic (processors / 4, minimum 2,
                                    maximum Nothirium's); -1 = Nothirium's
                                    (processors - 2)
      chunkCompileThreadPriority=5  their priority (5 = normal, below the client
                                    and the server; 10 = as before)
      chunkCompileOrder=inViewFirst
                                    build order when there are more than threads:
                                    those on screen first (default); inViewOnly
                                    (off-screen ones wait to come back on screen;
                                    with a shader pack it behaves as the default);
                                    arrivalOrder (as in Nothirium)
      fixSmartAnimationsShadowRace=true
                                    with Smart Animations turned on by Gambiarra
                                    and shaders: sections built during the shadow
                                    pass also mark the animated textures
      fixRenderLibOptiRefine=true   without it (or Gambiarra's) the game does
                                    not start with OptiRefine + RenderLib. Only
                                    acts without Gambiarra


RECOMMENDED SETTINGS (other mods)
---------------------------------
  Mellow (Shader options -> Debug):
      FAST_TEXCOORD = 0
      With the "Vanilla" cloud style (CLOUD_STYLE=0), the default
      FAST_TEXCOORD=1 leaves the clouds stuck to the camera: they follow you
      when you walk and do not move with the wind. With 0, they stay fixed to
      the world and move (5 of 5 runs; FPS stayed within the noise). OptiRefine
      draws clouds in a way that depends on a matrix Mellow skips in this mode;
      other packs with the same shortcut may have the same defect.
  The LoliASM, Recurrent Complex and RLTweaker ones are in Gambiarra's README.


TURNING IT OFF
--------------
  Only the shader part:
      gadolinium.cfg -> enabled=false. With shaders, Nothirium goes back to
      GL 1.5. The fixes in the compat section keep acting.
  Everything:
      take out the two jars of this package (and Gambiarra's) and put
      OptiNotFine-1.0.jar back. Nothirium + OptiRefine without the fixes in the
      compat section does not start.


KNOWN ISSUES
------------
  - Without Gambiarra, restart the game after turning a shader pack off: with
    OptiRefine 78ff8ce, turning shaders on and off in the same session leaves
    the terrain black (with holes) and the item in the hand invisible until a
    restart. OptiRefine swaps the data array of the dynamic textures made
    before shaders (the lightmap, maps) the first time shaders upload them,
    and the game keeps writing the old one. Gambiarra 1.0.0-rc11 and newer fix
    it (fixOptiRefineDynamicTextureData). The log has "Shader-off probe"
    lines 1 s and 5 s after turning shaders off.


KNOWN LIMITATIONS
-----------------
  - The shader part only acts with the exact versions above. With another
    version of Nothirium, OptiFine or OptiRefine, it turns itself off and warns
    in the log ("ADDON OFF"); with shaders, Nothirium goes back to GL 1.5.
  - Tested with the shader packs Mellow v3.4.1 and Sildur's Vibrant v2.02
    Extreme. If a pack's vertex shader cannot be adapted, Gadolinium draws that
    program chunk by chunk (like GL 1.5, slower), with no error on screen.
  - OptiRefine v0.1.0-alpha is not compatible with Nothirium yet: two new
    injectors of that release clash with Nothirium (BufferBuilder) and, with
    its default config, with VintageFix (DefaultResourcePack), and the game
    stops at start. Reported to OptiRefine. Use the 78ff8ce build from this
    package.
  - OptiRefine 78ff8ce, without Gambiarra: releasing OptiFine's zoom key (C)
    crashes the game, and so does being in water or lava with a custom
    colours resource pack (Custom Colors); with shaders, the chest lid comes out
    bent. These are OptiRefine defects Gambiarra works around.
  - OptiRefine 78ff8ce: the Trees option (Details) has no effect; leaves
    follow Graphics (Fast = opaque, Fancy = cut out).
  - OptiRefine: the game's clouds are drawn by Forge's
    renderer. Shader packs that skip the texture matrix on clouds leave them
    stuck to the camera (Mellow: see RECOMMENDED SETTINGS).
  - Anisotropic filtering above 1: water turns blue and opaque with Nothirium,
    with or without Gadolinium. (OptiFine's antialiasing turns on the same draw
    mode, but was not tested.)
  - Memory (Nothirium's, not Gadolinium's): with a big modpack, distance 16 and
    shaders, Nothirium's compile buffer queue grows and never returns the
    memory. It can pass the "direct" memory limit and bring the game down with
    "OutOfMemoryError ... direct buffer memory". Gadolinium limits that queue
    (limitChunkCompileBuffers, on by default). On a copy of the test instance
    (5 runs of each mode, 8000 MB): with the limit, the direct memory peak stayed
    at ~475 MB; without it, 2.2 to 3.5 GB. If the initial world load gets slow,
    raise chunkCompileBuffersPerThread (3 or 4). The direct memory limit equals
    Java's maximum memory (-Xmx). Workaround, if you turn the limit off or memory
    still runs out: raise the memory allocated to Minecraft.
  - F3's tile entity counters do not count those drawn by RenderLib.
  - Only tested on Cleanroom, never on plain Forge.

  Conflicts of other mods with OptiRefine (they happen without Gadolinium):
  - VintageFix 0.7.2 with its resource pack optimization on (the default) and
    OptiRefine v0.1.0-alpha: the game stops at start. Gambiarra's
    fixOptiRefineVintageFix lets those two start, but v0.1.0-alpha still clashes
    with Nothirium: with Gadolinium, use OptiRefine 78ff8ce.
  - Serene Seasons 1.2.18: the world does not open (VerifyError in
    EntityRenderer.addRainParticles). Workaround: remove Serene Seasons.
  - FermiumMixins with "Prevent Mob Spawns in Lazy Chunks (Vanilla)" on: the
    integrated server crashes when spawning mobs. Workaround: turn that option
    off in config/fermiummixins.cfg.


IF SOMETHING GOES WRONG
-----------------------
  Send latest.log and gadolinium-diagnostics.txt (both in the logs folder). The
  diagnostics carry versions, GPU, the mod's state, the compatibility fixes
  (those from here and Gambiarra's, if installed) and the mod list. The
  version and commit show on the first line of the diagnostics and on the
  mod's first line in latest.log:
      [gadolinium] Gadolinium ${version} (commit ${commit}), client
  Gadolinium's lines in latest.log start with [gadolinium].

  If Gadolinium detects an OpenGL error on its own path, it goes back to GL 1.5 by
  itself for the rest of the session and records the reason in the diagnostics.
