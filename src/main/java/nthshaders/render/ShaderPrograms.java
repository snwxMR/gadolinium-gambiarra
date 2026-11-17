// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import meldexun.nothirium.mc.config.NothiriumConfig;
import meldexun.nothirium.mc.config.NothiriumConfig.RenderEngine;
import net.optifine.shaders.Program;
import nthshaders.common.DevHooks;
import nthshaders.core.Status;

/**
 * Which pack programs have their vertex shader rewritten (ShaderRewriter) and which GL programs actually ended up with the offset.
 *
 * Rewritten programs: those that can draw terrain with Nothirium (Shaders.java, G5):
 *  - terrain: the beginTerrain* calls only use gbuffers_terrain; if the pack does not have it, OptiFine copies the first that
 *    exists in the chain gbuffers_terrain -> gbuffers_textured_lit -> gbuffers_textured -> gbuffers_basic;
 *  - water: gbuffers_water, which falls back to gbuffers_terrain (same chain);
 *  - shadow: in the shadow pass useProgram always forces "shadow" (shadow_solid/shadow_cutout are never used in G5).
 *
 * Rewrite failure: the program keeps the original source and stays off the list, and GL43 draws it chunk by chunk, like GL15.
 *  - zero substitutions, or the rewritten source does not compile in a test shader: OptiFine already receives the original;
 *  - the rewritten source compiles but the program does not link (or validate): the program is deleted and built again with
 *    the original source.
 */
public final class ShaderPrograms {

    public static final List<String> REWRITTEN = Collections.unmodifiableList(Arrays.asList(
            "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic", "gbuffers_water", "shadow"));

