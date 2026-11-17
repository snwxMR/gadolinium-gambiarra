// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import meldexun.nothirium.util.Direction;
import meldexun.nothirium.util.VisibilitySet;
import meldexun.renderlib.util.Frustum;
import meldexun.renderlib.util.Vec4;
import net.minecraft.client.Minecraft;
import nthshaders.core.Diagnostics;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * Nothirium terrain culling on the GPU (gadolinium.cfg: gpuCulling, gpuShadowLightCulling, gpuOcclusionCulling).
 *
 * Why: Nothirium's visibility traversal (AbstractChunkRenderer.setup) runs every frame, in both passes with shaders, and
 * tests each section's frustum on the CPU. It was 41-43% of the render thread's Java time when profiled (RenderLib's frustum
 * test, 14-15%, inside it).
 *
 * How it works (GpuTerrain):
 *  - Nothirium's traversal still decides what is built and what can be seen through what, but runs WITHOUT the frustum test
 *    and only when something changes (the camera changes section, a section opens a new path, the fog grows, every 0.5 s);
 *    the result (the visited sections, "candidates") goes to a GPU buffer;
 *  - every frame, a compute shader tests the candidates against RenderLib's frustum (the same planes) and the fog and builds
 *    the draw commands (glMultiDrawArraysIndirectCount); in the shadow pass, also against the shadow map volume (the light);
 *  - a dirty section is only built when it enters the frustum, as in Nothirium (the deferred build list);
 *  - optional (gpuOcclusionCulling): two-phase Hi-Z in the main pass (HiZ).
 *
 * Needs compute shaders (GL 4.3) and glMultiDrawArraysIndirectCount (GL 4.6 or ARB_indirect_parameters). Without them, or
 * on any error, Nothirium carries on exactly as if the addon did not do this. The code does not ask for the vendor, but it was
 * only tested on NVIDIA.
 *
 * Everything here runs on the render thread.
 */
public final class GpuCulling {

    private GpuCulling() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // General state

    private static volatile Boolean capable;
    private static String capability = "not checked yet";
    private static String limits = "not read yet";
    private static long maxStorageBlock = Long.MAX_VALUE;
    private static boolean gl46;
    private static GpuPrograms programs;

    /** Off for the rest of the session (OpenGL error, exception). */
    private static volatile String failure;

    /** The current renderer (only one at a time). */
    private static GpuTerrain active;

    /** Client ticks (to mark the animated textures once per tick). */
    static int clientTicks;

    /** Validation: checks for OpenGL errors in the first calls after each new renderer. */
    private static int checksLeft;
    private static final int CHECKS = 2000;
    private static int foreignErrors;

    /** Traversal frame: its own values, far from RenderLib's (increasing positives) and from the shadow's negatives. */
    private static int bfsFrame = 0x40000000;

    private static boolean injectionProblemLogged;

    /** On the init of a GL43 renderer: whether to create its GpuTerrain (ChunkRendererGL43GpuMixin). */
    public static boolean usableForNewRenderer() {
        if (NthConfig.gpuCulling && !GpuInjections.allOk() && !injectionProblemLogged) {
            injectionProblemLogged = true;
            Status.LOG.warn("[gadolinium] GPU culling not enabled: incomplete injections ({})", GpuInjections.problem());
            nthshaders.common.StartupReport.failed("gpuCulling", "incomplete injections: " + GpuInjections.problem());
        }
        return usable();
    }

    public static void failInit(Throwable t) {
        fail("init", t);
    }

    /** Usable now: config, injections, GPU capability, programs and no failure. */
    static boolean usable() {
        return NthConfig.gpuCulling && GpuInjections.allOk() && failure == null && capable();
    }

    /** Called per entity and per section: after the first time it only reads the result. */
    static boolean capable() {
        Boolean known = capable;
        return known != null ? known : checkCapable();
    }

