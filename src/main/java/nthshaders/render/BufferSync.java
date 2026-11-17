// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL32;

import net.optifine.shaders.Shaders;
import nthshaders.common.DevHooks;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * Synchronization of GL43's offset and command buffers in shader mode.
 *
 * Nothirium writes those buffers from the CPU while the GPU may be reading a copy from a previous frame ("persistent" buffers:
 * mapped all the time, without the driver's automatic wait). To avoid writing to a copy in use, it alternates between copies
 * and, before writing, waits for a timestamp query placed after the last draw of that copy.
 *
 * With shaders there are two "passes" per frame (shadow and main), each with its own setup, and Nothirium only places the query
 * after the translucent layer, which the shadow pass does not always draw: without it, the shadow pass copy was rewritten with
 * no wait at all. ChunkRendererGL43Mixin places the query after each layer. (Separate copies per pass were measured and made
 * frame pacing worse with no gain: left out.)
 *
 * MEASUREMENT (diagnostics, config measureBufferSync, independent of the fix): after each draw, a fence (glFenceSync) marks the
 * copy used; before writing to it again, the fence is checked without waiting (glClientWaitSync with timeout 0). If it has not
 * signalled, the GPU was still using the copy: it counts as "busy". The expected value is zero. Costs about 3% FPS (the query
 * synchronizes with the driver thread), so it is off by default.
 */
public final class BufferSync {

    public static final int SHADOW = 0;
    public static final int MAIN = 1;
    private static final String[] PASS_NAMES = { "shadow", "main" };

    /**
     * Development build only: -Dnthshaders.testSync=off goes back to Nothirium's original synchronization (no per-layer query). In
     * the release build it is always "".
     */
    private static final String TEST_MODE;

    static {
        String mode = "";
        if (DevHooks.ON) {
            mode = System.getProperty("nthshaders.testSync", "");
        }
        TEST_MODE = mode;
    }
    private static boolean warnedTest;

    private static final long[] checks = new long[2];
    private static final long[] busy = new long[2];
    private static final long[] failed = new long[2];
    private static final long[] untracked = new long[2];
    /** Diagnostics: chunk sections uploaded to the GPU and VAOs rebuilt (the VBO grew), per pass. */
    private static final long[] uploads = new long[2];
    private static final long[] vaoRebuilds = new long[2];
    /** GPU culling: reuses of a buffer set checked and how many with the GPU still on it (checkGpuReuse). */
    private static final long[] gpuChecks = new long[2];
    private static final long[] gpuBusy = new long[2];

    private BufferSync() {
    }

    public static void noteUpload() {
        uploads[currentPass()]++;
    }

    public static void noteVaoRebuild() {
        vaoRebuilds[currentPass()]++;
    }

    public static int currentPass() {
        return Shaders.isShadowPass ? SHADOW : MAIN;
    }

    /** Time query after each layer drawn. */
    public static boolean queryPerLayer() {
        if (DevHooks.ON) {
            if (!TEST_MODE.isEmpty() && !warnedTest) {
                warnedTest = true;
                Status.LOG.warn("[gadolinium] [TEST] Buffer synchronization in mode '{}' (-Dnthshaders.testSync)", TEST_MODE);
            }
        }
        return !"off".equals(TEST_MODE);
    }

    /** Fences after the draws and check before writing (config measureBufferSync). */
    public static boolean measuring() {
        return NthConfig.measureBufferSync;
    }

    /** Replaces a copy's fence with a new one, placed now (after a draw). */
    public static long replaceFence(long old) {
        if (old != 0L) {
            GL32.glDeleteSync(old);
        }
        return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /**
     * Before writing to a copy: has the GPU finished the last draw that used it?
     * Consumes the fence (returns 0 to store in its place).
     */
    public static long checkBeforeWrite(long fence) {
        int pass = currentPass();
        checks[pass]++;
        if (fence == 0L) {
            // Copy not drawn since it was created (or since the last write).
            untracked[pass]++;
            return 0L;
        }
        int result = GL32.glClientWaitSync(fence, 0, 0L);
        if (result == GL32.GL_TIMEOUT_EXPIRED) {
            if (busy[pass]++ == 0) {
                Status.LOG.warn("[gadolinium] GL43 buffer written while the GPU was still using the copy (pass {})", PASS_NAMES[pass]);
            }
        } else if (result == GL32.GL_WAIT_FAILED) {
            failed[pass]++;
        }
        GL32.glDeleteSync(fence);
        return 0L;
    }

    /**
     * GPU culling, before the compute rewrites one of the two buffer sets (commands, offsets, counts): has the GPU finished the last
     * draw that read that set? Nothing writes from the CPU there and OpenGL keeps the order, so "busy" is not an error, unlike the
     * path above: it says the alternation between the two sets has no slack and the GPU may stall the queue.
     */
    public static long checkGpuReuse(long fence) {
        if (fence == 0L) {
            return 0L;
        }
        int pass = currentPass();
        gpuChecks[pass]++;
        if (GL32.glClientWaitSync(fence, 0, 0L) == GL32.GL_TIMEOUT_EXPIRED) {
            gpuBusy[pass]++;
        }
        GL32.glDeleteSync(fence);
        return 0L;
    }

    public static void deleteFence(long fence) {
        if (fence != 0L) {
            GL32.glDeleteSync(fence);
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", TEST_MODE.isEmpty() ? "normal" : "test:" + TEST_MODE);
        m.put("measuring", NthConfig.measureBufferSync);
        for (int pass = 0; pass < 2; pass++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("checks", checks[pass]);
            p.put("busy", busy[pass]);
            p.put("waitFailed", failed[pass]);
            p.put("noFence", untracked[pass]);
            p.put("uploads", uploads[pass]);
            p.put("vaoRebuilds", vaoRebuilds[pass]);
            p.put("GPU culling", gpuChecks[pass] + " reuses checked, " + gpuBusy[pass] + " with the GPU still on it");
            m.put(PASS_NAMES[pass], p);
        }
        return m;
    }

}
