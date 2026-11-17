// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * unfocusedFpsLimit: with the window out of focus, the client draws at most this many frames per second. It waits at the
 * end of the frame (RenderTickEvent), on the client thread: no mixin, and the game's FPS limit is left alone. Playing with
 * the window in focus never gets here; the integrated server has its own thread and does not change.
 *
 * The window class changes name between Cleanroom versions (emulated LWJGL 2 or SDL): by MethodHandle, on both. With
 * neither, the option does nothing and the log says so.
 */
public final class UnfocusedFps {

    private static final String[] WINDOW_CLASSES = { "org.lwjgl.opengl.Display", "com.cleanroommc.client.sdl.video.Display" };

    private static boolean looked;
    private static MethodHandle isActive;
    private static long lastEnd;

    private UnfocusedFps() {
    }

    private static boolean windowActive() throws Throwable {
        if (!looked) {
            looked = true;
            for (String name : WINDOW_CLASSES) {
                try {
                    isActive = MethodHandles.publicLookup().findStatic(Class.forName(name), "isActive", MethodType.methodType(boolean.class));
                    break;
                } catch (ReflectiveOperationException | LinkageError e) {
                    // try the other name
                }
            }
            if (isActive == null) {
                Status.LOG.warn("[gadolinium] unfocusedFpsLimit: could not find out whether the window has focus; the limit has no effect");
                nthshaders.common.StartupReport.failed("unfocusedFpsLimit", "the window class has no isActive()");
            }
        }
        return isActive == null || (boolean) isActive.invokeExact();
    }

    public static void onFrameEnd() {
        int limit = NthConfig.unfocusedFpsLimit;
        try {
            if (limit <= 0 || windowActive()) {
                lastEnd = 0L;
                return;
            }
            long now = System.nanoTime();
            long wait = lastEnd == 0L ? 0L : 1_000_000_000L / limit - (now - lastEnd);
            if (wait > 0L) {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            }
            lastEnd = System.nanoTime();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            isActive = null;
        }
    }

}
