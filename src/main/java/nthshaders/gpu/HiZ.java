// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import nthshaders.core.Status;

/**
 * Hi-Z (gpuOcclusionCulling): a MAXIMUM depth pyramid of the screen, built in the middle of the main pass's SOLID terrain
 * draw (GpuTerrain):
 *  1. before phase 1, copies the screen depth ("before": sky, clouds, whatever was already there);
 *  2. phase 1 draws the SOLID of the sections visible in the previous frame;
 *  3. copies the depth again ("after") and builds the pyramid only from what phase 1 drew (hiz_init: where the depth did not
 *     change the value is "far", so clouds and sky never hide terrain) and the smaller levels (hiz_reduce);
 *  4. phase 2 tests the other sections against the pyramid (cull.comp, mode 2).
 *
 * Where the depth comes from: the framebuffer bound at SOLID time (Minecraft's without shaders, OptiFine's with).
 *  - Renderbuffer attachment (Minecraft's): glCopyImageSubData to textures of the same format, with no framebuffer switch.
 *  - Texture attachment (OptiFine's): "before" becomes R32F (depth_copy.comp); "after" is the texture itself.
 *
 * Anything different (default framebuffer, multisample, unsized format) only takes Hi-Z out of that frame. An OpenGL error, in
 * the first uses, turns only Hi-Z off for the rest of the session: the rest of GPU culling carries on.
 */
final class HiZ {

    /** Uses checked with glGetError (the query synchronizes with the driver: only at the start). */
    private static final int CHECKS = 600;

    private int width;
    private int height;
    /** Format of the renderbuffer attachment (0 = the depth is a texture). */
    private int renderbufferFormat;
    private int sourceRenderbuffer;
    private int sourceTexture;

    private int beforeTexture;
    private int afterTexture;
    private int pyramid;
    private int levels;
    /** Depth sampler (no mipmap: OptiFine's texture only has level 0) and the pyramid's. */
    private int depthSampler;
    private int sampler;

    private int checksLeft = CHECKS;

    /** Why it does not work this frame (null = working). */
    String problem;
    /** Off for the rest of the session. */
    private String failure;

    private final GlStateSave state = new GlStateSave();

    int levels() {
        return levels;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int pyramid() {
        return pyramid;
    }

    int sampler() {
        return sampler;
    }

    boolean failed() {
        return failure != null;
    }

    /** Its first uses checked with glGetError are over (measureGpuTime only times it after that). */
    boolean validated() {
        return checksLeft <= 0;
    }

    private void fail(String where, String what) {
        failure = where + ": " + what;
        problem = "off for the rest of the session (" + failure + ")";
        Status.LOG.warn("[gadolinium] Hi-Z off for the rest of the session ({}). The rest of GPU culling carries on.", failure);
        nthshaders.common.StartupReport.failed(nthshaders.core.NthConfig.gpuOcclusionCulling ? "gpuOcclusionCulling" : "gpuOcclusionAuto", failure);
    }

    private boolean beginCheck() {
        if (checksLeft <= 0) {
            return false;
        }
        int n = 0;
        while (GL11.glGetError() != GL11.GL_NO_ERROR && n++ < 16) {
            // Errors from other code: they do not count.
        }
        return true;
    }

    /** true = no error. */
    private boolean endCheck(String where) {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            fail(where, "OpenGL error 0x" + Integer.toHexString(err));
            return false;
        }
        checksLeft--;
        return true;
    }

