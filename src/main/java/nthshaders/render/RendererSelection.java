// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import meldexun.nothirium.mc.config.NothiriumConfig;
import meldexun.nothirium.mc.config.NothiriumConfig.RenderEngine;
import meldexun.nothirium.mc.integration.ChunkRendererGL43Optifine;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.optifine.shaders.Shaders;
import nthshaders.core.Diagnostics;
import nthshaders.core.Status;

/**
 * Chooses Nothirium's chunk renderer (Optifine#createChunkRenderer).
 *
 * Without the addon, with shaders Nothirium falls back to GL15. Here, with the addon active and GL43 available, the renderer stays
 * GL43 and enters "shader mode": drawing uses the shader pack's program (ChunkRendererGL43Mixin).
 *
 * DynamicVBO's vertex size is fixed when the renderer is created (28 bytes without shaders, 56 with). Nothirium only recreated the
 * renderer when the engine changed (GL43 <-> GL15); now the engine no longer changes, so the renderer is discarded and recreated
 * whenever the format changes.
 */
public final class RendererSelection {

    private static final String GL43_CLASS = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL43";

    /** Vertex size each live renderer was created with. */
    private static final Map<Object, Integer> vertexSizes = Collections.synchronizedMap(new WeakHashMap<>());
    private static String lastRefusal;
    /** Reason for the veto on GL43 with shaders (addon OpenGL error, GlErrorGuard); lasts for the rest of the session. */
    private static volatile String veto;

    private RendererSelection() {
    }

    /** Forbids GL43 with shaders from here on: the next renderer created takes the original path (GL15). */
    public static void veto(String reason) {
        veto = reason;
    }

    public static boolean isShaderMode(Object renderer) {
        return renderer instanceof ShaderModeRenderer && ((ShaderModeRenderer) renderer).nthshaders$isShaderMode();
    }

    public static IChunkRenderer<?> create(IChunkRenderer<?> old, UnaryOperator<IChunkRenderer<?>> original) {
        int size = DefaultVertexFormats.BLOCK.getSize();
        if (old != null) {
            Integer oldSize = vertexSizes.get(old);
            if (oldSize == null || oldSize != size) {
                Status.LOG.info("[gadolinium] Vertex format {} -> {} bytes: discarding renderer {} to recreate it",
                        oldSize, size, old.name());
                old.dispose();
                old = null;
            }
        }

        IChunkRenderer<?> renderer;
        boolean reused;
        if (Shaders.shaderPackLoaded && canKeepGL43()) {
            if (old != null && isShaderMode(old)) {
                renderer = old;
                reused = true;
            } else {
                if (old != null) {
                    old.dispose();
                }
                renderer = new ChunkRendererGL43Optifine();
                // canKeepGL43 guaranteed the audited GL43 mixin: the cast is safe.
                ((ShaderModeRenderer) renderer).nthshaders$setShaderMode(true);
                reused = false;
            }
        } else {
            renderer = original.apply(old);
            reused = renderer == old;
        }
        vertexSizes.put(renderer, size);
        // Diagnostics only (log): the terrain black or fullbright after turning shaders off.
        ShaderOffProbe.onRendererCreated(renderer, isShaderMode(renderer));
        String description = renderer.name() + ", vertex " + size + " bytes"
                + (isShaderMode(renderer) ? ", shader mode (Gadolinium), " + ((ShaderModeRenderer) renderer).nthshaders$bufferCopies()
                        + " buffer copies" : "")
                + (reused ? ", reused" : ", new");
        Status.onChunkRendererCreated(description);
        if (isShaderMode(renderer)) {
            // Pack programs (re)compiled or a new renderer: validates the addon's path again.
            GlErrorGuard.onShaderRendererCreated();
        }
        Diagnostics.captureGpu();
        Diagnostics.write("chunk renderer: " + description);
        return renderer;
    }

    /**
     * GL43 chosen by Nothirium's config (and supported) and the addon active with ALL mixins verified. The renderer's target class is
     * loaded before asking, so its audit has already run: never a shader mode renderer with some injections missing.
     */
    private static boolean canKeepGL43() {
        if (veto != null) {
            refuse("vetoed after " + veto);
            return false;
        }
        if (NothiriumConfig.getRenderEngine() != RenderEngine.GL43) {
            refuse("Nothirium did not choose GL43 (config or hardware)");
            return false;
        }
        if (!loadTargetClasses()) {
            refuse("Nothirium target classes missing");
            return false;
        }
        if (!Status.isActive()) {
            refuse("addon inactive (" + Status.unverifiedMixins() + " not verified)");
            return false;
        }
        return true;
    }

    /**
     * Loads (without initializing) the target classes of the Nothirium mixins, so their audit has already run when
     * Status.isActive() is asked.
     */
    public static boolean loadTargetClasses() {
        try {
            for (String c : TARGET_CLASSES) {
                Class.forName(c, false, RendererSelection.class.getClassLoader());
            }
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * The targets of every mixin in Injections.EXPECTED. AbstractRenderChunk (RenderChunkShadowAreaMixin) only loads when Nothirium builds
     * its first sections, after the first renderer is chosen: without it here, that renderer took GL15 for a moment (rc3).
     */
    private static final String[] TARGET_CLASSES = { "net.optifine.shaders.Shaders", "meldexun.nothirium.mc.integration.Optifine",
            GL43_CLASS, "meldexun.nothirium.renderer.chunk.AbstractRenderChunk" };

    private static void refuse(String why) {
        if (!why.equals(lastRefusal)) {
            lastRefusal = why;
            Status.LOG.warn("[gadolinium] With shaders, the terrain goes through Nothirium's original path (GL15, chunk by chunk, slower): {}", why);
            if (!why.startsWith("addon inactive") && !why.startsWith("vetoed")) {
                nthshaders.common.StartupReport.failed("shader mode", why);
            }
        }
    }

}
