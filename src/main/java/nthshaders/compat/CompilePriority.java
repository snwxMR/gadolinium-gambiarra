// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import nthshaders.common.JarUtil;
import nthshaders.core.NthConfig;
import nthshaders.compat.ShadersCompat;

/**
 * chunkCompileOrder: the order of Nothirium's section builds. Nothirium sends each build to the thread pool in the order it
 * asks (the ForkJoinPool queue), and a build requested with the section on screen stays in front after the camera turns.
 * Here each RenderChunkDispatcher gets its own queue (CompileQueue) in front of the pool, with at most "threads" builds in
 * progress, and the next one out is:
 *  - "inViewFirst" (default): the oldest among those in the camera cone; with none, the oldest;
 *  - "inViewOnly": only those in the cone; the others wait for the section to come back on screen (or to be cancelled);
 *  - "arrivalOrder": as in Nothirium (the queue is not even created).
 * The cone is wide on purpose (the screen diagonal with slack, and everything within 40 blocks), so what appears is never
 * left out; in third person, everything counts as "on screen". A cancelled build (the section was marked again or left the
 * render distance) leaves right away: it only returns "cancelled". Only the order changes; same build, same result.
 *
 * Lock: Nothirium 0.4.9's RenderChunkDispatcher and AbstractRenderChunkTask (SHA-256), and the 3 injections counted.
 */
public final class CompilePriority {

    public static final String MIXIN = "NothiriumCompilePriorityMixin";
    public static final int INJECTIONS = 3;

    private static final String[][] CLASSES = {
            { "meldexun/nothirium/mc/renderer/chunk/RenderChunkDispatcher.class",
                    "75eb8a8ae8005dfb489d482c310f3d002054d3e204fa533991c5e423616eb271" },
            { "meldexun/nothirium/renderer/chunk/AbstractRenderChunkTask.class",
                    "4fa33d0c5232d8b012e6f2ddfeae84e94a0f3637cf158062141ab09a5bcf4ecf" },
    };

    static final String FIRST = "inViewFirst";
    static final String ONLY = "inViewOnly";
    static final String ARRIVAL = "arrivalOrder";

    /** Everything closer than this (blocks, squared) counts as on screen. */
    private static final double NEAR_SQ = 40.0 * 40.0;
    /** Radius of the sphere containing a section (8 x square root of 3). */
    private static final double SECTION_RADIUS = 13.86;

    private static boolean allowed;
    private static boolean onlyInView;
    private static String problem;
    static volatile boolean ok;
    private static String audit;
    private static MethodHandle taskChunk;

    /** The camera cone of the last frame (the render thread writes, the build threads read); null = everything on screen. */
    private static volatile Cone cone;
    private static volatile CompileQueue current;
    /** With inViewOnly: a shader pack active in the last frame (the mode is suspended). Starts as "yes" until the first frame. */
    private static volatile boolean shadersActive = true;
    private static MethodHandle isShaders;
    private static boolean shadersTried;
    private static boolean stateLogged;

    private static final class Cone {
        final double x, y, z, lx, ly, lz, cosHalf, sinHalf;

