// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import nthshaders.common.ValidationLog;
import nthshaders.core.Status;

/**
 * gpuOcclusionAuto: Hi-Z turned on by itself when the GPU is the bottleneck. Hi-Z saves GPU time but costs CPU time; with the
 * CPU as the bottleneck it only gets in the way.
 *
 * The measurement is the GPU queue delay. At the end of the frame, glGetInteger64(GL_TIMESTAMP) gives the GPU time when the
 * commands reached it, and glQueryCounter, read later, says when it actually finished them. The difference is how far the GPU
 * lags behind the CPU: near zero when the CPU is the bottleneck (the GPU waits for commands), a good part of the frame when
 * the GPU is. The time between two GPU marks is not used: it also counts the time the GPU sat idle waiting for the CPU to send
 * the rest of the frame.
 *
 * One sample every ~100 ms and a decision every ~2 s. After turning on, it stays on for at least 60 s, and that time doubles
 * each time it turns off (up to 10 min): with Hi-Z the GPU works less, and without this the decision would keep going back and
 * forth. The switch does not show in the image: Hi-Z only skips drawing what is hidden.
 *
 * The GPU clock and the query's must be the same. On a driver where they are not, samples come out negative or absurd: after
 * 20 like that, with no good one, the measurement turns itself off and Hi-Z stays as in the config.
 */
final class HizAuto {

    private static final long SAMPLE_NANOS = 100_000_000L;
    private static final long WINDOW_NANOS = 2_000_000_000L;
    private static final long MIN_HOLD_NANOS = 60_000_000_000L;
    private static final long MAX_HOLD_NANOS = 600_000_000_000L;
    private static final double ON_AT = 0.6;
    private static final double OFF_AT = 0.25;
    private static final int WINDOWS_TO_TURN_ON = 2;
    /** A sample that makes no sense: more than 2 ms before the time the commands arrived, or more than 1 s after. */
    private static final long MIN_LAG_NANOS = -2_000_000L;
    private static final long MAX_LAG_NANOS = 1_000_000_000L;
    private static final int MAX_BAD_SAMPLES = 20;

    private static boolean tried;
    private static boolean available;
    private static String unavailable;
    private static int query;
    private static boolean pending;
    private static long submitted;

    private static long lastFrame, lastSample, windowStart, lastSwitch;
    private static long lagSum, frameSum;
    private static int lagCount, frameCount, highWindows;
    private static long goodSamples, badSamples;

    private static boolean on;
    private static double lastRatio = -1;
    private static long turnedOn, turnedOff;
    private static long hold = MIN_HOLD_NANOS;

    private HizAuto() {
    }

    static boolean on() {
        return on;
    }

    private static void giveUp(String why) {
        available = false;
        unavailable = why;
        on = false;
        Status.LOG.warn("[gadolinium] gpuOcclusionAuto off: {}. Hi-Z stays as in the config (gpuOcclusionCulling).", why);
        nthshaders.common.StartupReport.failed("gpuOcclusionAuto", why);
    }

    /** At the end of each frame, on the client thread (RenderTickEvent). */
    static void onFrameEnd() {
        if (!tried) {
            tried = true;
            available = true;
            if (!GL.getCapabilities().OpenGL33) {
                giveUp("no OpenGL 3.3 (time query)");
            } else if (GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) <= 0) {
                giveUp("the driver has no GPU time counter");
            } else {
                query = GL15.glGenQueries();
            }
        }
        if (!available) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0L) {
            frameSum += now - lastFrame;
            frameCount++;
        }
        lastFrame = now;
        if (pending && GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            sample(GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT) - submitted);
            pending = false;
            if (!available) {
                return;
            }
        }
        if (!pending && now - lastSample >= SAMPLE_NANOS) {
            lastSample = now;
            GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
            submitted = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
            pending = true;
        }
        if (windowStart == 0L) {
            windowStart = now;
        } else if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            decide(now);
        }
    }

    private static void sample(long lag) {
        if (lag < MIN_LAG_NANOS || lag > MAX_LAG_NANOS) {
            if (++badSamples >= MAX_BAD_SAMPLES && goodSamples == 0L) {
                giveUp("the GPU clock and the query's do not match on this driver");
            }
            return;
        }
        goodSamples++;
        lagSum += Math.max(lag, 0L);
        lagCount++;
    }

    private static void decide(long now) {
        if (lagCount == 0 || frameCount == 0) {
            return;
        }
        double ratio = (lagSum / (double) lagCount) / (frameSum / (double) frameCount);
        lagSum = 0L;
        frameSum = 0L;
        lagCount = 0;
        frameCount = 0;
        lastRatio = ratio;
        highWindows = ratio >= ON_AT ? highWindows + 1 : 0;
        ValidationLog.checked("gpu.occlusionAuto");
        if (!on && highWindows >= WINDOWS_TO_TURN_ON) {
            on = true;
            lastSwitch = now;
            turnedOn++;
            Status.LOG.info("[gadolinium] gpuOcclusionAuto: Hi-Z on (GPU delay at {}% of the frame)", Math.round(ratio * 100));
        } else if (on && ratio <= OFF_AT && now - lastSwitch >= hold) {
            on = false;
            lastSwitch = now;
            turnedOff++;
            hold = Math.min(hold * 2, MAX_HOLD_NANOS);
            Status.LOG.info("[gadolinium] gpuOcclusionAuto: Hi-Z off (GPU delay at {}% of the frame)", Math.round(ratio * 100));
        }
    }

    static String describe() {
        if (unavailable != null) {
            return "unavailable: " + unavailable;
        }
        return (on ? "on" : "off") + " now, turned on " + turnedOn + " and turned off " + turnedOff + " times"
                + (lastRatio >= 0 ? ", GPU delay " + Math.round(lastRatio * 100) + "% of the frame" : "");
    }

}
