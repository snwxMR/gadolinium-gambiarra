// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The GPU culling injections (mixins.nthgpu.json) and what the audit counted in the final class of each target. No game or
 * mod classes here: it is used by the mixin plugin (GpuMixinPlugin) while the target classes are still being transformed.
 */
public final class GpuInjections {

    /** Mixin -> injections it leaves in the final class. */
    public static final Map<String, Integer> EXPECTED;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        // @WrapMethod setup and renderChunks, @Inject TAIL init, initVAOs and dispose
        m.put("ChunkRendererGL43GpuMixin", 5);
        // in setup: @Inject HEAD addToRenderLists, @WrapOperation compileAsync, isFrustumCulled and isFogCulled
        m.put("AbstractChunkRendererGpuMixin", 4);
        // @Inject TAIL setVBOPart and markDirty, HEAD setVisibility, before compileAsync's setAllVisible
        m.put("AbstractRenderChunkGpuMixin", 4);
        // @WrapOperation List.isEmpty in render
        m.put("MinecraftChunkRendererGpuMixin", 1);
        EXPECTED = Collections.unmodifiableMap(m);
    }

    /**
     * Per-direction face culling (gpuFaceCulling), checked separately: the target class only loads on the first build, after the
     * renderer is created, and a failure here only turns per-direction culling off.
     */
    public static final Map<String, Integer> FACE_EXPECTED;

    /** Per-direction culling mixins (only applied with gpuFaceCulling on); VBOPartGpuMixin only adds a field. */
    public static final java.util.Set<String> FACE_MIXINS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        // @Inject HEAD compileSection(pack), RETURN of the finishDrawing lambda (3 exits), before runOnRenderThread,
        // @WrapOperation of buffer in the render thread lambda
        m.put("RenderChunkTaskCompileGpuMixin", 6);
        FACE_EXPECTED = Collections.unmodifiableMap(m);
        FACE_MIXINS = Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList("RenderChunkTaskCompileGpuMixin", "VBOPartGpuMixin")));
    }

    /**
     * Entity graph culling (gpuCullingEntities), checked separately: the targets are RenderLib's and a failure here only turns
     * that part off. @WrapMethod shouldRender on each.
     */
    public static final Map<String, Integer> ENTITY_EXPECTED;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("RenderLibEntityGraphMixin", 1);
        m.put("RenderLibTileEntityGraphMixin", 1);
        ENTITY_EXPECTED = Collections.unmodifiableMap(m);
    }

    private static final Map<String, Boolean> entityResults = new LinkedHashMap<>();
    private static volatile boolean entityOk;

    private static final Map<String, String> results = new LinkedHashMap<>();
    private static final Map<String, Boolean> ok = new LinkedHashMap<>();
    private static volatile boolean allOk;
    private static volatile boolean faceOk;
    private static String lastProblem;
    private static String faceProblem;

    private GpuInjections() {
    }

    /** HandlerCallAudit, at the end of each target class's transformation. */
    public static synchronized void record(String target, String mixin, int found, int wanted) {
        results.put(mixin, found + "/" + wanted + " in " + target);
        if (ENTITY_EXPECTED.containsKey(mixin)) {
            entityResults.put(mixin, found == wanted);
            boolean all = true;
            for (String m : ENTITY_EXPECTED.keySet()) {
                all &= Boolean.TRUE.equals(entityResults.get(m));
            }
            entityOk = all;
            return;
        }
        if (FACE_EXPECTED.containsKey(mixin)) {
            faceOk = found == wanted;
            faceProblem = faceOk ? null : mixin + " has " + found + " of " + wanted + " injections in " + target;
            return;
        }
        ok.put(mixin, found == wanted);
        if (found != wanted) {
            lastProblem = mixin + " has " + found + " of " + wanted + " injections in " + target;
        }
        boolean all = true;
        for (String m : EXPECTED.keySet()) {
            if (!Boolean.TRUE.equals(ok.get(m))) {
                all = false;
            }
        }
        allOk = all;
    }

    /** All mixins went in with the full count. */
    public static boolean allOk() {
        return allOk;
    }

    /** Both entity graph mixins went in with the full count (false until both classes load). */
    public static boolean entityOk() {
        return entityOk;
    }

    /** The per-direction culling build mixin went in with the full count (false until the class loads). */
    public static boolean faceOk() {
        return faceOk;
    }

    public static synchronized String faceProblem() {
        return faceOk ? null : faceProblem != null ? faceProblem : "Nothirium's build code has not loaded yet";
    }

    /** What is missing (null = nothing), for the log. */
    public static synchronized String problem() {
        if (allOk) {
            return null;
        }
        if (lastProblem != null) {
            return lastProblem;
        }
        StringBuilder sb = new StringBuilder("not applied:");
        for (String m : EXPECTED.keySet()) {
            if (!ok.containsKey(m)) {
                sb.append(' ').append(m);
            }
        }
        return sb.toString();
    }

    public static synchronized Map<String, String> results() {
        return new LinkedHashMap<>(results);
    }

}
