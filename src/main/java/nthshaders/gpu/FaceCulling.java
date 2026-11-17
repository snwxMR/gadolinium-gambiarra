// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.mc.util.BlockRenderLayerUtil;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import nthshaders.core.Diagnostics;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * Per-direction face culling (gadolinium.cfg: gpuFaceCulling, gpuFaceCullingShadow), with GPU culling.
 *
 * Why: OpenGL face culling only discards back faces after the GPU has already processed their vertices. In terrain seen
 * from one point, about half of the faces of the opaque layers face away.
 *
 * How (derived from Aetherium, LGPL-3.0, rewritten for Nothirium's GL43 and GPU culling):
 *  - at build time (Nothirium's threads), the quads of the SOLID, CUTOUT and CUTOUT_MIPPED layers are reordered into 7
 *    direction groups (FaceSorter); the layout (where each group starts and the outermost plane of each) travels with the
 *    VBO part (RenderChunkTaskCompileGpuMixin, VBOPartGpuMixin);
 *  - every frame, the culling compute shader (cull.comp) skips the groups with the camera behind the plane (1/16 block of
 *    slack) and emits up to 4 commands per section and layer. In the shadow pass, the light direction decides;
 *  - the mesh stays valid as a whole: without a layout, disabled or on error, each layer is drawn whole, as before.
 *
 * The translucent layer does not change (Nothirium sorts it far to near).
 */
public final class FaceCulling {

    private FaceCulling() {
    }

    /** There is a GL43 renderer with GPU culling (GpuTerrain) in use: only then is reordering at build time worthwhile. */
    private static volatile boolean terrainActive;
    private static volatile String failure;

    // Statistics (all threads).
    private static final AtomicLong sortedLayers = new AtomicLong();
    private static final AtomicLong untouchedLayers = new AtomicLong();
    private static final AtomicLongArray groupQuads = new AtomicLongArray(FaceLayout.GROUPS);

    static void onTerrain(boolean active) {
        terrainActive = active;
    }

    /** Whether to reorder now (build threads): config, injections checked, GPU renderer and no failure. */
    static boolean sorting() {
        return NthConfig.gpuFaceCulling && terrainActive && failure == null && GpuInjections.faceOk();
    }

    /** Whether to ask for per-direction commands in the culling (render thread). */
    static boolean drawing() {
        return NthConfig.gpuFaceCulling && failure == null && GpuInjections.faceOk();
    }

    static void fail(String where, Throwable t) {
        if (failure != null) {
            return;
        }
        failure = where + ": " + t;
        Status.LOG.error("[gadolinium] Per-direction face culling disabled for the rest of the session ({}). Layers are drawn whole.",
                failure, t);
        if (nthshaders.core.NthConfig.gpuFaceCulling) {
            nthshaders.common.StartupReport.failed("gpuFaceCulling", String.valueOf(failure));
        }
        Diagnostics.write("per-direction face culling disabled: " + failure);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Build (Nothirium's threads)

    private static final class Compile {
        RegionRenderCacheBuilder pack;
        FaceLayout[] layouts;
        final FaceSorter.Result result = new FaceSorter.Result();
    }

    private static final ThreadLocal<Compile> COMPILE = ThreadLocal.withInitial(Compile::new);

    /** Start of a section's compileSection(pack). */
    public static void beginCompile(RegionRenderCacheBuilder pack) {
        Compile c = COMPILE.get();
        c.pack = pack;
        c.layouts = null;
    }

    /** A layer finished (finishDrawing) with vertices: the opaque ones are reordered here, still on the build thread. */
    public static void onLayerFinished(BufferBuilder builder) {
        Compile c = COMPILE.get();
        if (builder == null || c.pack == null || !sorting()) {
            return;
        }
        try {
            int layer = -1;
            for (int i = 0; i < 3; i++) {
                if (c.pack.getWorldRendererByLayer(BlockRenderLayerUtil.ALL[i]) == builder) {
                    layer = i;
                    break;
                }
            }
            if (layer < 0) {
                // The translucent layer (or a BufferBuilder not from this pack): left as it is.
                return;
            }
            int stride = builder.getVertexFormat().getSize();
            int vertices = builder.getVertexCount();
            ByteBuffer data = builder.getByteBuffer();
            BitSet waving = stride == FaceSorter.STRIDE_SHADERS ? WavingBlocks.ids() : null;
            if (FaceSorter.sort(data, vertices, stride, waving, c.result)) {
                if (c.layouts == null) {
                    c.layouts = new FaceLayout[4];
                }
                c.layouts[layer] = c.result.layout;
                sortedLayers.incrementAndGet();
                for (int g = 0; g < FaceLayout.GROUPS; g++) {
                    groupQuads.addAndGet(g, c.result.quads[g]);
                }
            } else {
                untouchedLayers.incrementAndGet();
                groupQuads.addAndGet(FaceLayout.ANY, vertices / 4);
            }
        } catch (Throwable t) {
            fail("build", t);
        }
    }

    /** Before the build goes to the render thread: this section's layouts (null = none). */
    public static FaceLayout[] takeLayouts() {
        Compile c = COMPILE.get();
        FaceLayout[] layouts = c.layouts;
        c.layouts = null;
        c.pack = null;
        return layouts;
    }

    // ------------------------------------------------------------------------------------------------------------
    // VBO part (render thread)

    /** The new part of a layer receives the layout of the mesh that just went to the VBO. */
    public static IVBOPart attach(IVBOPart part, FaceLayout[] layouts, ChunkRenderPass pass) {
        if (part instanceof FaceLayoutHolder) {
            FaceLayout layout = layouts != null && pass.ordinal() < layouts.length ? layouts[pass.ordinal()] : null;
            ((FaceLayoutHolder) part).nthgpu$setFaceLayout(layout);
        }
        return part;
    }

    /** Layout of a part, only if it describes exactly its mesh. */
    static FaceLayout layoutOf(IVBOPart part) {
        if (!(part instanceof FaceLayoutHolder)) {
            return null;
        }
        FaceLayout layout = ((FaceLayoutHolder) part).nthgpu$faceLayout();
        return layout != null && layout.total == part.getCount() ? layout : null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // OpenGL face culling state (what guarantees that skipping a back-facing group does not change the image)

    private static Object cullState;
    private static Field cullEnabled;
    private static boolean cullReflectionTried;
    private static final boolean[] cullOffLayers = new boolean[3];
    private static final boolean[] cullOffLogged = new boolean[3];

    /** GL_CULL_FACE on now, from GlStateManager's cache (no glGet); null = could not read. */
    private static Boolean cullFaceEnabled() {
        if (!cullReflectionTried) {
            cullReflectionTried = true;
            try {
                for (Field f : GlStateManager.class.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType().getName().endsWith("$CullState")) {
                        f.setAccessible(true);
                        Object state = f.get(null);
                        for (Field g : f.getType().getDeclaredFields()) {
                            if (g.getType().getName().endsWith("$BooleanState")) {
                                g.setAccessible(true);
                                Object bool = g.get(state);
                                for (Field h : g.getType().getDeclaredFields()) {
                                    if (h.getType() == boolean.class) {
                                        h.setAccessible(true);
                                        cullState = bool;
                                        cullEnabled = h;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                cullEnabled = null;
            }
        }
        if (cullEnabled == null) {
            return null;
        }
        try {
            return cullEnabled.getBoolean(cullState);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Layers (bits 0..2) where the main pass can skip groups. */
    static int mainPassLayers(boolean shaderMode) {
        int mask = 0;
        for (int l = 0; l < 3; l++) {
            if (cullOffLayers[l]) {
                continue;
            }
            if (shaderMode && net.optifine.shaders.Shaders.isRenderBackFace(BlockRenderLayerUtil.ALL[l])) {
                // The pack asks for back faces on this layer (backFace.* in shaders.properties).
                continue;
            }
            mask |= 1 << l;
        }
        return mask;
    }

    /**
     * When drawing a main pass layer where groups were skipped: face culling must be on. If it is not, the layer is drawn whole
     * from the next frame on (and the log says so).
     */
    static void checkCullOnDraw(int layer) {
        if (layer < 0 || layer >= 3 || cullOffLayers[layer]) {
            return;
        }
        Boolean on = cullFaceEnabled();
        if (on == null) {
            on = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        }
        if (!on) {
            cullOffLayers[layer] = true;
            if (!cullOffLogged[layer]) {
                cullOffLogged[layer] = true;
                Status.LOG.warn("[gadolinium] Per-direction culling: layer {} is drawn with OpenGL face culling off; it will be drawn "
                        + "whole.", ChunkRenderPass.ALL[layer]);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Diagnostics

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", "gpuFaceCulling=" + NthConfig.gpuFaceCulling + ", gpuFaceCullingShadow=" + NthConfig.gpuFaceCullingShadow);
        m.put("injections", GpuInjections.faceOk() ? "ok" : "missing: " + GpuInjections.faceProblem());
        m.put("failure", failure);
        m.put("layersSorted", sortedLayers.get());
        m.put("layersAnyOnly", untouchedLayers.get());
        m.put("quadsPerGroup", groupShares());
        m.put("layersWithoutFaceCulling", java.util.Arrays.toString(cullOffLayers));
        m.put("wavingBlocks", WavingBlocks.describe());
        return m;
    }

    /** "UP 21%, NORTH 9%, ..." of the opaque quads built since the start of the session. */
    static String groupShares() {
        long total = 0;
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            total += groupQuads.get(g);
        }
        if (total == 0) {
            return "no quads built yet";
        }
        String[] names = { "UP", "NORTH", "WEST", "ANY", "EAST", "SOUTH", "DOWN" };
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            if (g > 0) {
                sb.append(", ");
            }
            sb.append(names[g]).append(' ').append(Math.round(groupQuads.get(g) * 100.0 / total)).append('%');
        }
        return sb.toString();
    }

    static long anyPercent() {
        long total = 0;
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            total += groupQuads.get(g);
        }
        return total == 0 ? -1 : Math.round(groupQuads.get(FaceLayout.ANY) * 100.0 / total);
    }

    static String failure() {
        return failure;
    }

}