        Cone(double x, double y, double z, double lx, double ly, double lz, double half) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.cosHalf = Math.cos(half);
            this.sinHalf = Math.sin(half);
        }
    }

    private CompilePriority() {
    }

    /** ShadersCompat.evaluate: Nothirium present, the option not "arrivalOrder" and the classes checked. */
    public static void evaluate(File nothirium) {
        String order = NthConfig.chunkCompileOrder;
        if (nothirium == null || ARRIVAL.equalsIgnoreCase(order)) {
            return;
        }
        try {
            for (String[] c : CLASSES) {
                byte[] bytes = JarUtil.readEntry(nothirium, c[0]);
                if (bytes == null || !c[1].equals(JarUtil.sha256Hex(bytes))) {
                    problem = "class differs from the checked one: " + c[0];
                    break;
                }
            }
        } catch (Throwable t) {
            problem = "failed checking the classes: " + t;
        }
        allowed = problem == null;
        onlyInView = ONLY.equalsIgnoreCase(order);
        ShadersCompat.LOG.info("[gadolinium] Nothirium build order: {}", allowed ? order : "as in Nothirium (" + problem + ")");
    }

    public static boolean allowed() {
        return allowed;
    }

    /** HandlerCallAudit. */
    public static synchronized void record(String target, String mixin, int found, int expected) {
        audit = found + "/" + expected + " in " + target;
        ok = allowed && found == expected && resolveTaskChunk();
        if (found != expected) {
            ShadersCompat.LOG.warn("[gadolinium] Build order: {} has {} of {} injections in {}; staying as in Nothirium", mixin, found,
                    expected, target);
            nthshaders.common.StartupReport.failed("chunkCompileOrder", mixin + " has " + found + " of " + expected + " injections");
        }
    }

    private static boolean resolveTaskChunk() {
        try {
            Field f = Class.forName("meldexun.nothirium.renderer.chunk.AbstractRenderChunkTask", false,
                    CompilePriority.class.getClassLoader()).getDeclaredField("renderChunk");
            f.setAccessible(true);
            taskChunk = MethodHandles.lookup().unreflectGetter(f);
            return true;
        } catch (Throwable t) {
            problem = "renderChunk field of AbstractRenderChunkTask: " + t;
            ShadersCompat.LOG.warn("[gadolinium] Build order: {}; staying as in Nothirium", problem);
            nthshaders.common.StartupReport.lock("chunkCompileOrder", problem);
            return false;
        }
    }

    /** The section of a Nothirium build, or null (another task). */
    static AbstractRenderChunk chunkOf(Supplier<?> supplier) {
        if (taskChunk == null || !(supplier instanceof meldexun.nothirium.renderer.chunk.AbstractRenderChunkTask)) {
            return null;
        }
        try {
            return (AbstractRenderChunk) taskChunk.invoke(supplier);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether "inViewOnly" applies right now: not with a shader pack active. The shadow pass needs off-screen sections (what
     * the light sees), and holding their builds would leave the shadow without them; while a shader pack is active the order
     * is the default one (what is on screen goes first, but nothing is held).
     */
    static boolean onlyInView() {
        return onlyInView && !shadersActive;
    }

    /** OptiFine's Config.isShaders() (by reflection; nthcompat does not depend on OptiFine). Without it: as if there were a shader. */
    private static boolean shadersNow() {
        if (!shadersTried) {
            shadersTried = true;
            for (String name : new String[] { "Config", "net.optifine.Config" }) {
                try {
                    isShaders = MethodHandles.publicLookup().findStatic(Class.forName(name), "isShaders",
                            java.lang.invoke.MethodType.methodType(boolean.class));
                    break;
                } catch (Throwable ignored) {
                    // try the other name
                }
            }
            if (isShaders == null) {
                ShadersCompat.LOG.warn("[gadolinium] Build order: OptiFine's Config.isShaders() not found; inViewOnly behaves as inViewFirst");
            }
        }
        try {
            return isShaders == null || (boolean) isShaders.invokeExact();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The supplyAsync of Nothirium's runAsync(Supplier): the build goes to the dispatcher's queue. queue is created by the
     * mixin (one per dispatcher). Another task, or off: Nothirium's executor, as before.
     */
    public static Executor executorFor(CompileQueue queue, Supplier<?> supplier, Executor executor) {
        if (!ok || queue == null) {
            return executor;
        }
        AbstractRenderChunk chunk = chunkOf(supplier);
        if (chunk == null) {
            return executor;
        }
        return command -> queue.submit(command, (meldexun.nothirium.api.renderer.chunk.IRenderChunkTask) supplier, chunk);
    }

    /** Render thread, on each Nothirium setup (RenderChunkDispatcher.update): the new cone and what can leave the queue. */
    public static void frame(CompileQueue queue) {
        if (!ok || queue == null) {
            return;
        }
        cone = coneNow();
        if (onlyInView) {
            boolean shaders = shadersNow();
            // The first frame always goes to the log (the state starts "suspended", for safety, and without this the notice with a
            // shader pack already on from the start never appeared).
            if (!stateLogged || shaders != shadersActive) {
                stateLogged = true;
                shadersActive = shaders;
                ShadersCompat.LOG.warn("[gadolinium] Build order: {}", shaders
                        ? "shader pack active, inViewOnly behaves as inViewFirst (the shadow pass needs the off-screen sections)"
                        : "no shader pack, inViewOnly in effect");
            }
        }
        queue.pump();
    }

    private static Cone coneNow() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.getRenderViewEntity();
        if (e == null || mc.gameSettings.thirdPersonView != 0 || mc.displayHeight <= 0) {
            return null;
        }
        float pt = mc.getRenderPartialTicks();
        double x = e.prevPosX + (e.posX - e.prevPosX) * pt;
        double y = e.prevPosY + (e.posY - e.prevPosY) * pt + e.getEyeHeight();
        double z = e.prevPosZ + (e.posZ - e.prevPosZ) * pt;
        Vec3d look = e.getLook(pt);
        // Half the screen diagonal, with the config FOV x 1.4 (sprint, potions) and 10 degrees of slack.
        double vfov = Math.toRadians(Math.min(170.0, mc.gameSettings.fovSetting * 1.4));
        double aspect = (double) mc.displayWidth / mc.displayHeight;
        double half = Math.atan(Math.tan(vfov / 2.0) * Math.sqrt(1.0 + aspect * aspect)) + Math.toRadians(10.0);
        return new Cone(x, y, z, look.x, look.y, look.z, Math.min(half, Math.toRadians(89.9)));
    }

    /** Is the section in the last frame's cone (or near the camera, or no cone)? */
    static boolean inView(AbstractRenderChunk chunk) {
        Cone c = cone;
        if (c == null) {
            return true;
        }
        double dx = chunk.getX() + 8 - c.x;
        double dy = chunk.getY() + 8 - c.y;
        double dz = chunk.getZ() + 8 - c.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < NEAR_SQ) {
            return true;
        }
        double d = Math.sqrt(d2);
        double cosAngle = (dx * c.lx + dy * c.ly + dz * c.lz) / d;
        // In the cone if the angle to the centre <= half + the section's angular radius: cos(angle) >= cos(half + radius).
        double sa = SECTION_RADIUS / d;
        double ca = Math.sqrt(1.0 - sa * sa);
        return cosAngle >= c.cosHalf * ca - c.sinHalf * sa;
    }

    /** The current dispatcher's queue (the diagnostics show this one). */
    public static void attach(CompileQueue queue) {
        current = queue;
    }

    public static Map<String, Object> describe() {
        CompileQueue queue = current;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", !allowed ? ARRIVAL + (problem != null ? " (" + problem + ")" : "") : NthConfig.chunkCompileOrder
                + (onlyInView && shadersActive ? " (suspended: shader pack active, inViewFirst applies)" : ""));
        m.put("injections", audit);
        m.put("active", ok);
        if (queue != null) {
            m.putAll(queue.describe());
        }
        return m;
    }

}