    /**
     * Once, with the OpenGL context: GL 4.3 (compute), MultiDrawIndirectCount, the compute limits and the programs compile.
     * Without any of them, the terrain goes through Nothirium's own path (GL 4.3 or whatever the card has), as without GPU
     * culling: the log gives the reason and the card.
     */
    private static synchronized boolean checkCapable() {
        if (capable != null) {
            return capable;
        }
        boolean result = false;
        try {
            GLCapabilities caps = GL.getCapabilities();
            boolean count = caps.OpenGL46 || caps.GL_ARB_indirect_parameters;
            gl46 = caps.OpenGL46;
            if (!caps.OpenGL43) {
                capability = "the GPU/driver does not have OpenGL 4.3 (compute shaders)";
            } else if (!count) {
                capability = "no glMultiDrawArraysIndirectCount (neither OpenGL 4.6 nor ARB_indirect_parameters)";
            } else {
                String limits = computeLimitsProblem();
                if (limits != null) {
                    capability = limits;
                } else {
                    programs = new GpuPrograms();
                    if (!programs.ok()) {
                        capability = "the compute shaders did not compile: " + programs.problem;
                    } else {
                        capability = "ok (" + (gl46 ? "OpenGL 4.6" : "ARB_indirect_parameters") + ")";
                        result = true;
                    }
                }
            }
        } catch (Throwable t) {
            capability = "failed checking: " + t;
        }
        capable = result;
        Diagnostics.captureGpu();
        String gpu = Diagnostics.gpu();
        if (result) {
            Status.LOG.info("[gadolinium] GPU culling: {}. GPU: {}", capability, gpu);
        } else {
            Status.LOG.warn("[gadolinium] GPU culling OFF: {}. The terrain goes through Nothirium's own path, without GPU culling. GPU: {}",
                    capability, gpu);
            if (nthshaders.core.NthConfig.gpuCulling) {
                nthshaders.common.StartupReport.failed("gpuCulling", String.valueOf(capability));
            }
            Diagnostics.write("GPU culling off: " + capability);
        }
        return result;
    }

