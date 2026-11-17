// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/**
 * The GPU culling compute shaders (assets/nthshaders/gpu): the three-stage culling (cull.comp with STAGE 0, 1 and 2) and the
 * Hi-Z ones (hiz_init, hiz_reduce, depth_copy). Compiled once, on the render thread; a compile error turns GPU culling off and
 * the log carries the driver's message.
 */
final class GpuPrograms {

    /** Texture units the compute shaders use (saved and restored around each use, see GlStateSave). */
    static final int UNIT_A = 14;
    static final int UNIT_B = 15;

    final int[] cull = new int[ShaderSource.CULL_STAGES];
    int hizInit;
    int hizReduce;
    int depthCopy;
    int hizInitSrcSize;
    int hizReduceSrcLevel;

    /** null = everything compiled; otherwise, what failed (with the driver's log). */
    String problem;

    GpuPrograms() {
        try {
            String[] cullSources = ShaderSource.variants("cull.comp", read("cull.comp"));
            for (int stage = 0; stage < ShaderSource.CULL_STAGES; stage++) {
                cull[stage] = build(cullSources[stage], "cull.comp (stage " + stage + ")");
            }
            hizInit = build(ShaderSource.variants("hiz_init.comp", read("hiz_init.comp"))[0], "hiz_init.comp");
            hizReduce = build(ShaderSource.variants("hiz_reduce.comp", read("hiz_reduce.comp"))[0], "hiz_reduce.comp");
            depthCopy = build(ShaderSource.variants("depth_copy.comp", read("depth_copy.comp"))[0], "depth_copy.comp");
            int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            try {
                for (int p : cull) {
                    sampler(p, "u_HiZ", UNIT_B);
                }
                sampler(hizInit, "u_Before", UNIT_A);
                sampler(hizInit, "u_After", UNIT_B);
                sampler(hizReduce, "u_Src", UNIT_B);
                sampler(depthCopy, "u_Src", UNIT_B);
                hizInitSrcSize = GL20.glGetUniformLocation(hizInit, "u_SrcSize");
                hizReduceSrcLevel = GL20.glGetUniformLocation(hizReduce, "u_SrcLevel");
            } finally {
                GL20.glUseProgram(previous);
            }
        } catch (Throwable t) {
            if (problem == null) {
                problem = t.toString();
            }
            dispose();
        }
    }

    private static void sampler(int program, String name, int unit) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20.glUseProgram(program);
            GL20.glUniform1i(location, unit);
        }
    }

    private int build(String source, String name) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            problem = name + " did not compile: " + log.trim();
            throw new IllegalStateException(problem);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            GL20.glDeleteProgram(program);
            problem = name + " did not link: " + log.trim();
            throw new IllegalStateException(problem);
        }
        return program;
    }

    private static String read(String name) throws IOException {
        try (InputStream in = GpuPrograms.class.getResourceAsStream("/assets/nthshaders/gpu/" + name)) {
            if (in == null) {
                throw new IOException("missing resource: " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    boolean ok() {
        return problem == null;
    }

    void dispose() {
        for (int i = 0; i < cull.length; i++) {
            if (cull[i] != 0) {
                GL20.glDeleteProgram(cull[i]);
                cull[i] = 0;
            }
        }
        if (hizInit != 0) {
            GL20.glDeleteProgram(hizInit);
            hizInit = 0;
        }
        if (hizReduce != 0) {
            GL20.glDeleteProgram(hizReduce);
            hizReduce = 0;
        }
        if (depthCopy != 0) {
            GL20.glDeleteProgram(depthCopy);
            depthCopy = 0;
        }
    }

}
