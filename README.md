# Gadolinium and Gambiarra

By Dragonborne (GitHub: snwxMR).

Two mods for **Minecraft 1.12.2 on Cleanroom** (Java 25), built from this repository. Both are made only of mixins that coexist
with other mods: neither is a fork, and neither changes or redistributes another mod's jar.

- **Gadolinium: shaders and GPU culling for Nothirium** (`gadolinium`, client only; called Nothirium Shaders until 1.0.0-rc7): keeps
  [Nothirium](https://github.com/Meldexun/Nothirium)'s GL 4.3 renderer working with OptiFine shader packs, and adds GPU culling of the
  terrain.
- **Gambiarra** (`gambiarra`, client and server): performance work and crash fixes for other mods. It does not need Nothirium or
  OptiFine, and a dedicated server only needs this jar.

Version history: [CHANGELOG.md](CHANGELOG.md) (Gadolinium) and
[CHANGELOG-Gambiarra.md](CHANGELOG-Gambiarra.md).

## Requirements
| Mod | Version |
|---|---|
| Cleanroom | 0.7.14-alpha or newer (Java 25) |
| Nothirium | 1.12.2-0.4.9-beta |
| RenderLib | 1.12.2-1.4.5 |
| OptiFine | 1.12.2 HD_U_G5 |
| [OptiRefine](https://github.com/Ecdcaeb/OptiRefine) | built from commit `78ff8ce`: `OptiRefine-78ff8ce.jar`, attached to this repository's [v1.0.0 release](https://github.com/snwxMR/gadolinium-gambiarra/releases/tag/v1.0.0) with its MIT license. Its v0.1.0-alpha release is **not compatible with Nothirium yet** (see [Known limitations](#known-limitations)) |

These are the requirements for Gadolinium's shader part. The game still starts without them; that part just turns itself off. Any
OptiRefine build other than `78ff8ce` and v0.1.0-alpha (for example a newer commit of its `dev` branch) also turns it off, with a
line in the log.
OptiNotFine cannot be installed together with OptiRefine.

Gambiarra has no requirements. Each optimization runs only when its target mod is present in the validated version.

## GPU compatibility
Gadolinium has only been tested on an NVIDIA GeForce RTX 5060 (driver 621.14, Windows). On other GPUs it falls back step by
step, with no config change: GPU culling needs compute shaders and `glMultiDrawArraysIndirectCount` (GL 4.6 or
`ARB_indirect_parameters`); without them the terrain goes through Nothirium's own GL 4.3 path, and below GL 4.3 through Nothirium's
older paths. An OpenGL error turns off only the part that caused it, for the rest of the session. The log names the GPU and the
path in use. Details, and what is still untested: [docs/gpu-compatibility.md](docs/gpu-compatibility.md).

## How the safety works
- **Code locks.** Every optimization checks a SHA-256 fingerprint of the exact methods it depends on. If a mod or the game has
  different code there, that one optimization turns off and the log says why.
- **Injection audit.** After a mixin is applied, the number of injections is compared with the expected number. A partial mixin never acts.
- **One option each.** Every optimization has its own switch. Anything not yet proven in a real game ships off by default.
- **Validation mode.** With `validationMode=true`, an optimization runs next to the original path and compares the two. The game keeps the
  original result, and differences go to `logs/gambiarra-validation.log` or `logs/gadolinium-validation.log`, with counts per optimization
  when the world closes. Expect lower performance in this mode.
- **Visible warnings.** When an option that is on in the config does not start (a lock that does not match, a shader the driver refused,
  a mixin that did not go in whole), each mod says so once in the chat and lists it in F3, besides the log. A few seconds after a world is
  entered, the log gets a summary: how many options are active, how many have no target mod, how many are not needed (the target mod
  already fixed the problem), and which ones the code lock turned off.
- **Build checks.** Packaging runs `checkShaders` (every shader, after the mod inserts its lines, must start with `#version`),
  `checkLocks` (every lock is recomputed from the target mod jars and the Cleanroom binpatches, both accepted OptiRefine builds give their
  fingerprints, and the build fails if one does not match)
  and `checkLod` (measuring models for the LOD impostors never hides a part nor changes level 1, a failing measure leaves the mob drawn, and
  the size measured for an Ice and Fire dragon stays within twice the model at rest).

## Options

### `config/gadolinium.cfg`
Coming from Nothirium Shaders, the first start copies `nthshaders.cfg` to `gadolinium.cfg` with every option, and the log says so. The
old file is left as it was and is no longer read.

| Option | Default | What it does |
|---|---|---|
| `enabled` | `true` | Turns on the shader part. |
| `validationMode` | `false` | See above. |
| `forceOptiRefineFingerprint` | `false` | Accepts an OptiRefine build other than the two known ones. At your own risk. |
| `limitShadowPass` | `true` | Shadow pass only draws sections within the shadow distance the pack asks for. |
| `unfocusedFpsLimit` | `15` | Frames per second while the window has no focus. 0 = no limit. |
| `gpuCulling` | `true` | Terrain culling on the GPU, with or without shaders. |
| `gpuShadowLightCulling` | `true` | With `gpuCulling` and shaders: shadow pass also culled on the GPU. |
| `gpuOcclusionCulling` | `false` | Experimental. Two-pass Hi-Z occlusion culling: skips drawing the terrain hidden behind other terrain. It only pays off in dense scenes (forests, cities, caves); in open terrain it costs more than it saves (about 0.1 to 0.2 ms of GPU per frame on an RTX 5060, with or without shaders). Check it on your pack with `diagnostics.measureGpuTime`. |
| `gpuOcclusionAuto` | `false` | Experimental. Turns Hi-Z on by itself while the GPU is the bottleneck. |
| `gpuFaceCulling` | `true` | Per-direction face culling for opaque layers. |
| `gpuFaceCullingShadow` | `true` | Same, in the shadow pass, against the light direction. |
| `gpuCullingEntities` | `true` | Entities and tile entities are only drawn if a section they touch was reached. |
| `gpuCullingSkipNoOpDirtyRebuilds` | `true` | Skips visibility rebuilds for rebuilds that change nothing. |
| `diagnostics.measureBufferSync` | `false` | Counts buffer writes made while the GPU was busy. Costs about 3% FPS. |
| `diagnostics.measureGpuTime` | `false` | GPU time per frame of GPU culling, Hi-Z and the terrain draw, with and without a shader pack, in F3 and in the log. With `gpuOcclusionCulling`, Hi-Z alternates 2 s on, 2 s off to show what it saves and costs. |
| `compat.*` | see file | Nothirium and OptiRefine compatibility fixes and chunk build thread settings. |

### `config/gambiarra.cfg` (client)
| Option | Default | What it does |
|---|---|---|
| `fixRenderLibOptiRefine` | `true` | Removes a RenderLib patch that stops the game from starting with OptiRefine. |
| `fixOptiRefineFloatBuffer`, `fixOptiRefineFloatBufferReuseView` | `true` | Temporary fixes for a vertex buffer defect of OptiRefine `78ff8ce` (bent chest lid with shaders). OptiRefine v0.1.0-alpha fixed it: with it they stay off by themselves. |
| `fixOptiRefineAccPlaceholders` | `true` | Temporary fix for placeholder fields of OptiRefine `78ff8ce` (crash on releasing zoom, and in water or lava with Custom Colors). Off by itself with v0.1.0-alpha, which fixed it. |
| `fixOptiRefineVintageFix` | `true` | Crash fix for OptiRefine v0.1.0-alpha with VintageFix's resource pack optimization: both change `DefaultResourcePack.resourceExists` and the game does not start. OptiRefine's mixin counts as one priority above VintageFix's on that class only, so both apply as written. Only acts with the exact OptiRefine v0.1.0-alpha and VintageFix 0.7.2 code. |
| `fixOptiRefineDynamicTextureData` | `true` | Fix for OptiRefine `78ff8ce`: after a shader pack is turned on and off, the terrain stays black and the item in the hand disappears. Each dynamic texture gets, when it is made, the larger data array OptiRefine would swap in later, as OptiFine and OptiRefine v0.1.0-alpha do. Only acts with the exact `78ff8ce` code. |
| `fixSmartLeavesVertexData` | `true` | Fixes inside-out leaves with LoliASM and OptiFine. |
| `fixSmartAnimations`, `smartAnimationsMarking`, `smartAnimationsAlwaysAnimate` | `true`, `sprite`, empty | Makes OptiFine Smart Animations work on OptiRefine. |
| `iceandfire.cacheTabulaPoses` | `true` | Skips dragon animations that are not playing, and caches part lookups. |
| `iceandfire.distantDragonAnimation` and its settings | `false` | Distant dragons recompute their pose less often. |
| `optifinedynamiclights.*` | see file | Cheaper OptiFine dynamic lights. |
| `pulsar.fixSmoothLightCrash` | `true` | Fixes a Pulsar smooth light crash with OptiRefine. |
| `lod.subPixelParts`, `lod.animationBySize` | `true` | Entity level of detail: skips sub-pixel model parts and animates small entities less often (models covered: see Known limitations). |
| `lod.impostors` | `false` | Experimental. Very small entities become an image. Never within `lod.impostorMinDistance` (32 blocks) or 8 blocks, nor on a mob fighting the player; F3 shows the LOD decision for the mob under the crosshair. |

### `config/gambiarra-worldgen.cfg`
| Section | Option | Default | What it does |
|---|---|---|---|
| `general` | `enabled` | `true` | Turns on the module. |
| `general` | `validationMode` | `false` | See above. |
| `general` | `experimental` | `false` | Turns on every option marked experimental below. |
| `general` | `forceUnknownVersion` | `false` | Runs optimizations on unrecognized target versions. |
| `minecraft` | `saveSectionsByPalette` | `false` | Experimental. Chunk saving looks up each palette state once instead of 4096 times. |
| `minecraft` | `restingItemsSkipCollision` | `false` | Experimental. An item at rest repeats its last collision result while nothing around it changed. Stays off when another mod changes `Entity.move` or `EntityItem.onUpdate` before Gambiarra checks them: RLTweaker's `aggressiveMotionChecker` patch changes `Entity.move`. |
| `minecraft` | `sessionLockOncePerSecond` | `true` | The `session.lock` check counts for one second instead of being repeated per saved chunk. |
| `minecraft` | `oreVeinChunkCache` | `true` | Ore generation reads through the last chunk it looked up. |
| `minecraft` | `reflectionFieldHandles` | `true` | RenderLib and RLTweaker field access through `MethodHandle`. |
| `minecraft` | `fluidCornerClamp` | `true` | Crash fix: clamps fluid corner heights to the allowed range. |
| `minecraft` | `fixStrongholdLocateCrash` | `true` | Crash fix for stronghold location. |
| `pulsar` | `retryNeighboursFirst` | `true` | Deferred chunk retries test neighbours before the expensive lookup. |
| `biomesoplenty` | `fogScanOncePerTick` | `true` | Biomes O' Plenty fog distance computed once per tick instead of per frame. |
| `biomesoplenty` | `fogColourOncePerTick` | `false` | Experimental. Each column's biome and fog colour kept until the next tick. |
| `recurrentcomplex` | `stopMazeSearchAfterTimeout`, `rejectBeforePreparation` | `true` | Abandoned maze searches stop; rejection happens before preparation. |
| `recurrentcomplex` | `saveStructureDataOffThread` | `false` | Experimental. Saves the structure data file on the game's disk thread. |
| `rtg` | `skipUselessVoronoiCells` | `true` | Skips Voronoi cells that cannot change the result. |
| `additionalstructures` | `groundScanFromTopSection` | `true` | Ground search starts at the highest section with blocks. |
| `scalinghealth`, `srparasites`, `forgottenitems`, `iceandfire` | see file | `true` | Each removes repeated work in that mod. |
| `wonderfulenchantments` | `fixPhoenixDiveCrash` | `true` | Crash fix. |
| `xaeroworldmap` | `biomeColourMethodHandle` | `true` | Biome colour lookup through `MethodHandle`. |
| `xaeroworldmap` | `flushChunksOffServerThread`, `vramQueryFourPerSecond` | `false` | Experimental. Moves a chunk flush off the server thread; reads free video memory four times per second. |
| `diagnostics` | `recordTicks` and its settings | `false` | Server tick recorder. Read only. |

## Known limitations
- **OptiRefine v0.1.0-alpha is not compatible with Nothirium yet.** Two new injectors of that release target methods that
  Nothirium (`BufferBuilder.sortVertexData`) and VintageFix (`DefaultResourcePack.resourceExists`, with its default config) replace,
  and the game stops at start, with or without Gadolinium and Gambiarra. It is reported to [OptiRefine](https://github.com/Ecdcaeb/OptiRefine/issues).
  Use the `78ff8ce` build from this repository's [v1.0.0 release](https://github.com/snwxMR/gadolinium-gambiarra/releases/tag/v1.0.0). Gadolinium still recognizes v0.1.0-alpha.
  Without Nothirium, Gambiarra's `fixOptiRefineVintageFix` lets v0.1.0-alpha start next to VintageFix.
- **Entity level of detail (Gambiarra):** level 1 (`lod.subPixelParts`) and the impostors (`lod.impostors`) only act, for now, on
  Tabula models (Ice and Fire) and Lycanites models. With OptiRefine, the hook on ordinary vanilla models (`ModelRenderer`, used by
  vanilla mobs and many mods) never runs, because OptiRefine replaces that method whole: those mobs are always drawn in full. Level 2
  (`lod.animationBySize`) is Tabula and Lycanites only by design.
- **Shader packs:** Gadolinium was tested with Mellow v3.4.1 and Sildur's Vibrant v2.02 Extreme. A program that cannot be adapted is
  drawn chunk by chunk (slower), with no error on screen.
- **Anisotropic filtering above 1** makes water blue and opaque with Nothirium, with or without Gadolinium.
- Both mods were only tested on Cleanroom, never on plain Forge.

## Known issues
- **Without Gambiarra, restart the game after turning a shader pack off.** With OptiRefine `78ff8ce`, turning a shader pack on and
  then off in the same session leaves the terrain black (with holes where the void shows through) and the item in the hand
  invisible until the game restarts. The cause is OptiRefine's: the first time shaders upload a dynamic texture made before them
  (the lightmap, maps), it swaps the texture's data array for a new one, and the game keeps writing the old one. Gambiarra 1.0.0-rc11
  and newer fix it (`fixOptiRefineDynamicTextureData`, on by default); OptiRefine v0.1.0-alpha fixed it too.

## Installing
1. Back up your `mods` folder and your world.
2. Add `Gambiarra-1.12.2-<version>.jar`. For shaders on Nothirium, also add `Gadolinium-1.12.2-<version>.jar` and
   `OptiRefine-78ff8ce.jar` (from this repository's [v1.0.0 release](https://github.com/snwxMR/gadolinium-gambiarra/releases/tag/v1.0.0); MIT license, by Hileb).
3. Coming from Nothirium Shaders: remove the old `NothiriumShaders-*.jar`. Gadolinium replaces it; with both in the folder, Gadolinium
   turns itself off and says which jar to remove.
4. A dedicated server only needs the Gambiarra jar. Clients and servers can have it or not, independently.

## Building
Java 25 for Gradle, with the target mod jars and the official OptiFine jar available locally: the paths go in `gradle.properties`
(or, better, in `~/.gradle/gradle.properties`). Step by step in [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
./gradlew buildOptiRefine
./gradlew distTeste distGambiarra
```

## Reporting problems
Use the issue templates (bug or crash). They ask for `logs/latest.log`, the Cleanroom and Java versions and the mod list. For GPU
problems, also `logs/gadolinium-diagnostics.txt`. Every optimization can be turned off on its own in its config file, which is the
quickest way to tell which one is involved.

## Licenses
Each mod has its own license, and each jar carries its license text and a `NOTICE` file with the third-party credits.

- **Gadolinium** is licensed under the [GNU LGPL 3.0](LICENSE) (which builds on the [GNU GPL 3.0](COPYING)), the whole mod, because
  its per-direction face culling is derived from Aetherium.
- **Gambiarra** is licensed under the [MIT License](LICENSE-Gambiarra). It contains no code copied from LGPL projects.

Both: Copyright (c) 2026 Dragonborne.

Source files start with an `SPDX-License-Identifier` line.

## Credits
| Project | License | Used for |
|---|---|---|
| [Aetherium Backport](https://github.com/entitybrian69-bit/aetherium) | LGPL-3.0 | Gadolinium: the per-direction face culling (`FaceSorter`, `FaceLayout` and the face groups in `cull.comp`) is derived from it. |
| [OptiRefine](https://github.com/Ecdcaeb/OptiRefine) | MIT | Gadolinium: `RenderGlobalEntitiesMixin` adapts its `renderEntities`. Gambiarra: `AccPlaceholderFix` applies the same name rule. Its build from commit `78ff8ce`, compiled from source, is attached to this repository's v1.0.0 release and carried in Gadolinium's test package, with its license. |
| [Recurrent Complex](https://github.com/Ivorforce/RecurrentComplex) | MIT | Gambiarra: `rejectBeforePreparation` follows the order of its checks. |
| [Embeddium](https://github.com/FiniteReality/embeddium) | LGPL-3.0 | Reference only: its visibility traversal and entity culling approach were read. No code was taken. |
| [Lithium](https://github.com/CaffeineMC/lithium) | LGPL-3.0 | Reference only: the idea of tracking block changes per section, and its list of hot paths. No code was taken. |
| [RLTweaker](https://github.com/Charles445/RLTweaker) | MIT | Read for compatibility. |

A few Gambiarra methods repeat small pieces of Minecraft or OptiFine math in the same order so the result stays identical. That math remains subject to the terms of
those projects: see `NOTICE` in the Gambiarra jar.

Sodium 0.6 and later use the Polyform Shield license and were not read.

Mods under All Rights Reserved are only touched through mixins, each guarded by the code locks above. Their jars and decompiled code are
never committed or redistributed.
