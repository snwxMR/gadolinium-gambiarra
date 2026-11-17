# Gambiarra changelog

What changed in each version of Gambiarra, newest first. Gambiarra gathers the optimizations and fixes for other mods that do not depend on Nothirium or OptiFine; up to 0.10.0 they came inside Nothirium Shaders (history in `CHANGELOG.md`).

**How the version goes up:** as in Gadolinium (called Nothirium Shaders until 1.0.0-rc7). Each new package (`./gradlew distGambiarra`) has a new version (`gambiarra_version` in `gradle.properties`), in the `MAJOR.MINOR.PATCH` format, with an entry in this file; each package becomes the tag `gambiarra-vX.Y.Z`. The version shows in the jar name, in `mcmod.info` and in Gambiarra's first log line (`[gambiarra] Gambiarra X.Y.Z (commit ...)`).

Older entries use today's option names; 1.0.0-rc3 lists the ones that changed.

## [1.0.0] - 2026-11-17
**First stable version.** The same code as 1.0.0-rc11, tested in game in the Mob Ascension The Incident pack. In rc7, validation mode showed 0 differences in every optimization that has a comparison.

**What the mod does.** Gambiarra speeds up and fixes other mods, on the client and on the server, without needing Nothirium or OptiFine:
- world generation and saving: ore veins, Recurrent Complex, RTG, Additional Structures, Pulsar, chunk saving;
- per-tick work of mods such as Biomes O' Plenty, Scaling Health, SRParasites, Ice and Fire and Xaero's World Map;
- crash fixes: Wonderful Enchantments, fluid corners, stronghold location, Pulsar with OptiRefine;
- OptiRefine and OptiFine fixes on the client, including the black terrain after turning a shader pack off with OptiRefine `78ff8ce`;
- an entity level of detail: small parts and distant animation, with impostors as an experiment.

Each optimization has its own option and a code lock on the exact methods it depends on. Validation mode runs it next to the original and compares.

