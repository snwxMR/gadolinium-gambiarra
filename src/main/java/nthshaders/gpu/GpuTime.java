// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * Diagnostics only (measureGpuTime, 1.0.0-rc11): how much GPU time per frame GPU culling, Hi-Z and the terrain draw take, separately
 * with and without a shader pack, from GL_TIME_ELAPSED queries around each piece in GpuTerrain:
 *  - culling: the compute dispatches of the main pass (with Hi-Z, phase 1 and phase 2); shadow culling: those of the shadow pass;
 *  - Hi-Z: the two depth copies and the pyramid;
 *  - terrain: the indirect draws of the main pass (with shaders, the pack's programs on that geometry). This is what Hi-Z saves.
 *
 * With gpuOcclusionCulling on, Hi-Z alternates every 2 s (2 s on, 2 s off), so the terrain draw is measured with and without it in
 * the same scene: what Hi-Z saves is the terrain time without it minus with it, and what it costs is its own time plus the extra
 * culling of phase 2. The image does not change: Hi-Z only skips drawing what is hidden (gpuOcclusionAuto switches it the same way).
 * With gpuOcclusionAuto, nothing alternates (it would get in the way of its decision); only the frames as they come are measured.
 *
 * It never waits for the GPU: the queries of each frame are read 3 frames later, only if all of them are done (otherwise that frame
 * is dropped). It does not start while GPU culling or Hi-Z are still in their first uses checked with glGetError, nor while another
 * GL_TIME_ELAPSED query is running (someone else's: OpenGL allows only one). Any failure turns only the measurement off.
 */
final class GpuTime {

    static final int CULL = 0;
    static final int CULL_SHADOW = 1;
    static final int HIZ = 2;
    static final int DRAW = 3;
    private static final int KINDS = 4;
    private static final String[] NAMES = { "culling", "shadow culling", "Hi-Z", "terrain" };

    private static final int FRAMES = 4;
    private static final int PER_FRAME = 48;
    private static final long WINDOW_NANOS = 2_000_000_000L;
    private static final long LOG_NANOS = 30_000_000_000L;

    private static boolean tried;
    /** Not null = off for the rest of the session. */
    private static String failure;
    private static int[][] ids;
    private static int[][] kinds;
    private static final int[] used = new int[FRAMES];
    private static final int[] frameBucket = new int[FRAMES];
    private static int frame;
    private static boolean open;
    private static long busy;
    private static long full;
    private static long late;

    /** [bucket][kind] in ns, bucket = (shaders ? 2 : 0) + (Hi-Z this frame ? 1 : 0): since the start, and the last finished 2 s window. */
    private static final long[][] total = new long[4][KINDS];
    private static final long[] totalFrames = new long[4];
    private static final long[][] window = new long[4][KINDS];
    private static final long[] windowFrames = new long[4];
    private static final long[][] last = new long[4][KINDS];
    private static final long[] lastFrames = new long[4];
    private static final long[] scratch = new long[KINDS];
    private static long windowStart;
    private static long lastLog;
    private static long alternateStart;

    private GpuTime() {
    }

    private static boolean ready() {
        if (!NthConfig.measureGpuTime || failure != null || !GpuCulling.validated()) {
            return false;
        }
        if (!tried) {
            tried = true;
            try {
                ids = new int[FRAMES][PER_FRAME];
                kinds = new int[FRAMES][PER_FRAME];
                for (int f = 0; f < FRAMES; f++) {
                    GL15.glGenQueries(ids[f]);
                }
                Status.LOG.info("[gadolinium] measureGpuTime: GPU time of culling, Hi-Z and the terrain draw, in F3 and every 30 s here{}",
                        NthConfig.gpuOcclusionCulling ? " (Hi-Z alternates 2 s on, 2 s off to compare)" : "");
            } catch (Throwable t) {
                fail(t.toString());
            }
        }
        return failure == null;
    }

    private static void fail(String why) {
        failure = why;
        open = false;
        Status.LOG.warn("[gadolinium] measureGpuTime off for the rest of the session ({}). Nothing else changes.", why);
    }

    /** Before a piece; true = a query started, and end() must follow (in a finally). */
    static boolean begin(int kind) {
        if (open || !ready()) {
            return false;
        }
        try {
            int f = frame;
            if (used[f] >= PER_FRAME) {
                full++;
                return false;
            }
            if (GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) {
                busy++;
                return false;
            }
            int i = used[f]++;
            kinds[f][i] = kind;
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, ids[f][i]);
            open = true;
            return true;
        } catch (Throwable t) {
            fail(t.toString());
            return false;
        }
    }

    static void end() {
        if (!open) {
            return;
        }
        open = false;
        try {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    /**
     * Whether Hi-Z may run this frame (GpuTerrain.hizFrameFor). Only with measureGpuTime and gpuOcclusionCulling: off in every other 2 s
     * window, to compare.
     */
    static boolean hizAllowed() {
        if (!NthConfig.measureGpuTime || !NthConfig.gpuOcclusionCulling || failure != null || !tried) {
            return true;
        }
        long now = System.nanoTime();
        if (alternateStart == 0L) {
            alternateStart = now;
        }
        return ((now - alternateStart) / WINDOW_NANOS & 1L) == 0L;
    }

    /** End of frame (GpuCulling.onFrameEnd): closes this frame's queries and reads those of 3 frames ago. */
    static void onFrameEnd(boolean shaders, boolean hiz, boolean hizChecked) {
        if (!NthConfig.measureGpuTime || failure != null || !tried) {
            return;
        }
        try {
            end();
            // A Hi-Z frame while Hi-Z is still in its checked uses has no Hi-Z time: left out.
            frameBucket[frame] = hiz && !hizChecked ? -1 : (shaders ? 2 : 0) + (hiz ? 1 : 0);
            frame = (frame + 1) % FRAMES;
            collect(frame);
            long now = System.nanoTime();
            if (windowStart == 0L) {
                windowStart = now;
                lastLog = now;
            } else if (now - windowStart >= WINDOW_NANOS) {
                windowStart = now;
                for (int b = 0; b < 4; b++) {
                    if (windowFrames[b] > 0) {
                        System.arraycopy(window[b], 0, last[b], 0, KINDS);
                        lastFrames[b] = windowFrames[b];
                    }
                    java.util.Arrays.fill(window[b], 0L);
                    windowFrames[b] = 0;
                }
            }
            if (now - lastLog >= LOG_NANOS) {
                lastLog = now;
                for (int s = 0; s < 2; s++) {
                    String line = summary(s == 1, total, totalFrames);
                    if (line != null) {
                        Status.LOG.info("[gadolinium] GPU time per frame {}", line);
                    }
                }
            }
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private static void collect(int f) {
        int n = used[f];
        used[f] = 0;
        if (n == 0 || frameBucket[f] < 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            if (GL15.glGetQueryObjecti(ids[f][i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                late++;
                return;
            }
        }
        java.util.Arrays.fill(scratch, 0L);
        for (int i = 0; i < n; i++) {
            scratch[kinds[f][i]] += GL33.glGetQueryObjecti64(ids[f][i], GL15.GL_QUERY_RESULT);
        }
        int b = frameBucket[f];
        for (int k = 0; k < KINDS; k++) {
            total[b][k] += scratch[k];
            window[b][k] += scratch[k];
        }
        totalFrames[b]++;
        windowFrames[b]++;
    }

    private static double ms(long[][] sums, long[] frames, int bucket, int kind) {
        return frames[bucket] == 0 ? 0.0 : sums[bucket][kind] / 1e6 / frames[bucket];
    }

    /** "(with shaders, N frames): culling ..., Hi-Z ...; Hi-Z saves ..." or null without frames in that mode. */
    private static String summary(boolean shaders, long[][] sums, long[] frames) {
        int off = shaders ? 2 : 0;
        int on = off + 1;
        long n = frames[off] + frames[on];
        if (n == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("(").append(shaders ? "with shaders" : "without shaders").append(", ").append(n).append(" frames)");
        for (int b = off; b <= on; b++) {
            if (frames[b] == 0) {
                continue;
            }
            sb.append(b == on ? "; Hi-Z frames: " : "; frames without Hi-Z: ");
            for (int k = 0; k < KINDS; k++) {
                if (k == HIZ && b != on || k == CULL_SHADOW && !shaders) {
                    continue;
                }
                sb.append(k == 0 ? "" : ", ").append(NAMES[k]).append(' ').append(String.format("%.3f", ms(sums, frames, b, k)));
            }
            sb.append(" ms");
        }
        if (frames[off] > 0 && frames[on] > 0) {
            double saves = ms(sums, frames, off, DRAW) - ms(sums, frames, on, DRAW);
            double costs = ms(sums, frames, on, HIZ) + ms(sums, frames, on, CULL) - ms(sums, frames, off, CULL);
            sb.append(String.format("; Hi-Z saves %.3f ms of terrain and costs %.3f (net %+.3f ms: %s)", saves, costs, saves - costs,
                    saves - costs >= 0 ? "worth it" : "costs more than it saves"));
        }
        return sb.toString();
    }

    /** F3 lines, only with measureGpuTime: the last 2 s window of the current mode, and the comparison since the start. */
    static void debugLines(List<String> lines, boolean shaders) {
        if (!NthConfig.measureGpuTime) {
            return;
        }
        if (failure != null) {
            lines.add("[gadolinium] GPU time: off (" + failure + ")");
            return;
        }
        if (!tried) {
            lines.add("[gadolinium] GPU time: starts after GPU culling's first checks");
            return;
        }
        String now = summary(shaders, last, lastFrames);
        lines.add("[gadolinium] GPU time, last 2 s " + (now != null ? now : "(no frame read yet)"));
        if (NthConfig.gpuOcclusionCulling) {
            String all = summary(shaders, total, totalFrames);
            if (all != null && all.contains("Hi-Z saves")) {
                lines.add("[gadolinium] GPU time since the start " + all.substring(all.indexOf("; Hi-Z saves") + 2));
            }
        }
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!NthConfig.measureGpuTime) {
            m.put("state", "disabled in the config (measureGpuTime)");
            return m;
        }
        m.put("state", failure != null ? "off: " + failure : tried ? "measuring" : "not started yet");
        m.put("withoutShaders", summary(false, total, totalFrames));
        m.put("withShaders", summary(true, total, totalFrames));
        m.put("skipped", "another time query running " + busy + ", too many pieces in a frame " + full + ", frames read late " + late);
        return m;
    }

}