    /**
     * Before phase 1: checks the bound framebuffer, (re)creates the textures if the size/format changed and keeps the depth from
     * before. false = no Hi-Z this frame (the reason goes in problem).
     */
    boolean begin(GpuPrograms programs) {
        if (failure != null) {
            return false;
        }
        boolean check = beginCheck();
        try {
            if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == 0) {
                problem = "drawing to the default framebuffer (no FBO)";
                return false;
            }
            if (!inspect()) {
                return false;
            }
            if (renderbufferFormat != 0) {
                copyDepth(beforeTexture);
            } else {
                copyToR32F(programs, sourceTexture, beforeTexture);
            }
        } catch (Throwable t) {
            fail("depth copy", t.toString());
            return false;
        }
        if (check && !endCheck("depth copy")) {
            return false;
        }
        problem = null;
        return true;
    }

    /** After phase 1: depth from after and the whole pyramid. false = failed (Hi-Z off). */
    boolean build(GpuPrograms programs) {
        boolean check = beginCheck();
        try {
            int after = sourceTexture;
            if (renderbufferFormat != 0) {
                copyDepth(afterTexture);
                after = afterTexture;
            }
            buildPyramid(programs, after);
        } catch (Throwable t) {
            fail("pyramid", t.toString());
            return false;
        }
        return !check || endCheck("pyramid");
    }

    private void buildPyramid(GpuPrograms programs, int after) {
        state.saveProgram();
        state.saveTextures();
        try {
            int w0 = Math.max(1, width / 2);
            int h0 = Math.max(1, height / 2);
            GL20.glUseProgram(programs.hizInit);
            GL20.glUniform2i(programs.hizInitSrcSize, width, height);
            state.bind(GpuPrograms.UNIT_A, beforeTexture, depthSampler);
            state.bind(GpuPrograms.UNIT_B, after, depthSampler);
            GL42.glBindImageTexture(0, pyramid, 0, false, 0, GL42.GL_WRITE_ONLY, GL30.GL_R32F);
            GL43.glDispatchCompute((w0 + 15) / 16, (h0 + 15) / 16, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            GL20.glUseProgram(programs.hizReduce);
            state.bind(GpuPrograms.UNIT_B, pyramid, sampler);
            int lw = w0;
            int lh = h0;
            for (int level = 1; level < levels; level++) {
                lw = Math.max(1, lw / 2);
                lh = Math.max(1, lh / 2);
                GL20.glUniform1i(programs.hizReduceSrcLevel, level - 1);
                GL42.glBindImageTexture(0, pyramid, level, false, 0, GL42.GL_WRITE_ONLY, GL30.GL_R32F);
                GL43.glDispatchCompute((lw + 15) / 16, (lh + 15) / 16, 1);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL42.GL_WRITE_ONLY, GL30.GL_R32F);
        } finally {
            state.restoreTextures();
            state.restoreProgram();
        }
    }

    /** Reads the depth attachment of the draw framebuffer (without switching framebuffer): type, name, format and size. */
    private boolean inspect() {
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int w;
        int h;
        int format;
        if (type == GL30.GL_RENDERBUFFER) {
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
            format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            w = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
            h = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
            int samples = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            if (samples > 0) {
                problem = "multisampled depth";
                return false;
            }
            if (!sizedDepth(format)) {
                problem = "unsized depth format (0x" + Integer.toHexString(format) + ")";
                return false;
            }
            sourceRenderbuffer = name;
            sourceTexture = 0;
        } else if (type == GL11.GL_TEXTURE) {
            int level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            if (level != 0) {
                problem = "the depth is level " + level + " of a texture";
                return false;
            }
            format = 0;
            int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GpuPrograms.UNIT_A);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            GL13.glActiveTexture(previousActive);
            sourceTexture = name;
            sourceRenderbuffer = 0;
        } else {
            problem = "framebuffer with no depth attachment (type 0x" + Integer.toHexString(type) + ")";
            return false;
        }
        if (w <= 0 || h <= 0) {
            problem = "depth without a size";
            return false;
        }
        if (w != width || h != height || format != renderbufferFormat || pyramid == 0) {
            recreate(w, h, format);
        }
        return true;
    }

    private static boolean sizedDepth(int format) {
        return format == GL14.GL_DEPTH_COMPONENT16 || format == GL14.GL_DEPTH_COMPONENT24 || format == GL14.GL_DEPTH_COMPONENT32
                || format == GL30.GL_DEPTH_COMPONENT32F || format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
    }

    private void recreate(int w, int h, int format) {
        dispose();
        width = w;
        height = h;
        renderbufferFormat = format;
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + GpuPrograms.UNIT_A);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (format != 0) {
                // Renderbuffer: two textures of the same format, for the copy.
                beforeTexture = depthTexture(w, h, format);
                afterTexture = depthTexture(w, h, format);
            } else {
                // Texture: "before" is an R32F copy; "after" is the framebuffer's texture itself.
                beforeTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, beforeTexture);
                GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32F, w, h);
                nearest();
            }
            int w0 = Math.max(1, w / 2);
            int h0 = Math.max(1, h / 2);
            levels = 1 + (int) Math.floor(Math.log(Math.max(w0, h0)) / Math.log(2));
            pyramid = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, pyramid);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_R32F, w0, h0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            GL13.glActiveTexture(previousActive);
        }
        depthSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static int depthTexture(int w, int h, int format) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, format, w, h);
        nearest();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        return t;
    }

    private static void nearest() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }

    /** The depth of the screen renderbuffer to one of our textures (same format and size). */
    private void copyDepth(int target) {
        GL43.glCopyImageSubData(sourceRenderbuffer, GL30.GL_RENDERBUFFER, 0, 0, 0, 0, target, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    private void copyToR32F(GpuPrograms programs, int source, int target) {
        state.saveProgram();
        state.saveTextures();
        try {
            GL20.glUseProgram(programs.depthCopy);
            state.bind(GpuPrograms.UNIT_B, source, depthSampler);
            GL42.glBindImageTexture(0, target, 0, false, 0, GL42.GL_WRITE_ONLY, GL30.GL_R32F);
            GL43.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL42.GL_WRITE_ONLY, GL30.GL_R32F);
        } finally {
            state.restoreTextures();
            state.restoreProgram();
        }
    }

    void dispose() {
        int[] textures = { beforeTexture, afterTexture, pyramid };
        for (int t : textures) {
            if (t != 0) {
                GL11.glDeleteTextures(t);
            }
        }
        if (depthSampler != 0) {
            GL33.glDeleteSamplers(depthSampler);
        }
        if (sampler != 0) {
            GL33.glDeleteSamplers(sampler);
        }
        beforeTexture = afterTexture = pyramid = sampler = depthSampler = 0;
        width = height = levels = 0;
    }

}