**From rc1 to rc11:**
- **rc1:** validation mode, the `experimental` switch, and new optimizations (`sessionLockOncePerSecond`, `oreVeinChunkCache`, `reflectionFieldHandles`, `biomeColourMethodHandle`, `vramQueryFourPerSecond`, `fluidCornerClamp`).
- **rc2:** every game fingerprint checked against Cleanroom 0.7.14 with its binpatches.
- **rc3:** everything in English, MIT, old config names converted, and two optimizations rewritten so they no longer reproduce other mods' code.
- **rc4:** `fluidCornerClamp` started in the real game (it had another Cleanroom's fingerprint), warnings visible in chat and F3, and the build check `checkLocks` (80 checks).
- **rc5:** the Recurrent Complex validation compares the data, not the bytes; the Biomes O' Plenty fog cache keyed by exact position and client tick.
- **rc6:** impostors measured by the model and held back by a safety lock; an F3 line for the mob under the crosshair.
- **rc7:** the model measure fixed for Ice and Fire's dragons and kept apart from drawing, and the build check `checkLod`.
- **rc8:** with OptiRefine v0.1.0-alpha, the two fixes it made unnecessary stay off by themselves; every log line starts with `[gambiarra]`.
- **rc9:** `fixOptiRefineVintageFix`, so OptiRefine v0.1.0-alpha starts next to VintageFix.
- **rc10:** no code change; the texts say to use OptiRefine `78ff8ce` with Nothirium.
- **rc11:** `fixOptiRefineDynamicTextureData`, for the black terrain and invisible held item after turning a shader pack off with OptiRefine `78ff8ce` (89 code lock checks).

## [1.0.0-rc11] - 2026-11-17
**Black terrain after turning a shader pack off (OptiRefine `78ff8ce`).** Only compiled and checked outside the game; the in-game test is still pending.

- **The defect.** With OptiRefine `78ff8ce`, turning a shader pack on and then off in the same session left the terrain black (with holes) and the item in the hand invisible until a restart. Gadolinium 1.0.0-rc10's log lines found the cause:
  - the first time shaders upload a dynamic texture made before them (the lightmap, maps), OptiRefine swaps its data array for a new one, three times the size;
  - the game keeps writing the old array (vanilla takes the lightmap's array once, when it starts), and the texture uploads the new one, which stays zero.

  It happens without Nothirium too. OptiRefine v0.1.0-alpha fixed it in commit `2cab6ef`.
- **New option `fixOptiRefineDynamicTextureData`** (on). It does what that commit does: each dynamic texture gets the larger array when it is made, with the same content, so the array never changes afterwards. The texture still uploads only its own pixels.
  - Memory: 2 x width x height ints more per dynamic texture made while shaders are off, as with OptiFine and with v0.1.0-alpha. That is 2 KB for the lightmap and 128 KB for each map.
  - It also covers maps made before turning shaders on (not checked in game).
  - Code lock: it only acts with OptiRefine's `MixinDynamicTexture` byte for byte the `78ff8ce` one. With v0.1.0-alpha the log summary lists it as not needed; with any other build it stays off by the code lock.
- `checkLocks` also checks that class in both OptiRefine builds (89 checks).

## [1.0.0-rc10] - 2026-11-17
The same code as 1.0.0-rc9; the version goes up together with Gadolinium's. The texts now say that OptiRefine v0.1.0-alpha does not start with Nothirium for another reason, so `fixOptiRefineVintageFix` only helps it start without Nothirium. With Gadolinium, use the `78ff8ce` build. The build reads Pulsar 0.6.6-beta, and both Pulsar code locks match it.

## [1.0.0-rc9] - 2026-11-15
**OptiRefine v0.1.0-alpha with VintageFix.** Only compiled and checked outside the game; the in-game test is still pending.

- **The game did not start** with OptiRefine v0.1.0-alpha and VintageFix 0.7.2 with its resource pack optimization on (`mixin.resourcepacks=true`, its default): `InvalidInjectionException ... optiRefine$closeResourceProbe with priority 1000 cannot inject into ... merged by org.embeddedt.vintagefix.mixin.resourcepacks.MixinDefaultResourcePack with priority 1000`. The release added an injector to `DefaultResourcePack.resourceExists`, the method VintageFix replaces, and Mixin only lets an injector into a method another mixin replaced when that mixin has a lower priority; both are 1000. An OptiRefine defect (issue text in `docs/upstream/`).
- **New option `fixOptiRefineVintageFix`** (on): OptiRefine's mixin counts as one priority above VintageFix's, on `DefaultResourcePack` only, while that class gets its mixins; the order they are applied in does not change. VintageFix's `resourceExists` still calls the method OptiRefine wraps, so both apply as they are written and nothing is turned off. It only acts with both mixins on that class and both classes exactly the checked ones (OptiRefine v0.1.0-alpha, VintageFix 0.7.2); otherwise it changes nothing and the log gives the other ways out (`mixin.resourcepacks=false`, or OptiRefine `78ff8ce`).
- With OptiRefine `78ff8ce`, or with VintageFix's resource pack optimization off, there is nothing to fix: the log summary lists it as not needed.
- `checkLocks` also checks VintageFix's class in the mods folder and OptiRefine's in both builds (87 checks).

## [1.0.0-rc8] - 2026-11-15
**OptiRefine v0.1.0-alpha, and every log line as `[gambiarra]`.** Only compiled and checked outside the game; the in-game test is still pending. Nothing changes in world generation or on the server.

- **OptiRefine v0.1.0-alpha** (the release of 2026-11-14) fixed the two defects Gambiarra worked around: the crash on releasing the zoom key, or in water or lava with Custom Colors (its issue #5), and the bent chest lid with shaders (issue #6). With that release, `fixOptiRefineAccPlaceholders` and `fixOptiRefineFloatBuffer` stay off by themselves, and the log summary lists them as not needed (`... 3 not needed (fixOptiRefineFloatBuffer, fixOptiRefineFloatBufferReuseView, fixOptiRefineAccPlaceholders) ...`). With the 78ff8ce build they work as before.
- **Checked against the release, no change needed:** Smart Animations (the release now calls `SmartAnimations.update()` itself, which Gambiarra already detects and leaves to it; item textures are now marked by the release too, and marking them again changes nothing; `renderModelBrightnessColorQuads` is still marked only by Gambiarra), the RenderLib patch (`renderEntities` is still replaced whole), and the Pulsar smooth light fix (the release still adds the extra `AmbientOcclusionFace` constructor that leaves Pulsar's field empty).
- **Logs:** every line of Gambiarra starts with `[gambiarra]`; before, many said `[nthcompat]` or `[nthrc]`. Only the text changed.
- The texts say Gadolinium, the new name of Nothirium Shaders. Author: Dragonborne (`mcmod.info`, `LICENSE`, `NOTICE`).

## [1.0.0-rc7] - 2026-11-15
**LOD level 3 (impostors, experimental, off by default), from the rc6 test with Ice and Fire dragons.** Only compiled and checked outside the game; the in-game test is still pending.

- **Dragons showing only their glowing eyes: not the LOD.** A dragon in the test world carries a permanent invisibility effect (effect 14, about 2.1 billion ticks, saved in the world). The game does not draw an invisible mob's body, only its layers, and Ice and Fire's eye layer draws the eyes of an invisible dragon on purpose. The LOD had it as a model and let the game draw it. The F3 line now says so (`invisible for the game: it draws only its layers ...` instead of `hurt, dying, glowing or invisible`) and also shows the game's own state of the mob at the end (`game: invisible, hurtTime 3, ...`).
- **A dragon measured as 465 blocks.** The size of a part with its children boxed each child's sphere again at every level, so on Ice and Fire's 9-level Tabula models it came out about 30 times too large. Level 3 now measures each part's reach (the farthest point from its rotation point in any pose): about 38 blocks for that dragon. Level 1 keeps its own size, unchanged.
- **Measuring never changes the drawing.** The part hooks measure in a separate step and still answer from level 1 alone (with level 1 off, every part is drawn). If a measure fails, measuring stops for the session, every mob stays a model, and the chat says so. An error inside level 3 turns it off for the session and that mob is drawn as a model.
- **New build check, `checkLod`:** it runs the part hooks outside the game and fails if measuring hides a part, changes a level 1 answer or leaves a part undrawn when it fails, or if the size measured for Ice and Fire's fire dragon model goes past twice its size at rest. With rc6's measure put back, it fails.

## [1.0.0-rc6] - 2026-11-15
**LOD level 3 (impostors, experimental, off by default), from what the rc4 test showed.** Only compiled and checked outside the game; the in-game test is still pending.

- **A mob up close still drawn as an image.** The way back to the model worked as written (above 1.2 times `impostorPixels`, with the dithered switch), but the size on screen came from the collision box alone: a mob with a small box and a large model (a Lycanites flyer, a dragon's wings) measured small up close. The size is now the larger of the box and the model, measured each time the model is drawn (through level 1's part hooks, which now also go in when `impostors` is on and `subPixelParts` is off). A mob whose model has not been measured yet stays a model. The image's square uses the same size.
- **Safety lock**, whatever the size says: never an image closer than `impostorMinDistance` (new, 32 blocks) or within 8 blocks, on a mob the player attacked in the last 5 seconds or one whose attack target, as far as the client knows, is the player; twice `impostorMinDistance` while the player has been hurt in the last 5 seconds. Each time it acts goes to the diagnostics (`safetyLock`, `safetyLockCutAnImage`).
- **F3 line** for the mob under the crosshair or just beside it, at any distance, excluded ones too: model, image or switching, its size on screen (with the box and the measured model), the distance and the reason.
- **Capture:** the depth now goes 4 radii each way (parts along the line of sight were cut off), and the image is cut at `impostorEdgeAlpha` (new, 0.5) instead of 0.1. The alpha test the game had is put back after the image.
- Diagnostics: `ditheredSwitches` counted frames, not switches. It is now `ditheredFrames`, next to `toImage` and `toModel`.
- `recurrentcomplex.saveStructureDataOffThread`: checked that the copy of Recurrent Complex's data is deep and made whole on the server thread; only the code comment changed.

## [1.0.0-rc5] - 2026-11-14
**Fixes from the rc4 validation session.** Only compiled and checked outside the game; the in-game test is still pending.

- **`recurrentcomplex.saveStructureDataOffThread`: 10 checks, 10 differences, same data.** Recurrent Complex puts each structure's stored compound into the output as it is, and a compound that has grown and shrunk lists its keys in another order than its copy: other bytes, the same keys and values. Validation now reads both files back and compares the NBT entry by entry; the summary also counts `sameBytes` and `sameContentOtherOrder`. Nothing changed in what is written.
- **`biomesoplenty.fogScanOncePerTick`: 55 differences, all `239.99998` cached against `240.00002` computed.** Biomes O' Plenty weighs the border columns by the fractional part of the player's position, and the cache kept the result per block and per world time; the server's time packets can repeat a world time on the client, so a result from a moment earlier, a little elsewhere in the same block, came back. The cache now keeps it per exact position and per client tick, counted by Gambiarra itself.
- **`biomesoplenty.fogColourOncePerTick`** uses the same client tick count, so each answer lasts exactly one client tick.
- **Validation log:** the summary at game exit is registered when validation mode turns on (before, with the file's first line). A session in which no optimization had a comparison says so in the file and in `latest.log`.

## [1.0.0-rc4] - 2026-11-14
**Fixes from the rc3 test.** Only compiled and checked outside the game; the in-game test is still pending.

- **`minecraft.fluidCornerClamp` never turned on in the real game.** The fingerprint of `BlockFluidBase#getExtendedState` in the code came from another Cleanroom version, so the lock turned the crash fix off on 0.7.14. It now has 0.7.14's fingerprint. The defect is still there in 0.7.14: the corner heights go to a property that only accepts 0 to 1, with no limit.
- **Visible warnings.** An option that is on in the config but did not start (a lock that does not match, a mixin that did not go in whole, an error) is shown once in the chat and listed in F3, besides the log. A few seconds after entering a world, the log gets a summary: `[gambiarra] Summary: N active, N without their target mod, N off by the code lock (...), N did not start (...)`. On a dedicated server, only the summary, when the server starts.
- **`minecraft.restingItemsSkipCollision` stays off where another mod changes the item's movement.** In the test pack, RLTweaker's `aggressiveMotionChecker` patch changes `Entity.move` before Gambiarra checks it, so the lock turns the option off. Nothing changed in the option; the README says so.
- **Build checks before packaging.** `checkLocks` recomputes all 80 lock checks (target mod classes, the game's own methods with the Cleanroom 0.7.14 binpatches, and the OptiRefine fingerprint) and fails if one does not match. In rc3 it would have caught the `fluidCornerClamp` fingerprint.
- Compiles against Pulsar 0.6.5 (the test instance's); the Pulsar locks match it.

## [1.0.0-rc3] - 2026-11-09
**The mod is now in English, and under MIT.** Only compiled and checked outside the game; the in-game test is still pending.

**Everything is in English:** config comments and values, log messages, error and warning texts, validation mode and its log, the tick recorder's CSV files, `mcmod.info`, the README and the code comments.

**Your config is kept.** On first start the old names are converted and each conversion goes to the log (`[nthcompat] Config gambiarra.cfg converted: ...`):

| Before | Now |
|---|---|
| `smartAnimationsMarking=coordenadas` | `smartAnimationsMarking=coordinates` |

The same conversion applies to values that come from an old `nthcompat.cfg`. The comments of an existing config file are rewritten in English.

**No option is ignored silently any more:** an option that no longer exists is removed from the file with a warning in the log saying what it held (`forceOptiRefineFingerprint`, `pulsar.rememberLastChunk`); `stopMazeSearchAfterTimeout` moving section also goes to the log; an unknown option and an invalid value get a warning on each start.

**Renamed files:** `logs/gambiarra-validacao.log` → `logs/gambiarra-validation.log`; `logs/nthrc-prontidao.csv` → `logs/nthrc-readiness.csv`. The column names of the CSV files are in English too.

**License:** MIT. To keep that true, two optimizations stopped reproducing other mods' code:
- `iceandfire.cacheTabulaPoses`: animations that are not playing are still skipped (28 of 29 calls), but the one that is playing now runs Ice and Fire's own `moveToPose`, which is LGPL-3.0, instead of a rewritten copy of it. The by-name lookups of that one animation come back; those of `setRotationAngles` stay cached.
- `biomesoplenty.fogColourOncePerTick`: Biomes O' Plenty's license does not allow adapted copies, so the copy of its loop is gone. The option now keeps each column's biome and fog colour until the next tick, and Biomes O' Plenty does the rest itself. **Off by default** until it goes through a real session, because this is new code. The gain has not been measured yet.

## [1.0.0-rc2] - 2026-11-09
**Review before 1.0.** the in-game test is still pending.

- Fingerprints fixed: `Entity.move` and `MapStorage` had been taken from Cleanroom 0.6.13. Why: in the real game (0.7.14) the lock would turn off `restingItemsSkipCollision` and `saveStructureDataOffThread`. What changed in those two methods is only from compilation; no logic that affects these options.
- All game fingerprints checked against the 0.7.14 classes, applying its binpatches (`ferramentas/vanilla`).
- Reviewed, no change: Smart Animations, OptiFine dynamic lights and LOD.

## [1.0.0-rc1] - 2026-11-09
**1.0 candidate.** The final only comes after the in-game test. Includes all of 0.4.0, which never made it to a validated package.

**Validation mode in the jar:** `validationMode` (off). Each optimization runs alongside the original path, compares, and the game carries on with the original. Differences go to `logs/gambiarra-validation.log`, with the count per optimization when closing the world.

**New, each with its own option and code lock:**
- `sessionLockOncePerSecond`: `session.lock` checked once per second, not for every chunk saved.
- `oreVeinChunkCache`: ore generation reads through the last chunk looked up. The same vein.
- `reflectionFieldHandles`: RenderLib and RLTweaker field accesses through `MethodHandle`.
- `biomeColourMethodHandle`: Xaero's biome colour through `MethodHandle`.
- `vramQueryFourPerSecond` (experimental): Xaero's video memory read 4 times per second.
- `fluidCornerClamp`: fixes the crash from a fluid corner above 1 (BoP honey).

**Changed:**
- `experimental` (off) turns on the experimental options at once. Experimental, and off by default: `saveSectionsByPalette`, `restingItemsSkipCollision`, `saveStructureDataOffThread`, `flushChunksOffServerThread`, `vramQueryFourPerSecond`.
- Code fingerprints of `EntityItem`, `Entity`, `BlockStateContainer` and `MapStorage` in the jar, computed by the `ferramentas/vanilla` tool.
- Pulsar 0.6.4-beta accepted.

## [0.4.0] - 2026-11-05
**The first 5 from the analysis of the 01 and 04/11 Flare recordings** (`docs/analise-mods.md`, entries 24, 25, 27, 28, 29 and 33). Each with its own option in `gambiarra-worldgen.cfg` and a code lock.

**Not yet tested in the harness or in the game:** the side-by-side validation waits for the PC to be free (`gamb040-*` scenarios ready, see `docs/o-que-testar/gambiarra-0.4.0.md`). The fingerprints of the game's own methods (EntityItem, Entity, BlockStateContainer, MapStorage) are recorded on the harness's first run; until then, only the development jar turns those three optimizations on (the release one leaves them off and says so in the log).

### New
- **`pulsar.retryNeighboursFirst`:** the resend of chunks waiting for light tests the 4 neighbours first (4 queries with no lock) and only then the pending light in 3×3 chunks (5 maps, 3 with a lock, contended with the light threads). It was 12.4% of the server in the 01/11 survival session. Both tests only read; the chunk goes out on the same tick. Issue draft in `docs/upstream/issue-pulsar-retry-order.md` (not published).
- **`biomesoplenty.fogColourOncePerTick`:** Biomes O' Plenty's fog colour added up the colour of up to 69×69 biomes every frame (6.2% of the frame in the 04/11 survival session). The sum holds until the next tick; the mix with the sun, rain, thunder and the default colour is redone every frame, in the same order of operations. Independent of `fogScanOncePerTick` (the distance).
- **`minecraft.saveSectionsByPalette`:** when saving a chunk, the number of each block is looked up once per state of the section's palette, and not for each of the 4096 blocks (65% of converting the chunk to NBT: ~2.2 s in 13 min of spikes). The same bytes; sections with the global palette go through the game.
- **`minecraft.restingItemsSkipCollision`:** an item resting on the ground (horizontal velocity exactly zero) repeats the previous tick's result of `pushOutOfBlocks` and `move`, and the "no water" of `handleWaterMovement`, while nothing changes. That means:
  - the same input, bit for bit;
  - no writes in the sections up to 3 blocks around;
  - no tile entity there;
  - no entity with a collision box nearby;
  - no unknown `GetCollisionBoxesEvent` listener;
  - the world border still and far away.

  The repeated tick must have been "quiet": no fire, no damage, no block with an effect on contact or on landing. Items were 26.6% of the server in the 01/11 survival session.
- **`recurrentcomplex.saveStructureDataOffThread`:** `reccomplex-structuredata.dat` (1.48 MB compressed in the test world, and it only grows) was rewritten and compressed on the server thread at every autosave (130–210 ms each). Now:
  - the data copy happens there;
  - the compression and writing happen on the game's disk thread, to a temporary file swapped for the final one at the end;
  - when closing the world, the game waits for that thread.
- **`xaeroworldmap.flushChunksOffServerThread`:** Xaero's World Map save reader (single-player) made the server thread write and compress the whole chunk queue (210–540 ms each time). Now the queue stays for the disk thread, and Xaero's thread waits for it before reading.

### Changed
- **"vanilla" mixin group** (`mixins.nthrc.vanilla.json`, in the jar's manifest): the game classes that load early (EntityItem, Entity, BlockStateContainer, MapStorage). Their lock is the methods' fingerprint at the moment Mixin transforms the class, already with what other mods changed in it.
- **The `pulsar` section of `gambiarra-worldgen.cfg` is back**, with the new option. The migration only deletes the old `rememberLastChunk` key.
- Compiles against Biomes O' Plenty 7.0.1 and Xaero's World Map 1.47.0 (read only, like the other target mods).

## [0.3.1] - 2026-11-01
**Recurrent Complex 2.0.1.4, Pulsar 0.6.2 and locks that survive updates.** Validation in `docs/gambiarra-031-resultados.md`.

### Changed
- **The locks look at the code, not the version number** (all of `nthrc` and the Pulsar fix):
  - on a new version of a mod, each optimization stays on if the class it depends on is the same, or if the methods that matter have the same "fingerprint" (the normalized bytecode);
  - and if everything our classes call in the mod still exists;
  - if any of that changes, only that optimization turns off, with the reason in the log;
  - the injection count stays.
- **Recurrent Complex 2.0.1.4:** `stopMazeSearchAfterTimeout` follows the mod's change. The search with `mazeTimeout` went to the `solveRooms` method, and the mixin targets both places. `rejectBeforePreparation` stays the same (`StructureGenerator` did not change).
- **Pulsar 0.6.2:** the smooth lighting crash fix turns on again. Pulsar's code is identical to 0.6.1's, and the bug remains.
- **`rejectBeforePreparation` validation:** it only counts as different when the original would generate the structure. A failure for another reason goes to a separate counter.

**Tested in the harness:**
- a 64×64-chunk region with a fixed seed, side by side: 0 differences in the world;
- a maze that never closes: stopped in ~17 ms, against over 240 s without the stop;
- smooth lighting with Pulsar 0.6.2: no crash.

**Not tested in the game.**

## [0.3.0] - 2026-10-31
**Server tick:** the first 5 of `docs/analise-mods.md` (`gambiarra-worldgen.cfg`, one section per mod). Validation in `docs/gambiarra-030-resultados.md`.

**Tested in the harness** (fire on the simulated weak PC, on × off × side by side, and a short pair for the stronghold): no crash, 0 GL errors, complete injections, 0 differences side by side. **Not tested in the game.**

### New
- **Scaling Health 1.3.42, `processMobsOnce`:** the mod only counted a mob as processed with difficulty 1 or more. With less (in this pack, always 0), it redid the health and damage math of each mob every tick. Now once, as it intends. Share of the server in the fire scene: 2.72% → 0.23%.
- **Scape and Run Parasites 1.10.9, `cacheDisloValues`:** its spawner converted the same code text on each attempt; it now keeps the result while the text stays the same. 0.57% → 0%.
- **Forgotten Items 1.3.1.4, `forbiddenListOnePass`:** the banned item list copied the whole registry twice per item, for each wildcard. The server froze ~1–2 s when opening the world; now at most ~10 ms.
- **Ice and Fire 1.9.1 + LLibrary 1.7.20, `skipUnchangedProperties`:** the data Ice and Fire puts on every mob (frozen, chain, siren, stone and others) was written to an NBT every tick just to find out whether it changed. Now only when it changes. 2.11% → 0.46%, plus 1.72% → 0.23% in LoliASM.

### Fixed
- **Stronghold crash** (`minecraft.fixStrongholdLocateCrash`):
  - looking for the nearest stronghold (eye of ender, `/locate`, Charm's SpectreHaunting) before the generator built a chunk brought the server down (NPE in `MapGenStronghold.generatePositions`; 7 crashes in the test pack, from 29/09 to 03/10);
  - now the generator receives the lookup's world, and the positions come out the same (they come from the seed);
  - Charm's `SpectreHaunting` can be turned back on.

## [0.2.1] - 2026-10-31
**Pulsar 0.6.1 crash fix** (`gambiarra.cfg`, `pulsar` section). Details and validation in `docs/gambiarra-021-resultados.md`.

### Fixed
- **Smooth lighting crash with Pulsar 0.6.1** (`fixSmoothLightCrash`, on): with smooth lighting on, the game crashed building chunks (`NullPointerException` in `SmoothLightOcclusion.hiddenSamples`, "scratch" is null), with and without shaders.
  - The cause: Pulsar keeps a scratch position in each `AmbientOcclusionFace`, created in the constructor. OptiRefine adds another constructor, with no arguments, which is the one OptiFine's `RenderEnv` uses in every chunk build, and with that one Pulsar's position is not created. It does not depend on the thread: the vanilla build with OptiRefine goes through the same path.
  - The fix: a `@ModifyVariable` at the start of `hiddenSamples` hands out a thread-local position when Pulsar's arrives null. Pulsar's feature (removing the light from hidden corners of stairs and slabs) works the same.
  - Only with Pulsar 0.6.1 and the two classes involved byte for byte equal to the checked ones (SHA-256); the injection is counted in the final class. State in `nthshaders-diagnostics.txt` (`pulsar`).
  - Issue for Pulsar (draft, not published): `docs/upstream/issue-pulsar-smoothlight-optirefine.md`.

### Build
- The harness opens the game on Cleanroom 0.7.14 (the one in the test pack since 31/10) and compiles against the instance's Recurrent Complex 2.0.1.3 and Pulsar 0.6.1. The Recurrent Complex optimizations are still checked only on 2.0.1.2: with 2.0.1.3 they turn themselves off and warn in the log.

## [0.2.0] - 2026-10-27
**Entity LOD** (`gambiarra.cfg`, `lod` section): from afar, mobs are drawn with less detail. Only the image changes (AI, collision, hits and sound do not). Details and validation in `docs/lod-resultados.md`.

**Tested in the harness** (46 runs: a crowd of vanilla mobs, Lycanites and Ice and Fire dragons from 8 to 180 blocks, without shaders and with Mellow, with zoom, with the camera moving and shaking; images equal to LOD off on levels 1 and 2, 0 blinks, exceptions respected). **Not tested in the game.**

### New
- **Level 1, small parts** (`subPixelParts`, on; `partPixels=1`): a model part smaller than 1 pixel on screen is not drawn (with its children).
  - the size comes from the real matrices: the field of view (with OptiFine's zoom), the window resolution and the entity's scale; the part's size is the diagonal of the box of its cubes and children, with 25% slack;
  - hysteresis: the part vanishes below 1 px and only comes back above 1.3 px;
  - vanilla and other mods' models (`ModelRenderer`), Ice and Fire's Tabula (LLibrary 1.7.20's `AdvancedModelRenderer`, checked by SHA-256) and Lycanites Mobs 2.0.8.10's `.obj` (checked by SHA-256);
  - outside the shadow pass and outside the GUI (perspective only);
  - in practice, it applies to models with very small parts seen from afar (in the test scene, only the Ice and Fire dragons; with the limit at 3 px, also Lycanites): a vanilla mob leaves the screen (the game itself stops drawing it at ~64 blocks) before its parts reach 1 pixel.
- **Level 2, animation by size** (`animationBySize`, on): an entity small on screen recomputes its pose every N frames (N = 64 ÷ height in pixels, from 1 to 4) and repeats its pose on the others; 15% hysteresis on N.
  - only on models whose whole pose comes from a single place: Ice and Fire's Tabula (the same hook as the distant animation) and Lycanites' `.obj` (the animation frames of all parts);
  - not on vanilla ones: armour and layers recompute the pose by themselves, and a repeated pose would leave the armour out of step with the body;
  - the shadow pass and the main pass of the same frame use the same pose.
- **Level 3, impostors** (`impostors`, **off**, experimental): an entity smaller than 40 px on screen becomes a quad with an image of itself, in a 2048 × 2048 atlas in an FBO (~32 MB of video memory), with the game's same camera (the image is what the game would draw there, at a lower resolution). Redone every 6 frames or when the camera turns around it; dithered switch between model and image (`glPolygonStipple`, no shaders); 20% hysteresis. Only without a shader pack.
- **Exceptions:** players, bosses, named mobs, mounts and the target never have LOD. The target: the entity under the game's crosshair, the one crossing the crosshair line up to 6 blocks, the one attacked in the last 5 s, and it stays a target for 20 frames after leaving the crosshair. `excludeMods` and `excludeEntities` take mods and entities off the list.
- **Locks:** on vanilla classes, the injection count in the final class (they change with OptiFine); on LLibrary and Lycanites, the version and the SHA-256 of the classes.

## [0.1.0] - 2026-10-27
First version: what came out of Nothirium Shaders 0.10.0, with no behaviour change. Details in `docs/divisao-dois-mods.md`.

**Tested in the harness** (together with Nothirium Shaders 0.11.0, alone in a modpack without Nothirium, OptiFine and OptiRefine, on a dedicated server, and the config migration). **Not tested in the game.**

### What it has
- **Worldgen** (on the server, dedicated or integrated; `gambiarra-worldgen.cfg`, one section per mod): Recurrent Complex Volts (`stopMazeSearchAfterTimeout`, `rejectBeforePreparation`), RTG Continued (`skipUselessVoronoiCells`), Additional Structures (`groundScanFromTopSection`), Biomes O' Plenty (`fogScanOncePerTick`, on the client), the Phoenix Dive crash fix (Wonderful Enchantments) and the tick recorder (`diagnostics.recordTicks`, off).
- **Ice and Fire** (`gambiarra.cfg`, `iceandfire` section): the pose and part lookup caches of the dragons and the sea serpent, and the distant dragon animation (off).
- **OptiRefine** (`gambiarra.cfg`): `acc$` placeholders (crash when releasing zoom and in water/lava with custom colours), `BufferBuilder` float view (bent chest lid with shaders), Smart Animations (`fixSmartAnimations`) and removing RenderLib's `renderEntities` patch (without it, OptiRefine + RenderLib does not open).
- **SmartLeaves leaves with LoliASM** (`fixSmartLeavesVertexData`).
- **OptiFine dynamic lights** (`gambiarra.cfg`, `optifinedynamiclights` section): `lockFreeLookup`, `reachedSectionsOnly` (with Nothirium) and `updatePerBlock` (off).

### Configs and migration
- `config/gambiarra.cfg`: on first start, a copy of the `nthcompat.cfg` of 0.10.0 (or earlier), without the keys that went to Nothirium Shaders (`nthshaders.cfg`, `compat` section).
- `config/gambiarra-worldgen.cfg`: on first start, a copy of `nthrc.cfg`.
- Each migration goes to the log (`Config migrated: ...`); the old files are not touched.
- The Java packages keep their old names (`nthcompat`, `nthrc`), and the log lines also start with `[nthcompat]` and `[nthrc]`, besides `[gambiarra]`.
- The old `nthrc` mod ("Nothirium Shaders: server (worldgen)") no longer exists; the mod is now `gambiarra`.