    /** cull.comp uses groups of 1024 invocations, 20 KB of shared memory and 7 storage blocks. null = it fits. */
    private static String computeLimitsProblem() {
        int invocations = GL11.glGetInteger(GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
        int sizeX = GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0);
        int shared = GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
        int blocks = GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS);
        maxStorageBlock = GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        limits = "invocations " + invocations + ", group " + sizeX + ", shared memory " + shared + ", blocks " + blocks + ", largest block "
                + maxStorageBlock / (1024 * 1024) + " MB";
        if (invocations < 1024 || sizeX < 1024 || shared < 20480 || blocks < 7) {
            return "compute limits below what is needed (" + limits + ")";
        }
        return null;
    }

    /** Largest size of a storage block (GpuTerrain checks the command buffer). */
    static long maxStorageBlock() {
        return maxStorageBlock;
    }

    static boolean gl46() {
        return gl46;
    }

    static GpuPrograms programs() {
        return programs;
    }

    /** Error or exception on the GPU culling path: off for the rest of the session; Nothirium carries on as before. */
    static void fail(String where, Throwable t) {
        if (failure != null) {
            return;
        }
        failure = where + ": " + t;
        Status.LOG.error("[gadolinium] GPU culling off for the rest of the session ({}). Nothirium carries on without it.", failure, t);
        nthshaders.common.StartupReport.failed("gpuCulling", failure);
        Diagnostics.write("GPU culling off: " + failure);
    }

    static void fail(String where, String what) {
        if (failure != null) {
            return;
        }
        failure = where + ": " + what;
        Status.LOG.error("[gadolinium] GPU culling off for the rest of the session ({}). Nothirium carries on without it.", failure);
        nthshaders.common.StartupReport.failed("gpuCulling", failure);
        Diagnostics.write("GPU culling off: " + failure);
    }

    static void onRendererCreated(GpuTerrain terrain) {
        active = terrain;
        checksLeft = CHECKS;
    }

    static void onRendererDisposed(GpuTerrain terrain) {
        if (active == terrain) {
            active = null;
        }
    }

    /** Before a piece of GPU culling, in the validation window: drains (and counts) errors from other code. */
    /** The first uses after the renderer was created (checked with glGetError) are over. */
    static boolean validated() {
        return checksLeft <= 0;
    }

    static boolean beginCheck() {
        if (checksLeft <= 0) {
            return false;
        }
        int n = 0;
        while (GL11.glGetError() != GL11.GL_NO_ERROR && n++ < 16) {
            foreignErrors++;
        }
        return true;
    }

    static void endCheck(String where) {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            fail(where, "OpenGL error 0x" + Integer.toHexString(err));
        } else if (--checksLeft == 0) {
            Status.LOG.info("[gadolinium] GPU culling: validation finished ({} calls without an OpenGL error)", CHECKS);
            Diagnostics.write("GPU culling validated");
        }
    }

    public static int nextBfsFrame() {
        if (++bfsFrame < 0x40000000) {
            bfsFrame = 0x40000000;
        }
        return bfsFrame;
    }

    public static void onClientTick() {
        clientTicks++;
    }

    /** At the end of each frame: the F3 count and the gpuOcclusionAuto measurement. */
    public static void onFrameEnd() {
        EntityGraphCulling.endFrame();
        GpuTerrain t = active;
        if (t != null) {
            t.onFrameEnd();
        }
        if (NthConfig.gpuOcclusionAuto && NthConfig.gpuCulling && !NthConfig.gpuOcclusionCulling) {
            HizAuto.onFrameEnd();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Nothirium's traversal in "capture" mode (AbstractChunkRendererGpuMixin)

    private static GpuTerrain capturing;
    private static GpuTerrain.Slot captureSlot;
    private static GpuTerrain.View captureView;
    private static double capturePaddedFogSqr;

    static void beginCapture(GpuTerrain terrain, GpuTerrain.Slot slot, GpuTerrain.View view, double paddedFogSqr) {
        capturing = terrain;
        captureSlot = slot;
        captureView = view;
        capturePaddedFogSqr = paddedFogSqr;
    }

    static void endCapture() {
        capturing = null;
        captureSlot = null;
        captureView = null;
    }

    /** The traversal is running for the candidate list (the hooks in Nothirium's setup change behaviour). */
    public static boolean capturing() {
        return capturing != null;
    }

    /** addToRenderLists: the traversal visited the section. */
    public static void visited(AbstractRenderChunk chunk) {
        capturing.addCandidate(captureSlot, chunk);
    }

    /**
     * compileAsync during the traversal: builds now if it is in the frustum; otherwise, later when it enters. With "now", the
     * section gets the build-in-progress mark before the call (see GpuSection.nthgpu$compiling).
     */
    public static boolean compileNow(AbstractRenderChunk chunk) {
        if (!chunk.isDirty()) {
            return false;
        }
        if (captureView.contains(chunk)) {
            ((GpuSection) chunk).nthgpu$setCompiling(true);
            return true;
        }
        capturing.defer(chunk);
        return false;
    }

    /** isFogCulled during the traversal: with slack, so the list serves while the camera moves inside the section. */
    public static double paddedFogSqr() {
        return capturePaddedFogSqr;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Section events (AbstractRenderChunkGpuMixin)

    // Events arrive on the render thread (Nothirium applies builds in taskDispatcher.update). One from another thread (a mod
    // marking blocks for redraw off it) goes to a queue, drained on the next setup.
    private static final ConcurrentLinkedQueue<AbstractRenderChunk> offThreadDirty = new ConcurrentLinkedQueue<>();
    private static volatile boolean offThreadRebuild;

    private static boolean onRenderThread() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.isCallingFromMinecraftThread();
    }

    /** Start of setup: what arrived from other threads. */
    static void drainOffThread(GpuTerrain terrain) {
        AbstractRenderChunk c;
        while ((c = offThreadDirty.poll()) != null) {
            terrain.onMarkDirty(c);
        }
        if (offThreadRebuild) {
            offThreadRebuild = false;
            terrain.requestRebuild(GpuTerrain.REQUEST_OFF_THREAD);
        }
    }

    public static void onPartChanged(AbstractRenderChunk chunk, ChunkRenderPass pass, IVBOPart part) {
        GpuTerrain t = active;
        if (t == null) {
            return;
        }
        if (onRenderThread()) {
            t.onPartChanged(chunk, part);
        } else {
            // The position in the buffer may be stale: the whole list is rebuilt.
            offThreadRebuild = true;
        }
    }

    public static void onVisibilityChanged(AbstractRenderChunk chunk, VisibilitySet previous, VisibilitySet next) {
        GpuTerrain t = active;
        if (t == null || next == null) {
            return;
        }
        for (Direction d : Direction.ALL) {
            int before = previous != null ? previous.allVisibleFrom(d) : 0;
            if ((next.allVisibleFrom(d) & ~before) != 0) {
                if (onRenderThread()) {
                    t.requestRebuild(GpuTerrain.REQUEST_VISIBILITY);
                } else {
                    offThreadRebuild = true;
                }
                return;
            }
        }
    }

    public static void onVisibilityOpened(AbstractRenderChunk chunk) {
        GpuTerrain t = active;
        if (t == null) {
            return;
        }
        if (onRenderThread()) {
            t.requestRebuild(GpuTerrain.REQUEST_VISIBILITY);
        } else {
            offThreadRebuild = true;
        }
    }

    public static void onMarkDirty(AbstractRenderChunk chunk) {
        GpuTerrain t = active;
        if (t == null) {
            return;
        }
        if (onRenderThread()) {
            t.onMarkDirty(chunk);
        } else {
            offThreadDirty.add(chunk);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reflection: private fields of RenderLib's Frustum and Nothirium's private resortTransparency

    private static MethodHandle frustumPlanes;
    private static MethodHandle frustumX;
    private static MethodHandle frustumY;
    private static MethodHandle frustumZ;
    private static MethodHandle resortTransparency;
    private static MethodHandle naughthiriumLoad;
    private static boolean reflectionTried;
    private static String reflectionProblem;

    static synchronized boolean reflection() {
        if (reflectionTried) {
            return reflectionProblem == null;
        }
        reflectionTried = true;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            frustumPlanes = getter(lookup, Frustum.class, "planes");
            frustumX = getter(lookup, Frustum.class, "cameraX");
            frustumY = getter(lookup, Frustum.class, "cameraY");
            frustumZ = getter(lookup, Frustum.class, "cameraZ");
            Class<?> renderer = Class.forName("meldexun.nothirium.renderer.chunk.AbstractChunkRenderer");
            Method resort = renderer.getDeclaredMethod("resortTransparency", double.class, double.class, double.class, Frustum.class);
            resort.setAccessible(true);
            resortTransparency = lookup.unreflect(resort)
                    .asType(MethodType.methodType(void.class, Object.class, double.class, double.class, double.class, Frustum.class));
        } catch (Throwable t) {
            reflectionProblem = t.toString();
            return false;
        }
        try {
            Class<?> holder = Class.forName("zone.rong.naughthirium.compat.optifine.AnimatedSpritesHolder");
            naughthiriumLoad = MethodHandles.publicLookup().findVirtual(holder, "naughthirium$optifine$load", MethodType.methodType(void.class))
                    .asType(MethodType.methodType(void.class, Object.class));
        } catch (Throwable t) {
            // Without Naughthirium: nothing to mark.
            naughthiriumLoad = null;
        }
        return true;
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return lookup.unreflectGetter(f).asType(MethodType.methodType(f.getType().isPrimitive() ? f.getType() : Object.class, Object.class));
    }

    static Vec4[] planes(Frustum frustum) throws Throwable {
        return (Vec4[]) (Object) frustumPlanes.invokeExact((Object) frustum);
    }

    static double frustumX(Frustum frustum) throws Throwable {
        return (double) frustumX.invokeExact((Object) frustum);
    }

    static double frustumY(Frustum frustum) throws Throwable {
        return (double) frustumY.invokeExact((Object) frustum);
    }

    static double frustumZ(Frustum frustum) throws Throwable {
        return (double) frustumZ.invokeExact((Object) frustum);
    }

    static void resortTransparency(Object renderer, double x, double y, double z, Frustum frustum) throws Throwable {
        resortTransparency.invokeExact(renderer, x, y, z, frustum);
    }

    /** Naughthirium: marks the section's animated textures (as its hook in addToRenderLists does). */
    static void naughthiriumLoad(Object chunk) throws Throwable {
        naughthiriumLoad.invokeExact(chunk);
    }

    static boolean hasNaughthirium() {
        return naughthiriumLoad != null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Diagnostics and F3

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", "gpuCulling=" + NthConfig.gpuCulling + ", gpuShadowLightCulling=" + NthConfig.gpuShadowLightCulling
                + ", gpuOcclusionCulling=" + NthConfig.gpuOcclusionCulling + ", gpuOcclusionAuto=" + NthConfig.gpuOcclusionAuto + ", gpuFaceCulling=" + NthConfig.gpuFaceCulling
                + ", gpuFaceCullingShadow=" + NthConfig.gpuFaceCullingShadow);
        m.put("injections", GpuInjections.allOk() ? "ok" : "missing: " + GpuInjections.problem());
        m.put("injectionsCounted", GpuInjections.results());
        m.put("gpu", capability);
        m.put("computeLimits", limits);
        m.put("reflection", reflectionTried ? (reflectionProblem == null ? "ok" : reflectionProblem) : "not yet");
        m.put("naughthirium", naughthiriumLoad != null);
        m.put("failure", failure);
        m.put("errorsFromOtherCode", foreignErrors);
        GpuTerrain t = active;
        if (t != null) {
            m.put("renderer", t.describe());
        }
        m.put("entitiesByGraph", EntityGraphCulling.describe());
        m.put("gpuTime", GpuTime.describe());
        return m;
    }

    /** The renderer in use (gpuCullingEntities), or null. */
    static GpuTerrain activeTerrain() {
        return active;
    }

    /** F3 lines (left side), only with GPU culling in use. */
    public static List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        GpuTerrain t = active;
        if (!NthConfig.gpuCulling) {
            lines.add("[gadolinium] GPU culling: disabled in the config");
            return lines;
        }
        if (failure != null) {
            lines.add("[gadolinium] GPU culling off: " + failure);
        } else if (t != null) {
            t.debugLines(lines);
        } else if (capable != null && !capable) {
            lines.add("[gadolinium] GPU culling off: " + capability);
        } else if (!GpuInjections.allOk()) {
            lines.add("[gadolinium] GPU culling off: incomplete injections (" + GpuInjections.problem() + ")");
        }
        return lines;
    }

}
