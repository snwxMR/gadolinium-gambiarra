// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import nthshaders.common.DevHooks;
import nthshaders.core.Diagnostics;
import nthshaders.core.Status;

/**
 * Automatic fallback to GL15 if the addon's path produces an OpenGL error.
 *
 * glGetError is a synchronization point with the driver thread (expensive), so it is only queried in a VALIDATION WINDOW after
 * each shader mode renderer is created (the first VALIDATION_DRAWS layer draws) and when VAOs are created (rare). Before each
 * piece of addon code, whatever was already pending is read and logged as an error from other code (it does not count); what
 * appears after the piece is the addon's.
 *
 * On an addon error: logs it, vetoes GL43 with shaders for the rest of the session (RendererSelection) and schedules a reload of
 * the renderers for the next client tick (outside drawing). Nothirium recreates the renderer, which comes out as GL15, as
 * without the addon.
 */
public final class GlErrorGuard {

    /** Layer draws checked after each shader mode renderer (~2 s at 60 FPS, 8 layers per frame). */
    static final int VALIDATION_DRAWS = 1000;
    private static final int MAX_ERRORS_PER_READ = 16;

    /**
     * Development build only: -Dnthshaders.testGlError=draw|vao causes a GL error (glEnable with an invalid enum) inside the addon
     * piece, in the validation window.
     */
    private static final String TEST_ERROR;

    static {
        String test = "";
        if (DevHooks.ON) {
            test = System.getProperty("nthshaders.testGlError", "");
        }
        TEST_ERROR = test;
    }

    private static int drawsChecked;
    private static boolean validating;
    private static volatile boolean reloadScheduled;
    private static int foreignErrors;
    private static String failure;

    private GlErrorGuard() {
    }

    /** A shader mode renderer was just created: opens the validation window. */
    public static void onShaderRendererCreated() {
        drawsChecked = 0;
        validating = true;
    }

    /** Before a piece of addon code: drains (and logs) errors from other code. Returns whether to check afterwards. */
    public static boolean before(String where) {
        if (!validating && !"vao".equals(where)) {
            return false;
        }
        int err;
        int n = 0;
        while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR && n++ < MAX_ERRORS_PER_READ) {
            if (foreignErrors++ < 5) {
                Status.LOG.warn("[gadolinium] OpenGL error 0x{} already pending before '{}' (from other code, not the addon)",
                        Integer.toHexString(err), where);
            }
        }
        return true;
    }

    /** After a piece of addon code: any new error is the addon's. */
    public static void after(String where) {
        if (DevHooks.ON) {
            if (TEST_ERROR.equals(where) && failure == null) {
                Status.LOG.warn("[gadolinium] [TEST] Causing an OpenGL error in '{}' (-Dnthshaders.testGlError)", where);
                GL11.glEnable(0x7FFF);
            }
        }
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            StringBuilder codes = new StringBuilder("0x").append(Integer.toHexString(err));
            int more;
            int n = 0;
            while ((more = GL11.glGetError()) != GL11.GL_NO_ERROR && n++ < MAX_ERRORS_PER_READ) {
                codes.append(", 0x").append(Integer.toHexString(more));
            }
            fail(where, codes.toString());
        }
        if (validating && !"vao".equals(where) && ++drawsChecked >= VALIDATION_DRAWS) {
            validating = false;
            Status.LOG.info("[gadolinium] Shader mode renderer validation finished: {} draws without an addon OpenGL error",
                    drawsChecked);
        }
    }

    private static void fail(String where, String codes) {
        if (failure != null) {
            return;
        }
        failure = "OpenGL error " + codes + " in '" + where + "'";
        validating = false;
        Status.LOG.error("[gadolinium] {} on the addon's path: falling back to GL15 with shaders for the rest of the session.", failure);
        nthshaders.common.StartupReport.failed("shader mode", failure);
        RendererSelection.veto(failure);
        reloadScheduled = true;
        Diagnostics.write("fallback to GL15: " + failure);
    }

    /** Client tick (outside drawing): reloads the renderers if a fallback was requested. */
    public static void onClientTick() {
        if (!reloadScheduled) {
            return;
        }
        reloadScheduled = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal != null && mc.world != null) {
            Status.LOG.info("[gadolinium] Reloading the chunk renderers (fallback to GL15)");
            mc.renderGlobal.loadRenderers();
        }
    }

    /** null = no addon error. */
    public static String failure() {
        return failure;
    }

    public static String describe() {
        return failure != null ? failure
                : (validating ? "validating (" + drawsChecked + "/" + VALIDATION_DRAWS + " draws)" : "no errors (" + drawsChecked + " draws checked)");
    }

}