    /** GL ids of the programs with the offset. */
    private static final Set<Integer> patchedIds = Collections.synchronizedSet(new HashSet<>());
    /** Result per program, for the log and perf.json. */
    private static final Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());

    /** Program in setupProgram and whether its source should be rewritten now. */
    private static Program current;
    private static boolean rewriteCurrent;
    private static ShaderRewriter.Result lastRewrite;

    private ShaderPrograms() {
    }

    /** Shaders.init/uninit: the old programs are deleted (and the ids may come back). */
    public static void reset() {
        patchedIds.clear();
        results.clear();
        perChunkLogged.clear();
        current = null;
        rewriteCurrent = false;
        lastRewrite = null;
    }

    public static boolean isPatched(int programId) {
        return programId != 0 && patchedIds.contains(programId);
    }

    /**
     * Only rewrites if the addon will draw with GL43 in shader mode: addon active with everything verified and GL43 chosen. Otherwise
     * the pack's source stays as it is (adding the offset would be harmless, but there is no reason to).
     */
    public static boolean shouldRewrite(Program program) {
        if (!REWRITTEN.contains(program.getName())) {
            return false;
        }
        RendererSelection.loadTargetClasses();
        if (!Status.isActive()) {
            return false;
        }
        try {
            return NothiriumConfig.getRenderEngine() == RenderEngine.GL43;
        } catch (RuntimeException e) {
            // GL capabilities not read yet: no rewrite (chunk by chunk drawing if needed).
            return false;
        }
    }

    public static void begin(Program program, boolean rewrite) {
        current = program;
        rewriteCurrent = rewrite;
        lastRewrite = null;
        lastOutcome = null;
        lastCompileLog = null;
    }

    /** End of setupProgram; the rewrite result (null = the pack does not have this vertex shader). */
    public static ShaderRewriter.Result end() {
        ShaderRewriter.Result r = lastRewrite;
        current = null;
        rewriteCurrent = false;
        lastRewrite = null;
        return r;
    }

    /** What happened to the current program's vertex shader source. */
    public enum Outcome {
        /** The rewritten source was handed to OptiFine. */
        REWRITTEN,
        /** Nothing to replace: OptiFine received the original source. */
        ZERO_SUBSTITUTIONS,
        /** The rewritten source did not compile in the test: OptiFine received the original. */
        COMPILE_FAILED
    }

    private static Outcome lastOutcome;
    private static String lastCompileLog;

    public static Outcome lastOutcome() {
        return lastOutcome;
    }

    public static String lastCompileLog() {
        return lastCompileLog;
    }

    /**
     * glShaderSourceARB of the current program's vertex shader. The rewritten source only goes to OptiFine if it has a substitution and
     * compiles in an addon test shader; otherwise the original goes. That way a bad rewrite never shows up as an OptiFine error (log,
     * chat, GL error).
     */
    public static CharSequence vertexSource(CharSequence source) {
        if (!rewriteCurrent || current == null) {
            return source;
        }
        ShaderRewriter.Result r = ShaderRewriter.rewrite(source.toString());
        if (DevHooks.ON) {
            // Development build only: -Dnthshaders.testRewriteFail=<program>:compile|zero forces this program's rewrite to fail, to exercise
            // the fallback.
            String test = System.getProperty("nthshaders.testRewriteFail", "");
            if (test.equals(current.getName() + ":compile")) {
                Status.LOG.warn("[gadolinium] [TEST] Rewritten source of {} with a deliberate compile error", current.getName());
                r = new ShaderRewriter.Result(r.source + "\n#error nthshaders: deliberate error (test)\n", r.vertexReplacements,
                        r.ftransformReplacements, r.declaration);
            } else if (test.equals(current.getName() + ":zero")) {
                Status.LOG.warn("[gadolinium] [TEST] Rewrite of {} treated as having no substitutions", current.getName());
                r = new ShaderRewriter.Result(r.source, 0, 0, r.declaration);
            }
            dump(current.getName(), r.source);
        }
        lastRewrite = r;
        if (r.substitutions() == 0) {
            lastOutcome = Outcome.ZERO_SUBSTITUTIONS;
            return source;
        }
        lastCompileLog = testCompile(r.source);
        if (lastCompileLog != null) {
            lastOutcome = Outcome.COMPILE_FAILED;
            return source;
        }
        lastOutcome = Outcome.REWRITTEN;
        return r.source;
    }

    /** null if it compiled; otherwise the compiler log. */
    private static String testCompile(String source) {
        int shader = ARBShaderObjects.glCreateShaderObjectARB(ARBVertexShader.GL_VERTEX_SHADER_ARB);
        try {
            ARBShaderObjects.glShaderSourceARB(shader, source);
            ARBShaderObjects.glCompileShaderARB(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
                return null;
            }
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            return log == null || log.trim().isEmpty() ? "(no log)" : log.trim();
        } finally {
            ARBShaderObjects.glDeleteObjectARB(shader);
        }
    }

    /** Before glLinkProgramARB: binds the offset attribute to location 6. */
    public static void beforeLink(int programId) {
        if (rewriteCurrent && lastOutcome == Outcome.REWRITTEN) {
            ARBVertexShader.glBindAttribLocationARB(programId, ShaderVaos.OFFSET_LOCATION, ShaderRewriter.ATTRIBUTE);
        }
    }

    public static void markPatched(Program program, ShaderRewriter.Result r) {
        patchedIds.add(program.getId());
        String result = "rewritten (id " + program.getId() + ", gl_Vertex x" + r.vertexReplacements + ", ftransform x"
                + r.ftransformReplacements + ", " + r.declaration + ")";
        results.put(program.getName(), result);
        Status.LOG.info("[gadolinium] Program {}: {}", program.getName(), result);
    }

    /** Discards the rewritten program that is no good (if it ever existed). */
    public static void discard(Program program) {
        int id = program.getId();
        if (id != 0) {
            ARBShaderObjects.glDeleteObjectARB(id);
            program.resetId();
        }
    }

    public static void markFallback(Program program, String reason) {
        String result = "NO offset (" + reason + "); program with the original source, id " + program.getId()
                + " -> drawn chunk by chunk";
        results.put(program.getName(), result);
        Status.LOG.warn("[gadolinium] Program {}: {}", program.getName(), result);
    }

    /** Records (once per program) that the terrain was drawn chunk by chunk. */
    public static void notePerChunk(int programId, String programName) {
        if (perChunkLogged.add(programId)) {
            Status.LOG.warn("[gadolinium] Active program {} (id {}) without the offset: terrain drawn chunk by chunk", programName,
                    programId);
            results.put("drawn chunk by chunk", String.valueOf(perChunkLogged));
        }
    }

    private static final Set<Integer> perChunkLogged = Collections.synchronizedSet(new HashSet<>());

    public static Map<String, String> describe() {
        synchronized (results) {
            return new LinkedHashMap<>(results);
        }
    }

    /** Development build only: -Dnthshaders.dumpShaders=true writes the rewritten sources to gadolinium-debug/. */
    private static void dump(String programName, String source) {
        if (DevHooks.ON) {
            if (!Boolean.getBoolean("nthshaders.dumpShaders")) {
                return;
            }
            try {
                File dir = new File("gadolinium-debug");
                dir.mkdirs();
                Files.write(new File(dir, programName + ".vsh").toPath(), source.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Status.LOG.warn("[gadolinium] Could not write the rewritten source of {}", programName, e);
            }
        }
    }

}
