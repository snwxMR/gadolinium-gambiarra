# Changelog

What changed in each version of Gadolinium (called Nothirium Shaders until 1.0.0-rc7), newest first.

**How the version goes up** (since 0.2.0): each new package (`./gradlew distTeste`) has a new version, in the `MAJOR.MINOR.PATCH` format:
- **patch** (0.2.0 → 0.2.1): a package with fixes only;
- **minor** (0.2.0 → 0.3.0): a package with a new feature.

The version shows in the jar name, in `mcmod.info`, in the mod's first log line and in `gadolinium-diagnostics.txt` (`nthshaders-diagnostics.txt` up to 1.0.0-rc7), along with the commit. `distTeste` refuses a version already used by a package and a version with no entry in this file.

Up to 0.10.0, the versions here also include what is now Gambiarra (worldgen, Ice and Fire, general OptiRefine fixes, leaves, OptiFine dynamic lights). From then on it has its own changelog: `CHANGELOG-Gambiarra.md`.

Older entries use today's option names; 1.0.0-rc3 lists the ones that changed.

## [1.0.0] - 2026-11-17
**First stable version.** The same code as 1.0.0-rc11, tested in game in the Mob Ascension The Incident pack with OptiRefine `78ff8ce`.

**What the mod does.** Gadolinium (called Nothirium Shaders until 1.0.0-rc7) keeps [Nothirium](https://github.com/Meldexun/Nothirium)'s GL 4.3 terrain renderer working with OptiFine shader packs on Cleanroom, through OptiRefine, shadow pass included. It also adds GPU culling of the terrain:
- frustum culling, and shadow-light culling in the shadow pass;
- per-direction face culling;
- entity and tile entity culling by the sections reached;
- an experimental two-pass Hi-Z occlusion culling, off by default. It only pays off in dense scenes, and `measureGpuTime` shows whether it does on your pack.

Everything is made of mixins checked by code locks. Each part has its own option and turns itself off, with a warning, when what it depends on is not as expected. The supported OptiRefine is the `78ff8ce` build, attached to the repository's v1.0.0 release; v0.1.0-alpha is recognized, but it does not start with Nothirium yet.

Measured on the test PC (RTX 5060, open plains): GPU culling takes 0.037 ms of GPU per frame, and 0.035 ms more in the shadow pass with Mellow. Drawing the terrain takes 0.82 ms without shaders and 2.25 ms with them.

**From rc1 to rc11:**
- **rc1:** validation mode (`validationMode`) and automatic Hi-Z (`gpuOcclusionAuto`, experimental).
- **rc2:** renderer review for other GPUs, `unfocusedFpsLimit`, and the license of the face culling derived from Aetherium.
  - The review: clearer warnings, the real draw limit for GPUs without a native count, and the Hi-Z depth copy without a framebuffer switch.
- **rc3:** everything in English, LGPL-3.0, and the old config names converted on first start.
- **rc4:** GPU culling started again, the GL 1.5 moment when entering a world with shaders was fixed, warnings became visible in chat and F3, and the build checks `checkShaders` and `checkLocks` were added.
  - GPU culling had stopped because rc3's license line pushed `#version` down in the compute shader.
  - Tested: about 750 FPS with GPU culling against about 630 without, in the same spot.
- **rc5:** the validation log is always written (it was missing when no difference was found).
- **rc6 and rc7:** no change in this mod; the version went with Gambiarra's.
- **rc8:** the new name Gadolinium (mod id `gadolinium`; `nthshaders.cfg` is copied to `gadolinium.cfg` on first start), the author Dragonborne, and OptiRefine v0.1.0-alpha recognized.
- **rc9:** the shader-off issue documented, with a first diagnostic line.
- **rc10:** OptiRefine `78ff8ce` became the supported build (v0.1.0-alpha clashes with Nothirium and VintageFix at start), and the diagnostic was widened.
- **rc11:** the cause of the black terrain after turning shaders off was found (OptiRefine `78ff8ce`'s; Gambiarra fixes it), and the GPU time measure `measureGpuTime` was added.

## [1.0.0-rc11] - 2026-11-17
**Why the terrain goes black after turning shaders off, and the GPU time of culling and Hi-Z.** Only compiled and checked outside the game; the in-game test is still pending.

- **The cause of the known issue, found with rc10's log lines.** After shaders are turned off, the lightmap on the GPU is all zeros, while the game's copy is right and there is no OpenGL error. The defect is in OptiRefine `78ff8ce`, and it happens without Gadolinium or Nothirium.
  - The first time shaders upload a dynamic texture made before them (the lightmap, maps), OptiRefine swaps the texture's data array for a new one, three times the size. The game keeps writing the old one, so the texture uploads zeros from then on.
  - With zeros in the lightmap, the terrain is black (Nothirium's shader multiplies by the lightmap) and the item in the hand is drawn with alpha 0, so it is invisible.
  - With a shader pack on, the lightmap the pack reads was black too, from the first upload on.
  - OptiRefine fixed it in v0.1.0-alpha (commit `2cab6ef`). For `78ff8ce`, Gambiarra 1.0.0-rc11 has the fix (`fixOptiRefineDynamicTextureData`).
  - The known issue now says this: without Gambiarra, restart the game after turning a shader pack off.
- **The shader-off diagnostic, widened again (log only).** The lines now say:
  - whether the array the game writes is the one the texture uploads;
  - OptiRefine's shaders state for that texture;
  - the texture's level 0 on the GPU;
  - the pixel transfer state, for uploads and reads;
  - the OpenGL error of the read.

  The diagnostic's own error is fixed. The Nothirium part no longer fails with `NullPointerException ... "perPass" is null`: Nothirium only picks its VAO copy at its first update. The probe now reads the copy at the current index, or says there is none yet.
- **New diagnostics option `measureGpuTime`** (off). It measures the GPU time per frame with OpenGL time queries, separately with and without a shader pack, of:
  - GPU culling (the compute shaders) and shadow culling;
  - Hi-Z (the depth copies and the pyramid);
  - the terrain draw of the main pass.

  The numbers show in F3, every 30 s in the log, and in `gadolinium-diagnostics.txt`. With `gpuOcclusionCulling` on, Hi-Z alternates 2 s on and 2 s off, so the terrain draw is measured with and without it in the same scene; the line says how much Hi-Z saves, how much it costs and the net. The image does not change. The queries never wait for the GPU, and any failure turns only the measurement off.

## [1.0.0-rc10] - 2026-11-17
**OptiRefine `78ff8ce` is the supported build, and a wider diagnostic for turning shaders off.** Only compiled and checked outside the game; the in-game test is still pending. Nothing changes in how anything is drawn.

- **OptiRefine v0.1.0-alpha is not compatible with Nothirium yet.** Two new injectors of that release target methods that Nothirium (`BufferBuilder.sortVertexData`) and VintageFix (`DefaultResourcePack.resourceExists`) replace with an `@Overwrite`, and the game stops at start, with or without Gadolinium. We checked all 467 injectors of the release against every `@Overwrite` in a 200-mod pack, and only these two stop the game; the `78ff8ce` build has none. Gadolinium 1.0.0 supports only the `78ff8ce` build; v0.1.0-alpha is still recognized. The package carries `OptiRefine-78ff8ce.jar` again (MIT license), and the same jar is attached to the repository's v1.0.0 release.
- **The shader-off diagnostic, widened (log only).** The `Shader-off probe` lines are now taken at the moment the terrain and the hand are drawn, 1 s and 5 s after shaders are turned off. They record:
  - the current program, the framebuffer and the viewport;
  - texture units 0 to 15 and the vertex attribute arrays;
  - the write masks and the blend, alpha and depth tests;
  - the lightmap, both on the GPU and as the game computed it;
  - the layout of Nothirium's VAO.

  A reference line, before any shader pack, gives a normal state to compare with. The probe only reads.
- **The known issue** (after turning a shader pack off, restart the game) now also says the terrain goes black with holes and the item in the hand disappears.

## [1.0.0-rc9] - 2026-11-15
**A known issue, and a log line to find its cause.** Only compiled and checked outside the game; the in-game test is still pending. Nothing changes in how anything is drawn.

- **Known issue: after turning a shader pack off, restart the game.** Going back to no shaders can leave the terrain black or fullbright until a restart, while the sky, the hand and the HUD look right. It happens since the first version; it is now in the README's "Known issues". Nothirium's terrain shader reads the lightmap from texture unit 1, and Nothirium never binds it there itself (it skips the vanilla step that does it). The likely cause is that unit 1 is left with another texture when shaders turn off.
- **New log line, diagnostics only:** 1 s and 5 s after shaders are turned off, `[gadolinium] Shader-off probe (...)` writes what that shader reads on unit 1 (the texture bound there, the one the game's state cache believes is bound, and the lightmap's own texture) and the samplers of Nothirium's program. It only reads GL state, and a failure only goes to the log.
- The version also goes up with Gambiarra's rc9, which lets OptiRefine v0.1.0-alpha start next to VintageFix (in its changelog). The package `README.txt` and the README warn about that conflict and give the ways out: Gambiarra, `mixin.resourcepacks=false` in `config/vintagefix.properties`, or OptiRefine `78ff8ce`.

## [1.0.0-rc8] - 2026-11-15
**New name, and OptiRefine's v0.1.0-alpha release.** Only compiled and checked outside the game; the in-game test is still pending. Nothing changes in how the terrain or the shadows are drawn.

- **Nothirium Shaders is now Gadolinium** ("shaders and GPU culling for Nothirium"): mod id `gadolinium`, jar `Gadolinium-1.12.2-<version>.jar`, `[gadolinium]` in the log and in F3, `[Gadolinium]` in the chat, `logs/gadolinium-diagnostics.txt` and `logs/gadolinium-validation.log`. The Java packages and the mixin names stay as they were (they only exist inside the jar).
- **Your config comes along.** On first start, with no `gadolinium.cfg` yet, `nthshaders.cfg` is copied to it with every option and the log says so (`[gadolinium] Config migrated: nthshaders.cfg copied to gadolinium.cfg ...`). The old file is left as it was and is no longer read.
- **Remove the old jar.** With an old `NothiriumShaders-*.jar` still in the mods folder (it carries the same classes), Gadolinium turns itself off, and the chat and the log say which jar to remove.
- **OptiRefine v0.1.0-alpha** (the release of 2026-11-14, commit `6c4855e`) is now accepted and recommended. The build from commit `78ff8ce` is still accepted. Any other OptiRefine build (for example a newer commit of its `dev` branch) still turns the shader part off, with a warning. Before accepting the release, every place where this mod or Gambiarra touches OptiRefine was checked against it: the shader vertex format and the attributes of immediate draws, the VBOs (OptiFine's render regions are not used with Nothirium), the passes and the shadow pass (the release's new shadow chunk list sits in a part of `setupTerrain` that Nothirium never reaches), `renderEntities` and `updateClouds` (still replaced whole by OptiRefine, so both fixes here are still needed), and the processing of its own mixins, which now runs after MixinExtras.
- **Breaking blocks with a tile entity:** the entity rendering adapted from OptiRefine's `renderEntities` now uses the release's condition (OptiFine's on Forge): the breaking stage is drawn only on blocks that ask for it, in their own pass. With the 78ff8ce code it was drawn on any block with a tile entity, in both passes.
- **Package `README.txt`:** it showed the Cleanroom the code compiles against (0.6.13); it now says the one the mods need, 0.7.14 or newer.
- **Build:** `buildOptiRefine` builds both accepted OptiRefine builds. The release build comes out byte for byte the jar published on GitHub (the same SHA-256), and that jar is the one in the test package. `checkLocks` checks both fingerprints and the OptiRefine in the mods folder (84 checks).
- Author: Dragonborne (`mcmod.info`, `NOTICE`).

## [1.0.0-rc7] - 2026-11-15
The same code as 1.0.0-rc5; the version goes up together with Gambiarra's rc7 (impostor fixes, in its changelog).

## [1.0.0-rc6] - 2026-11-15
The same code as 1.0.0-rc5; the version goes up together with Gambiarra's rc6 (impostor fixes, in its changelog).

## [1.0.0-rc5] - 2026-11-14
**Fixes from the rc4 test.** Only compiled and checked outside the game; the in-game test is still pending. In the rc4 test GPU culling started in every session (about 750 FPS on against 630 off, in the same spot).

- **`logs/nthshaders-validation.log` was not created.** The summary at game exit was only registered together with the file's first line, and Nothirium Shaders writes a line only when it finds a difference: a session with no difference left no file, and the comparisons it did were lost. The summary at exit is now registered when validation mode turns on. A session in which no optimization had a comparison says so in the file and in `latest.log`.

## [1.0.0-rc4] - 2026-11-14
**Fixes from the rc3 test.** Only compiled and checked outside the game; the in-game test is still pending.

- **GPU culling never started in rc3.** The license line at the top of `cull.comp` pushed `#version` down, and the `#define` of each stage went after the first line, so the driver refused the compute shader (`error C0204: version directive must be first statement`). Nothirium drew the terrain its own way, with no message on screen. The `#define` now goes right after `#version`, wherever it is.
- **GL 1.5 for a moment when entering a world with shaders.** The first renderer was chosen before the class of `RenderChunkShadowAreaMixin` (Nothirium's `AbstractRenderChunk`) had loaded, so shader mode was refused once. The classes of every required mixin now load before the choice.
- **Visible warnings.** An option that is on in the config but did not start (a refused shader, a lock that does not match, a mixin that did not go in whole) is shown once in the chat and listed in F3, besides the log. A few seconds after entering a world, the log gets a summary: `[nthshaders] Summary: ...`.
- **Build checks before packaging.** `checkShaders` runs the line insertion on every shader of both jars and fails if `#version` is not the first statement. `checkLocks` recomputes every code lock from the target mod jars and the Cleanroom 0.7.14 binpatches, and fails if one does not match.

## [1.0.0-rc3] - 2026-11-09
**The mod is now in English, and under LGPL-3.0.** No change to the image or to how anything is drawn. Only compiled and checked outside the game; the in-game test is still pending.

**Everything is in English:** config comments, section names and values, log messages, F3 lines, error and warning texts, the diagnostics file, validation mode and its log, `mcmod.info`, the README and the code comments.

**Your config is kept.** On first start the old names are converted and each conversion goes to the log (`[nthshaders] Config nthshaders.cfg converted: ...`):

| Before | Now |
|---|---|
| section `diagnostico` | section `diagnostics` |
| `chunkCompileOrder=campoDeVisaoPrimeiro` | `chunkCompileOrder=inViewFirst` |
| `chunkCompileOrder=soCampoDeVisao` | `chunkCompileOrder=inViewOnly` |
| `chunkCompileOrder=ordemDeChegada` | `chunkCompileOrder=arrivalOrder` |

The same conversion applies to values that come from an old `nthcompat.cfg`. The comments of an existing config file are rewritten in English.

**No option is ignored silently any more:** an option that no longer exists is removed from the file with a warning in the log saying what it held (`gpuCullingCompileEmptyDirectly`, for example); an unknown option and an invalid value get a warning on each start.

**Renamed files:** `logs/nthshaders-diagnostico.txt` → `logs/nthshaders-diagnostics.txt`; `logs/nthshaders-validacao.log` → `logs/nthshaders-validation.log`.

**License:** the whole mod is now LGPL-3.0, because of the code derived from Aetherium. The license texts and the OptiRefine MIT notice go inside the jar.

## [1.0.0-rc2] - 2026-11-09
**Renderer review before 1.0.** Nothing that changes the image without an option. Only compiled and checked outside the game; the in-game test is still pending.

**GPU compatibility (`docs/compatibilidade-placas.md`):**
- The warning names the GPU and the reason when GPU culling does not turn on, and the compute limits are checked first. Why: on AMD and Intel, the log was the only clue.
- The fallback path without `glMultiDrawArraysIndirectCount` already existed (Nothirium's GL 4.3); now the log says when it is in use.

**GPU culling:**
- The indirect draw receives the real limit of the frame, not the size of the region. Why: GPUs without a native count, like Intel, emit one conditional draw per possible command.
- Buffers written by the GPU became `GL_DYNAMIC_COPY`. Why: with `DRAW`, other drivers may pick memory that is slower for the GPU.
- Added a missing barrier before copying the F3 counts, and the UBO binding is restored in the right order.
- Frustum, shadow pass, per-direction face culling and entity graph culling: reread, no other defect found.

**Hi-Z (`gpuOcclusionCulling`, still off):**
- The depth copy went from `glBlitFramebuffer` to `glCopyImageSubData`, and no framebuffer is switched. Why: it was 3.3% of the frame in the 04/11 Flare recording, almost all of Hi-Z's CPU cost.
- An OpenGL error now turns off only Hi-Z, not all of GPU culling.
- Margin in the depth test for the rounding error far from the camera. Why: to keep sections from vanishing at the limit.
- Multisampling and an unsized depth format take Hi-Z out of that frame, with no OpenGL error.

**Automatic Hi-Z (`gpuOcclusionAuto`):** the GPU clock is now checked, and the minimum time on doubles on each round. Why: a driver with a different clock would give meaningless numbers, and the decision could keep flipping.

**Visibility traversal:** `gpuCullingCompileEmptyDirectly` was removed. Why: `gpuCullingSkipNoOpDirtyRebuilds` (on) already covers the same case, and the option no longer did anything. Third path, build order and threads: reread, no defect found.

**Aetherium package:** new `unfocusedFpsLimit` (15, `0` = no limit). Why: it was the missing item and carried no risk. No mod in the pack does this.

**Shader mode:** the offset declaration comes after the pack's `#extension` lines. Why: AMD and Mesa refuse a declaration before them, and that program would fall back to drawing chunk by chunk. The warning about falling back to GL 1.5 is now a WARN.

**Diagnostics:**
- F3 redone: one line says what is on, another what each culling discarded, with entities and chests per frame.
- `measureBufferSync` also measures the GPU culling path.
- `GpuBench` updated to the current format. It ran outside the game: compute, multi-draw, Hi-Z and the new copy passed.

**License:** per-direction face culling was marked as derived from Aetherium (LGPL-3.0), with the license texts in `licenses/` and inside the jar.

**Reviewed, no change:** Smart Animations, OptiFine dynamic lights and LOD (levels 1, 2 and 3).

## [1.0.0-rc1] - 2026-11-09
**1.0 candidate.** The final only comes after the in-game test. Includes 0.11.1.

- `validationMode` (off): compares the original and the optimized path and writes to `logs/nthshaders-validation.log`.
- `gpuOcclusionAuto` (experimental, off): turns Hi-Z on by itself while the GPU is the bottleneck. The measure is the GPU queue delay, every 2 seconds, and once on it stays on for at least 60 seconds.

## [0.11.1] - 2026-11-05
**The entity fix without reflection every frame.** From the analysis of the 04/11 Flare recordings (`docs/analise-mods.md`, entry 34). **Not yet tested in the harness or in the game.**

### Changed
- **`entityFixFastAccess`** (new, `compat` section, on): the fields OptiFine adds to `RenderGlobal` and `EntityRenderer`, and its methods that `fixEntityRendering` calls every frame, go through a `MethodHandle` found once. Through reflection, on Java 25 Cleanroom recomputed the field position on every use (`Unsafe.objectFieldOffset`): ~0.7% of the frame. Off, it goes back to reflection. The same values in both cases.

## [0.11.0] - 2026-10-27
**The addon became two mods.** What depends on Nothirium and the shader path stays here, in Nothirium Shaders; what does not (worldgen, Ice and Fire, general OptiRefine fixes, leaves, OptiFine dynamic lights) went to a new mod, **Gambiarra** (0.1.0, its own changelog: `CHANGELOG-Gambiarra.md`). No new optimization and no behaviour change: it is 0.10.0 split in two. Details in `docs/divisao-dois-mods.md`.

**Tested in the harness** (both together, each alone, Gambiarra on a dedicated server and the config migration). **Not tested in the game.**

### Changed
- **Two jars.** To have everything 0.10.0 had, install both. Each one works alone; neither requires the other.
- **The compatibility fixes that depend on Nothirium** left `nthcompat.cfg` and went to `nthshaders.cfg`, `compat` section (same names and defaults): `fixUpdateClouds`, `fixEntityRendering`, `limitChunkCompileBuffers`, `chunkCompileBuffersPerThread`, `chunkCompileThreads`, `chunkCompileThreadPriority`, `chunkCompileOrder`, plus a copy of `fixRenderLibOptiRefine` (only applies without Gambiarra, which has its own). The Smart Animations shadow pass race fix got its own option here, `fixSmartAnimationsShadowRace` (on; Smart Animations itself is turned on by Gambiarra).
- **Migration on first start:** the values you had in the `nthcompat.cfg` of 0.10.0 (or earlier) go into the `compat` section; each migrated key goes to the log (`[nthshaders] Config migrated: ...`). The old `nthcompat.cfg` is not touched.
- **Without Gambiarra**, with OptiRefine and RenderLib, the game still opens: a copy of the RenderLib fix stays here and only acts when Gambiarra is not installed.
- `nthshaders-diagnostics.txt` shows the compatibility fixes from here and, if Gambiarra is installed, its own.

## [0.10.0] - 2026-10-27
Item 2 (entities, OptiFine dynamic lights and visibility traversal). Includes 0.9.0. Details in `docs/0100-itens.md` and the validation in `docs/0100-resultados.md`.

**Tested in the harness** (bug batches on the simulated weak PC: 8 processors and power plan at 60%, without shaders and with Mellow). **Not tested in the game.**

### New
- **Entities and chests by the graph** (`nthshaders.cfg`, `gpuCullingEntities`, on; with `gpuCulling`):
  - an entity or tile entity is only drawn if the visibility traversal reached one of the sections its box touches. The box is the render box joined to the collision box and to the parts, and that of large entities grows on its own side;
  - a mob in a cave not connected to where you are, or a chest underground, is no longer drawn;
  - **always drawn:** the shadow with shaders, you and what you ride, entities that ask to ignore the frustum, and those the mod itself draws even off screen (the Ice and Fire dragon: the wings go far past the box);
  - **checked:** dragon with its centre behind a hill and a wing in view, ghast and bats in the air, chests, sign and skull in a tunnel, lake with the mobs of the scene. Identical images with and without, and 0 flips between hidden and drawn with the camera turning;
  - needs RenderLib 1.4.5 (checked by SHA-256).
- **OptiFine dynamic lights** (`nthcompat.cfg`, `optifinedynamiclights` section):
  - **`lockFreeLookup` (on):** the per-block light lookup uses a copy of the sources, without the lock the build threads fought over. The same math: 26.6 million lookups compared bit for bit with OptiFine, with fire spreading, 0 differences;
  - **`reachedSectionsOnly` (on, with Nothirium):** when a source moves, only the sections the light reaches (7.5 blocks, at the old or the new position) and that have some block are rebuilt, instead of the cube of 8 sections. The image does not change;
  - **`updatePerBlock` (off):** the source only updates when it changes block or level. The light moves block by block; it stays off until seen in the game;
  - only with OptiFine HD U G5 and, for the last two, Nothirium 0.4.9, with the classes checked by SHA-256.
- **Third path of the traversal** (`nthshaders.cfg`, `gpuCullingSkipNoOpDirtyRebuilds`, on; with `gpuCulling`):
  - a section outside the list marked for a rebuild only asks for the visibility traversal if it could change: not if the last traversal did not even reach it, nor if it is plain air already built;
  - with fire spreading, traversals fell to ~1/5;
  - checked with blocks placed and removed near and far, flowing water, fire, exploration and teleports: the same images and the same holes with and without.
- **Build order** (`nthcompat.cfg`, `chunkCompileOrder`):
  - `inViewFirst` (default): when more builds are waiting than there are threads, those of sections on screen go first, and one that has waited over 0.5 s jumps ahead;
  - `inViewOnly` (aggressive): off-screen ones wait for the section to come back on screen. With a shader pack active it behaves as the default, with a warning in the log, because the shadow pass needs the off-screen sections;
  - `arrivalOrder`: as in Nothirium.

### Changed
- **Ice and Fire lookup cache** (B2): the hit counters only count in validation mode.
- **Distant dragon animation** (`distantDragonAnimation`): stays off and left the test list.

## [0.9.0] - 2026-10-24
Item 1 of 22/10, the frozen lake: the FPS drop was the animation of the Ice and Fire dragons. Includes 0.8.0. Full validation (harness, pixel comparison and numeric validation) in `docs/090-resultados.md`.

**Tested in the harness.** **Not tested in the game.**

### New
- **Dragon and sea serpent animation without looking parts up by name** (`nthcompat.cfg`, `iceandfire` section, `cacheTabulaPoses`, on):
  - **Before:** every frame, for each dragon, Ice and Fire 1.9.1 looked up each of the ~104 model parts by name, several times:
    - in each pose of the animation mix (`moveToPose`), up to ~10 lookups per part;
    - again in `setRotationAngles`, in the walk, fly and hover poses, and in `genderMob` and `isWing`.

    At the lake, with 8 ice dragons at ~150 blocks, this was 67% of the render thread's time.
  - **Now:**
    - the matching parts of each model and pose are stored once (in the part itself), and each lookup becomes an array read;
    - when an animation is not playing, its mix, which the original did only to call empty functions, is skipped.

    The same math, in the same order.
  - **Applies to** the fire dragon, the ice dragon and the sea serpent, the three Tabula creatures of 1.9.1. There is no lightning dragon in this version; the amphithere, the hippogryph and the other creatures do not use this pattern.
  - **In the harness** (lake, no shaders, distance 18):
    - standing FPS from ~270 to ~410–470;
    - entity CPU from 2.6 to 1.2–1.5 ms per frame;
    - Ice and Fire on the render thread from 67% to 6–7%.
  - **Checked:**
    - image: 5 captures with and 5 without, 0 different pixels on the three dragons (still, flying, biting);
    - numbers: the original and the cache running together, 0 differences in 7.8 million calls and 2.5 million lookups.
  - **Protection:**
    - only applies with Ice and Fire 1.9.1 and LLibrary 1.7.20, with the classes byte for byte equal to the checked ones (SHA-256);
    - the injections are counted in the final class, per animator.
  - **Issue for Ice and Fire:** `docs/upstream/issue-iceandfire-tabula.md` (not published).
- **Distant dragon animation** (`iceandfire.distantDragonAnimation`, **off**; `distantDragonAnimationDistance=64`, `distantDragonAnimationEveryFrames=2`):
  - from that distance, the dragon only recomputes its pose every N frames and repeats the last one on the others;
  - client only: position, AI and hitbox do not change;
  - from afar, at low FPS, wings and tail may move in little jumps;
  - after the fix above it gave no measurable FPS gain at the lake, which is why it comes off.

### Changed
- **`fixOptiRefineFloatBuffer` float view with no new garbage** (`nthcompat.cfg`, `fixOptiRefineFloatBufferReuseView`, on):
  - each `BufferBuilder`'s anchored view is kept and reused, and only rebuilt when the buffer grows;
  - before, two new views per vertex: 8–24 MB/s of garbage at the lake, now 0;
  - same image.
- **Air section built by itself, experimental and off** (`nthshaders.cfg`, `gpuCullingCompileEmptyDirectly=false`; with `gpuCulling`):
  - **The problem:** each section outside the candidate list marked for a rebuild redoes the whole visibility traversal (~1–2 ms). At the still lake, that is ~44 traversals per second.
  - **Three versions tested in the harness:**
    - building every "empty" section by Nothirium's criterion also built the never-built ones of the load, and the visible ones waited ~3 s;
    - building all air without blocks dropped traversals to ~30/s, but the traversal started crossing never-built air and finding ~50% more candidates after a teleport (−1 to −16% FPS there);
    - building only air already built left the load equal to 0.8.0, but without the gain at the lake, because the air the light marks there is the never-built kind.
  - **The third stays**, off, because it brings nothing by default. On, it does what is described above.
  - **Next package:** the work on traversals goes on there, with a better measurement:
    - **what will change:** at most one traversal per frame; a re-mark that does not change the result does not trigger a traversal;
    - **where to measure:** a dragon destroying blocks with many mobs on screen, and a simulated weak PC. The still lake on a fast processor does not show the cost.

### Fixed
- **Injection count:** the "bridge" call MixinExtras generates for `@Local` counted as an injection. `GroundScanMixin` (Additional Structures) showed `2/1` in the log and diagnostics, but applied only once, and generation did not change. It now shows `1/1`.

### Harness
- **Dragon scene** (`scene.dragons`): dragons placed client side only; the capture comes out with `partialTicks = 0`.
- **New measurements:**
  - section readiness through the GPU culling list, every frame, with holes at 0.5–10 s and `ready.ignoreColumns`;
  - traversals only in the measured window.
- **`addon.jar`**, to run a ready jar (e.g. the previous version's).

## [0.8.0] - 2026-10-21
Renderer package B1 (ideas from Aetherium Backport): per-direction face culling, with GPU culling. Details, bench numbers and risks in `docs/b1-culling-por-direcao.md`.

**Tested outside the game** (bench with the real GPU). **Not tested in the game.** Includes 0.7.0.

### New
- **Per-direction face culling** (`nthshaders.cfg`, `gpuFaceCulling`, on; with and without shaders; needs `gpuCulling`):
  - **Before:** each section was drawn whole, and OpenGL's face culling discarded back faces. But that only happened after the GPU had already processed their vertices.
  - **Now, at build time** (Nothirium's threads):
    - the faces of the opaque layers (solid and cutout) are split into 7 groups: up, north, west, any, east, south and down. The same vertices, just in another order;
    - a face only enters a direction group if its 4 vertices lie on a plane perpendicular to the axis and the winding (the vertex order that decides the front side) matches. With shaders, the normal must match too. The rest (X-shaped plants, slanted models) goes to "any", which is always drawn.
  - **Every frame, on the GPU:** a group is skipped when the camera is behind its outermost plane, with a margin of 1/16 of a block. Up to 4 commands per section and layer come out, instead of 1.
  - **The camera position** is the real one: with third person and view bobbing.
  - **With shaders:**
    - blocks in the pack's `block.properties` (leaves and plants in the wind, for example) stay in "any";
    - in a layer where the pack asks for back faces (`backFace.*`), nothing is skipped.
  - **Does not change:** water, stained glass and the rest of the translucent layer.
  - **Protection:**
    - if OpenGL's face culling is off on a layer, it goes back to being drawn whole on the next frame, and the log warns;
    - a section built earlier, or with any error, is drawn whole;
    - if an injection is missing, nothing is reordered, and the rest of GPU culling carries on as in 0.7.0.
  - **On the bench** (RTX 5060, 21,904 sections):
    - the compute shader goes from 0.044 to 0.048 ms per pass;
    - 64,000 camera and light positions, and no face towards them was skipped;
    - 1.7 commands per section and layer.
  - **Extra memory, distance 18:** with shaders, +20 MB on the GPU and +4 MB on the CPU; without shaders, half. The log writes the numbers for your distance.
  - **F3:**
    - `[nthshaders] Faces: X% of the opaque quads in the frustum drawn, ANY Y% of those built`;
    - the `[nthshaders] GPU: ...` line now counts commands.
- **Shadow pass by the light direction** (`gpuFaceCullingShadow`, on; with shaders and `gpuShadowLightCulling`):
  - only the groups facing the sun or the moon;
  - known risk: in a very deep cave, during the day, sunlight may get in (see the document, section 5).

### Changed
- **GPU culling offsets:** one per section, and not one per command of each region. This frees ~4 MB of GPU memory, which offsets part of what the extra commands use.

## [0.7.0] - 2026-10-18
First package of the "lighter server (tick)" project, in the server module (`nthrc`): the Phoenix Dive crash fix and a tick recorder for the Phase 1 recordings. Also works on a dedicated server.

**Not tested in the game.** Includes 0.6.0 (GPU Phase), which had not been tested in depth either.

### Fixed
- **Phoenix Dive crash** (Wonderful Enchantments 0.6.0; `nthrc.cfg`, `wonderfulenchantments` section, `fixPhoenixDiveCrash`, on):
  - **Before:** the server crashed with a `ConcurrentModificationException` in `PhoenixDiveEnchantment.onUpdate`.
    - In your game there were 7 crashes, from 12/09 to 03/10.
    - In the tests, it happened in 10 of 10 fast flights and in 0 of 20 runs of the other scenarios.
  - **Cause:** to clear the entities with Phoenix Dive boots that disappeared, the mod calls `particleTimers.values().remove(id)`.
    - This deletes the wrong entry: it looks for the id among the counters.
    - And it touches the map in the middle of the scan, which brings the server down.
  - **Now:**
    - the call no longer touches the map;
    - the id is noted only if the entity does not exist in any loaded world;
    - the noted entries leave by key, at the end of `onUpdate`.
  - **What stays the same:** the particles and the enchantment (the fall damage and the jump do not even go through this code).
  - **Version protection:** the fix only acts with Wonderful Enchantments 0.6.0 and a `PhoenixDiveEnchantment` byte for byte equal to the checked one (SHA-256). Otherwise it turns off and warns in the log.
  - **Issue text for the author:** `docs/upstream/issue-wonderfulenchantments-phoenixdive.md` (not published).

### New
- **Server tick recorder** (`nthrc.cfg`, `diagnostics` section, `recordTicks`, **off**):
  - **What it is for:** it gives what Flare does not: tick P99, % of ticks above 50 ms and the time until chunks are ready.
  - **`logs/nthrc-ticks.csv`, one line per tick:**
    - tick duration, measured from the start to the end of the server tick event (also catches the other mods acting on it);
    - time since the previous tick;
    - loaded chunks, entities, tile entities;
    - chunks loaded, unloaded and sent in the tick;
    - players and chunks around them not sent yet;
    - cost of the recorder itself on the server thread.
  - **`logs/nthrc-readiness.csv`:** for each player, after joining, teleporting, changing dimension or respawning, the time until the server has sent all chunks in range (view distance − 1, which is what the server sends). The same line goes to the log.
  - **Summary in the log when leaving the world:** average tick, P95, P99, % above 50 ms, maximum and the average and maximum cost of the recorder.
  - **Light:**
    - on the server thread, it only measures and puts numbers in a queue;
    - formatting and writing happen on a lowest-priority thread, buffered;
    - queue full: the line is dropped and counted, and the tick never waits;
    - the most expensive part (checking the chunks around each player) measured ~5 µs per player per tick, outside the game, at distance 18.
  - **Files:** up to 16 MB each (`maxFileSizeMB`, ~3 h of play), with 3 old ones kept (`oldFilesToKeep`). Each world opened starts new files.
  - **Turns on and off without restarting the game:** applies when opening the world, even with `enabled=false` (so you can measure with the optimizations off).

## [0.6.0] - 2026-10-17
GPU Phase of the "renderer with less CPU" project, all four packages at once: terrain culling on the GPU, also in the shadow pass, and optional Hi-Z. Details in `docs/fase-gpu.md`.

**Tested outside the game** (bench with the real GPU, comparing with the same math on the CPU). **Not tested or measured in the game.**

### New
- **Terrain culling on the GPU** (`nthshaders.cfg`, `gpuCulling`, on; with and without shaders):
  - **Before:** Nothirium's visibility traversal ran every frame and tested the frustum of each section on the CPU. With shaders, twice per frame. In the 06/10 profile it was 41–43% of the render thread's Java.
  - **Now:** the same Nothirium traversal runs without the frustum test and only when something changes:
    - the camera changes section;
    - a section opens a new path;
    - the fog grows;
    - in any case, every 0.5 s.
  - **Every frame,** a compute shader tests the sections the traversal visited against RenderLib's own frustum planes and the fog, and builds the draw commands (`glMultiDrawArraysIndirectCount`).
  - **Turning the camera costs no CPU.** Exploring, with new sections all the time, the traversal runs every 2 frames and the cost stays close to before.
  - **What gets built does not change:** Nothirium's traversal decides, and a section is only built when it enters the frustum (a list keeps those waiting).
  - **Needs OpenGL 4.6 or `ARB_indirect_parameters`** (current NVIDIA, AMD and Intel). Without that, with an OpenGL error in the first 2,000 calls or with any exception, it turns off and Nothirium carries on exactly as before.
  - **F3:** `[nthshaders] GPU: ...` lines with what the GPU drew. Nothirium's "Solid/Cutout/..." counts become those of the traversal list.
  - **On the bench** (RTX 5060): ~0.02 ms of GPU per pass with 21,904 sections (distance 18).
- **Shadow pass on the GPU** (`gpuShadowLightCulling`, on; with shaders):
  - Tests each section against the shadow map volume (the planes of OptiFine's shadow matrix) and against RenderLib's extended frustum.
  - Replaces `limitShadowPass` in that pass, which built that volume on the CPU every frame (~8% of the render thread).
  - Off, the shadow pass stays as in 0.5.0.
- **Two-phase Hi-Z** (`gpuOcclusionCulling`, **off**, experimental):
  - In the main pass, first draws the solid terrain of what was visible in the previous frame.
  - From it, builds a depth pyramid and only draws the rest that is not behind it.
  - Clouds and sky never hide terrain. The test is conservative: on the bench, 0 sections hidden by mistake.
  - Costs ~0.12 ms of GPU per frame at 1080p on the RTX 5060 (2 to 3 times that on an RX 580); gains in caves, forests and mountains.
  - Off by default: an error here would hide terrain, and the test in the game is missing.

### Changed
- **Smart Animations with GPU culling:** Naughthirium's marking of animated textures is no longer done in the traversal every frame, and is now done once per tick, for all sections in the list. The number of active textures in F3 (`A: x/676`) is a little higher than in 0.5.0, because those of the sections behind you count.

### Notes
- **DSA (GL 4.5):** evaluated, nothing to do. On the new path, the CPU issues some 25 OpenGL commands per pass, and uploading sections to the GPU is already done by Nothirium with DSA. See `docs/fase-gpu.md`, section 5.
- **Vendors:** nothing is NVIDIA-only. Known risk: AMD was not tested with `GL_QUADS` in the "Count" multi-draw. If the driver refuses, GPU culling turns itself off in the first frames and the log says why.

## [0.5.0] - 2026-10-17
Package 2 of the "renderer with less CPU" project: OptiFine's Smart Animations option starts working on OptiRefine. And the Pulsar optimization leaves, since Pulsar 0.5.0 itself solved it.

### New
- **Smart Animations on OptiRefine** (`nthcompat.cfg`, `fixSmartAnimations`, on):
  - **Before:** the Smart Animations option (Video Settings → Performance) did nothing, because OptiRefine never calls `SmartAnimations.update()`. The ~676 animated textures of the atlas went to the GPU every tick (~495 uploads per tick), 98% of them with nothing nearby.
  - **Now:** only the textures drawn since the previous tick go.
  - **Measured on 13/10** (Mob Ascension, heavy area, distance 18, no shaders), with a test hook turning Smart Animations on: client tick 3.3 → 0.8 ms; frame time p95 5.8 → 3.4 ms; 11–20 of the 676 textures stayed active. This package's code has not been measured or tested in the game yet.
  - **What the addon does:**
    - calls `update()` at the start of `loadRenderers`, as OptiFine does;
    - fixes a race in OptiFine itself with the shadow pass: with shaders, sections built while the shadow pass runs did not note their animated textures, and the animation stopped on them;
    - marks what OptiRefine does not mark: items in the GUI (and all of them, without shaders), `renderModelBrightnessColorQuads` models (blocks in minecarts, in the enderman's hand, in some TESRs) and textures whose coordinates are requested at draw time (fluid in a tank, block particles, fire on a mob);
    - a texture that shows up again receives the current frame on the next tick; the animation time never stops.
  - **With Nothirium, it needs Naughthirium:** it is what marks the terrain sections. Without it, the fix does not turn on.
  - **With Smart Animations off in the game, nothing changes** (everything is uploaded every tick, as before).
  - **Version lock:**
    - each injection is checked in the final class, and the package only turns on if all apply;
    - if OptiRefine starts calling `update()` or marking `renderModelBrightnessColorQuads`, the addon does not repeat it.
- **`smartAnimationsMarking`** (default `sprite`): how to mark what OptiRefine does not mark.
  - `sprite`: by the texture of each quad and by whoever asks for its coordinates.
  - `coordinates`: by the texture coordinates of each immediate draw with the atlas bound. Also catches whoever keeps the coordinates and draws without asking again.
  - Measured on 13/10: 0.0015 ms (`sprite`) and 0.0024 ms (`coordinates`) per frame, with the same marks.
- **`smartAnimationsAlwaysAnimate`** (empty): textures always updated, for something no marking sees and that stays still. Texture name, or with `*` at the end as a prefix (e.g. `rustic:blocks/fluids/*`).
- **To compare:**
  - turn Smart Animations off in the game (applies at once);
  - or `fixSmartAnimations=false` (applies after restarting; the game's option goes back to doing nothing, as in 0.4.0).

### Removed
- **`pulsar.rememberLastChunk` (`nthrc.cfg`):**
  - Pulsar 0.5.0 (and 0.5.2, checked in the code) mixes the light map key (`mixChunkKey`, the idea `docs/upstream/pr-pulsar.md` proposed).
  - It also only asks for the 6 neighbours when there is contextual light or the Fluidlogged API.
  - With that, the expensive lookup the optimization attacked is gone.
  - The `pulsar` section leaves `nthrc.cfg` by itself, and there is no more Pulsar version lock to redo for each new version.

### Notes
- **Cleanroom 0.7.8:** 0.4.0 ran on it with no problem (log from your instance, 17/10). The addon still has no version lock tied to Cleanroom.

## [0.4.0] - 2026-10-15
Package 1 of the "renderer with less CPU" project: Nothirium's chunk build threads, fewer and at normal priority.

### New
- **Nothirium's chunk build threads** (`nthcompat.cfg`, `chunkCompileThreads`, default `0` = automatic):
  - **Before:** Nothirium uses processors − 2 threads (14 on a 16-thread processor). They have the maximum priority, the same as the client and the server, and fight them for cores and cache.
  - **Now, on automatic:** processors ÷ 4, at least 2 and at most what Nothirium would use. That gives 4 on a 16-thread processor and 2 on an 8-thread one (4 cores with SMT).
  - **The formula is provisional:** the test on a 4-core/8-thread PC is still missing.
  - `-1` = Nothirium's number; any other number sets that.
- **Priority of those threads** (`chunkCompileThreadPriority`, default `5` = normal, below the client and the server; `10` = as before).
- **Measured on 13/10** (Mob Ascension, distance 18, Mellow, 2 runs each; 4 threads with the old priority and buffer limit):
  - exploring new terrain: FPS ~222 → ~270, 1% low ~46 → ~66, server 37 → 31 ms per tick;
  - flying fast over ready terrain: similar, with the server 26 → ~21 ms per tick;
  - the time until everything around is ready when joining the world and after a teleport did not change (teleport: 8.0 s with 14, 6 and 4 threads; what limits it is the server sending the chunks).
  - Normal priority had no measured effect on this PC (16 threads, almost no queue).
- **To go back to how it was:** `chunkCompileThreads=-1` and `chunkCompileThreadPriority=10`.
- **Applies from the next renderer creation** (F3+A or re-entering the world).
- **If the hook does not catch** (a different Nothirium), nothing changes. The state goes to `nthshaders-diagnostics.txt`.

### Changed
- **`limitChunkCompileBuffers`:** the limit on buffer sets now uses the real number of build threads × `chunkCompileBuffersPerThread`. With 4 threads and the default 2, that is 8 sets; before it was 28.

### Notes
- **Cleanroom 0.7.6:** fixed `ObjectHolderRef#apply`. The class is now initialized (`Index.ensureInitialized`) before the `@ObjectHolder` field is written. In 0.7.4, initializing the class could erase the value, and items lost their textures (FTB Quests) or mods did not load (Apotheosis).
  - Checked in the 0.7.6 code; the addon needs no fix of its own.
  - The addon has no version lock tied to Cleanroom. It compiles against 0.6.13 and only uses Forge APIs and mixins, which remain in 0.7.6.

## [0.3.0] - 2026-10-08
Optimizations from the Mob Ascension performance survey: lighter shadow pass, Biomes O' Plenty fog and Pulsar 0.4.0 accepted.

**Flying** (Mob Ascension, distance 16, Mellow, 3 runs before × 3 after), all three together: **185 → 257 FPS (+39%)**. Frames below 120 FPS fell from 11.8% to 2.5%.

### New
- **Lighter shadow pass (`nthshaders.cfg`, `limitShadowPass`, on):**
  - With shaders, the shadow pass drew the whole view distance: ~7,000 sections per frame at distance 16, 90% of them outside the shadow map.
  - Now it draws only the volume OptiFine itself would draw without Nothirium, along the sun's direction, up to the shadow distance the pack asks for (`shadowDistanceRenderMul`; Mellow asks for 96 blocks).
  - Measured: **−80% sections in the shadow**, and the GPU from 5.3 to 3.8 ms per frame.
  - Pack with no limit: nothing changes.
  - The shadows come out the same: at late afternoon, with a low sun, 5 runs with and 5 without the limit differ as much as two runs on the same side.
- **Biomes O' Plenty fog (`nthrc.cfg`, `biomesoplenty.fogScanOncePerTick`, on; client):**
  - It queried 41×41 biomes on every fog event, ~7 per frame with shaders, because its cache never hits (it compares the rounded position with the unrounded one).
  - Now the result holds until the next tick, for the same block and the same render distance: 97.6% hits.
  - The fog comes out the same: 0 differences in 89 thousand hits checked.

### Changed
- **Pulsar 0.4.0 accepted** by the `pulsar.rememberLastChunk` optimization. The class it touches is identical to 0.3.2's; checked: 0 differences in 114 million hits.

## [0.2.0] - 2026-10-02
First numbered version. Brings the server worldgen module (`nthrc`, a provisional name), and the jar now loads on a dedicated server.

### New
- **Version and commit in plain sight:**
  - in the jar name;
  - in `mcmod.info` (the mod list shows the commit in the description);
  - in the addon's first log line, for example `[nthshaders] Nothirium Shaders 0.2.0 (commit abc1234), client`;
  - in the header of `nthshaders-diagnostics.txt`.
- **This `CHANGELOG.md`**, which also goes in the package.
- **Server worldgen module (`nthrc`):**
  - Runs where the world is generated: on the dedicated server or the game's integrated server.
  - Does not need Nothirium or OptiFine, and whoever joins a server that has it does not need it.
  - Config in `config/nthrc.cfg`, with one section per mod. All optimizations come on.

  | Mod (validated version) | Option | What it does |
  |---|---|---|
  | Recurrent Complex Volts 2.0.1.2 + IvToolkit 1.3.3 | `stopMazeSearchAfterTimeout` | The build of a maze abandoned by `mazeTimeout` now stops. Before, it kept running for over 4 min; now it stops ~25–30 ms later. |
  | | `rejectBeforePreparation` | Checks height and overlap **before** preparing the structure (`StatueTemple` spent ~3 s only to be refused). |
  | Pulsar 0.3.2 | `rememberLastChunk` | Remembers the last chunk looked up in the light map: ~10% of the server thread generating a new world, ~5% playing. |
  | RTG Continued 1.0.7 | `skipUselessVoronoiCells` | Skips the noise cells that do not change the result: ~3%. |
  | Additional Structures 2.5.0 | `groundScanFromTopSection` | Looks for the ground from the terrain, and not from the top of the world: ~3.4%. |

  - **In total:** generating a new 64×64-chunk region in the tested modpack went from 41.5 s to 34.1 s (−18%).
  - **Same result as the original:** each optimization was validated by running the original alongside, with 0 differences.
  - **Per-mod version safeguard:** checks the mod's version and the fingerprint (SHA-256) of the classes it touches. With the mod absent or of another version, only its optimizations turn off and warn in the log; `forceUnknownVersion=true` turns them on anyway.
  - **When the server stops,** the log records what each optimization did in the session.

### Changed
- **The jar loads on a dedicated server** (`ModSide: CLIENT` left the manifest). There, the shader part and `nthcompat` stay inactive and only `nthrc` acts.
- **Nothirium and RenderLib became optional.** Without them the game starts: only the shader part turns off, and `nthcompat` keeps the fixes that do not depend on them.
- **`stopMazeSearchAfterTimeout` changed section:** in the 01/10 test packages it was in `general` in `nthrc.cfg`; now it is in `recurrentcomplex`. The old value is carried over by itself.

### Removed
- **Recurrent Complex's "proposal D"** (placement when no possible height remains), which was in a 01/10 test package. The measured gain was ~1 ms per region.

## [0.1.x] - 2026-09-22 to 2026-10-01
The shaders and compatibility phase (phases 0 to 5 and the fixes that followed).
- All packages of this phase came out as "0.1.0", with no number per package.
- Those of 30/09 and 01/10 already carried preliminary versions of the server module, described in 0.2.0.
- Details in the reports in `docs/`.

### Shaders with Nothirium's GL 4.3 (`nthshaders`)
- **GL 4.3 with shaders (Phase 2):** with an OptiFine shader pack (via OptiRefine), Nothirium stays on the GL 4.3 renderer, instead of falling back to GL 1.5.
- **Terrain in place (Phase 3):**
  - The pack's vertex shader is rewritten at compile time to add each chunk's offset.
  - If the rewrite fails, that program is drawn chunk by chunk, with no error on screen.
- **Shadow pass with synchronized buffers (Phase 4):** 0 writes with the GPU busy.
- **FPS** (1280×720, distance 12):
  - Mellow: 684 against 494 on GL 1.5.
  - Sildur's Vibrant Extreme: tied average, with a better 1% low (350 against 302).
- **Robustness (Phase 5):**
  - Automatic fallback to GL 1.5 if an OpenGL error shows up on the addon's path.
  - GPU recorded in the log, and `logs/nthshaders-diagnostics.txt` to attach to issues.
- **Version safeguard:**
  - With another Nothirium, OptiFine or OptiRefine, the shader part turns off and warns in the log (`forceOptiRefineFingerprint` accepts another OptiRefine).
  - A mixin that does not apply whole does not act.
- **Config:** `config/nthshaders.cfg` (`enabled`, `forceOptiRefineFingerprint`, `measureBufferSync`).

### Compatibility (`nthcompat`)
- `fixRenderLibOptiRefine`: the game did not start with RenderLib + OptiRefine.
- `fixUpdateClouds`: crash in OptiRefine's `updateClouds` with Nothirium.
- `fixEntityRendering`: entities and tile entities vanished (RenderLib's culling).
- `fixOptiRefineFloatBuffer` (temporary, OptiRefine defect): bent chest lid and stretched strips coming out of mobs, with shaders.
- `limitChunkCompileBuffers` and `chunkCompileBuffersPerThread`: limit Nothirium's compile buffer queue.
  - Without the limit, it grew without returning memory and could bring the game down.
  - With it, the peak fell from 2.2–3.5 GB to ~475 MB.
- `fixSmartLeavesVertexData` (26/09): inside-out leaves with LoliASM + OptiFine, with or without Nothirium.
- `fixOptiRefineAccPlaceholders` (29/09; temporary, OptiRefine defect): crash when releasing zoom and in water/lava with custom resource pack colours.

### Package
- **`./gradlew distTeste`** builds the test package:
  - the addon as a release build, with no test hooks;
  - OptiRefine compiled from source;
  - the `README.txt`.
