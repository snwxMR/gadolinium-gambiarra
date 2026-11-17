// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.optifine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.optifine.shaders.Program;
import nthshaders.common.DevHooks;
import nthshaders.core.Status;
import nthshaders.render.ShaderPrograms;
import nthshaders.render.ShaderRewriter;

/**
 * Rewrites the pack's vertex shaders. See ShaderPrograms.
 *
 *  - setupProgram (@WrapMethod): builds the program with the rewritten source and records the result. If the rewrite changed
 *    nothing or did not compile, OptiFine already received the original source; if the program did not link/validate (id 0),
 *    it is deleted and built again with the original source. In those cases the program is marked for chunk by chunk drawing.
 *  - createVertShader, at glShaderSourceARB (@WrapOperation): swaps the complete source (includes and options applied) for the
 *    rewritten one, if that compiles in a test shader.
 *  - setupProgram, at glLinkProgramARB (@WrapOperation): binds nth_ChunkOffset to location 6 before linking.
 *  - init/uninit (HEAD): the old programs are deleted; forgets the ids.
 */
@Mixin(targets = "net.optifine.shaders.Shaders", remap = false, priority = 1500)
public abstract class ShadersMixin {

    @WrapMethod(method = "setupProgram")
    private static void nthshaders$ShadersMixin$setupProgram(Program program, String vShaderPath, String gShaderPath,
            String fShaderPath, Operation<Void> original) {
        Status.onShaderProgramSetup(program.getName());
        if (!ShaderPrograms.shouldRewrite(program)) {
            original.call(program, vShaderPath, gShaderPath, fShaderPath);
            return;
        }
        ShaderRewriter.Result rewrite;
        ShaderPrograms.begin(program, true);
        try {
            original.call(program, vShaderPath, gShaderPath, fShaderPath);
        } finally {
            rewrite = ShaderPrograms.end();
        }
        ShaderPrograms.Outcome outcome = ShaderPrograms.lastOutcome();
        if (rewrite == null || outcome == null) {
            // The pack does not have this vertex shader: nothing was rewritten.
            return;
        }
        if (outcome == ShaderPrograms.Outcome.ZERO_SUBSTITUTIONS) {
            ShaderPrograms.markFallback(program, "no gl_Vertex/ftransform() to replace");
            return;
        }
        if (outcome == ShaderPrograms.Outcome.COMPILE_FAILED) {
            ShaderPrograms.markFallback(program, "the rewritten source did not compile: " + firstLine(ShaderPrograms.lastCompileLog()));
            return;
        }
        boolean testLinkFailure = false;
        if (DevHooks.ON) {
            testLinkFailure = (program.getName() + ":link").equals(System.getProperty("nthshaders.testRewriteFail"));
        }
        if (program.getId() != 0 && !testLinkFailure) {
            ShaderPrograms.markPatched(program, rewrite);
            return;
        }
        if (DevHooks.ON) {
            if (testLinkFailure) {
                Status.LOG.warn("[gadolinium] [TEST] Program {} treated as not linked", program.getName());
            }
        }
        String failure = "the rewritten source compiled, but the program did not link or validate";
        ShaderPrograms.discard(program);
        ShaderPrograms.begin(program, false);
        try {
            original.call(program, vShaderPath, gShaderPath, fShaderPath);
        } finally {
            ShaderPrograms.end();
        }
        ShaderPrograms.markFallback(program, failure + "; built again");
    }

    /** First error line of the compiler log (the warnings come first and say nothing). */
    private static String firstLine(String log) {
        if (log == null) {
            return "(no log)";
        }
        String[] lines = log.split("\n");
        for (String line : lines) {
            if (line.toLowerCase(java.util.Locale.ROOT).contains("error")) {
                return line.trim();
            }
        }
        return lines[0].trim();
    }

    @WrapOperation(method = "createVertShader", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBShaderObjects;glShaderSourceARB(ILjava/lang/CharSequence;)V"))
    private static void nthshaders$ShadersMixin$shaderSource(int shader, CharSequence source, Operation<Void> original) {
        original.call(shader, ShaderPrograms.vertexSource(source));
    }

    @WrapOperation(method = "setupProgram", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBShaderObjects;glLinkProgramARB(I)V"))
    private static void nthshaders$ShadersMixin$link(int programId, Operation<Void> original) {
        ShaderPrograms.beforeLink(programId);
        original.call(programId);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private static void nthshaders$ShadersMixin$init(CallbackInfo ci) {
        // init() only recreates the programs when the pack has not been initialized yet.
        if (!net.optifine.shaders.Shaders.isShaderPackInitialized) {
            ShaderPrograms.reset();
        }
    }

    @Inject(method = "uninit", at = @At("HEAD"))
    private static void nthshaders$ShadersMixin$uninit(CallbackInfo ci) {
        ShaderPrograms.reset();
        nthshaders.render.ShaderOffProbe.onShadersUninit();
    }

}
