# GPU and driver compatibility (Gadolinium)

So far Gadolinium has only run on one GPU: **NVIDIA GeForce RTX 5060, driver 621.14, Windows**. Everything else below
comes from reading the code and the OpenGL specification, without a test. Reports from other GPUs are welcome (see
[CONTRIBUTING.md](../CONTRIBUTING.md)).

## Fallback paths

Each step works on its own, with no config change. The log says which one the game is on.

| The GPU has | Terrain without shaders | Terrain with shaders | What the log says |
|---|---|---|---|
| Compute shaders, `glMultiDrawArraysIndirectCount` (GL 4.6 or `ARB_indirect_parameters`) and the compute limits | GPU culling | GPU culling | `GPU culling: ok (...). GPU: ...` |
| GL 4.3 without the indirect count, or lower limits | Nothirium's GL 4.3 | the addon's shader mode, with plain GL 4.3 multi-draw | `GPU culling OFF: <reason>. The terrain goes through Nothirium's own path, without GPU culling. GPU: ...` (WARN) |
| Less than GL 4.3 | whatever Nothirium picks (GL 4.2, 2.0, 1.5) | Nothirium's GL 1.5, chunk by chunk | `With shaders, the terrain goes through Nothirium's original path...` (WARN) |

Failures while playing, without a crash:

| What fails | What happens |
|---|---|
| An OpenGL error in GPU culling (first 2,000 calls) | only GPU culling turns off, for the rest of the session; Nothirium carries on |
| An OpenGL error in Hi-Z (first 600 uses) | only Hi-Z turns off; GPU culling carries on |
| An OpenGL error in shader mode (first 1,000 draws) | back to GL 1.5 with shaders, for the rest of the session |
| A shader pack program does not accept the rewrite | only that program is drawn chunk by chunk |
| A compute shader does not compile | the reason, with the driver's log, goes to `latest.log` |
| A mixin does not apply in full | its part does not act |

An option that is on in the config but did not start (a refused compute shader, a mixin that did not apply in full) also
shows once in the chat and in F3.

## What depended on NVIDIA behaviour

| Point | Status |
|---|---|
| The offset declaration came before the pack's `#extension` lines. NVIDIA accepts it; AMD and Mesa refuse it | **fixed in 1.0.0-rc2.** Mellow and Sildur's use no `#extension`; in Sundial Lite it comes after the pack's own code, and nothing changes there |
| `glMultiDrawArraysIndirectCount` received the whole size of the region (up to 270,000 commands). GPUs without a native count emit one conditional draw per possible command | **fixed in 1.0.0-rc2:** the real limit of the frame is passed |
| Buffers written by the GPU were created as `GL_DYNAMIC_DRAW`. Other drivers may put them in memory that is slower for the GPU | **fixed in 1.0.0-rc2:** `GL_DYNAMIC_COPY` |
| A barrier was missing before copying the F3 counts | **fixed in 1.0.0-rc2:** the F3 numbers could be stale |
| The offset attribute at location 6, picked from NVIDIA's aliasing table | works on all of them: AMD and Intel have no such aliasing |
| `GL_QUADS` in indirect draws with a count | only tested on NVIDIA. If the driver refuses it, an OpenGL error turns GPU culling off. If it accepts it and gets slow, use `gpuCulling=false` |
| Depth `glCopyImageSubData` (Hi-Z without shaders) | only tested on NVIDIA. On an error, only Hi-Z turns off |
| The GPU clock used by `gpuOcclusionAuto` | if it does not match the query's clock, the measure turns itself off and the log says so |
| The shader pack's own GLSL | a pack written only against NVIDIA can fail on another GPU, with or without the addon |

## What cannot be known without a test

- Whether GPU culling pays off on an integrated GPU such as Intel Iris Xe. The compute work and the extra commands are a cost
  there: compare with `gpuCulling=false`.
- The cost of `glGetInteger` and `glGetError` on other drivers.
- How much video memory is left on an integrated GPU: at render distance 32 the buffers add up to about 100 MB; at 12, about 15 MB.

## Reporting from another GPU

Send `logs/latest.log` and `logs/gadolinium-diagnostics.txt`, the GPU and driver version, and the FPS in the same spot with
`gpuCulling=true` and `gpuCulling=false` (F3 shows both the FPS and the GPU culling line).
