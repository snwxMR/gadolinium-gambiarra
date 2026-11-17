// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.mixin.nothirium;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.api.renderer.chunk.IRenderChunkProvider;
import meldexun.nothirium.mc.renderer.chunk.ChunkRendererDynamicVbo;
import meldexun.nothirium.util.collection.Enum2IntMap;
import meldexun.nothirium.util.collection.Enum2ObjMap;
import meldexun.nothirium.util.collection.IntMultiObject;
import meldexun.nothirium.util.collection.MultiObject;
import meldexun.renderlib.util.Frustum;
import meldexun.renderlib.util.GLBuffer;
import meldexun.nothirium.mc.renderer.chunk.RenderChunk;
import meldexun.renderlib.util.RenderUtil;
import net.optifine.shaders.Shaders;
import nthshaders.core.Status;
import nthshaders.render.BufferSync;
import nthshaders.render.GlErrorGuard;
import nthshaders.render.ShaderModeRenderer;
import nthshaders.render.ShaderPrograms;
import nthshaders.render.ShadowArea;
import nthshaders.render.ShaderVaos;

/**
 * Nothirium's GL43 in shader mode (only on renderers RendererSelection created that way; the others stay untouched).
 *
 *  - initVAOs (TAIL): besides Nothirium's VAOs (28-byte format, its own shader), builds a VAO in OptiFine's format for each
 *    layer and each buffer copy (Nothirium alternates between the copies on each setup).
 *  - setup (HEAD): negative frame in the shadow pass, like GL15; and the section volume of the shadow pass (ShadowArea), the
 *    same one OptiFine would draw.
 *  - setup (before writing): measures whether the GPU is still using the copy (BufferSync).
 *  - record (TAIL): offset of each chunk relative to the camera entity.
 *  - renderChunks (@WrapMethod): draws with that VAO and the pack's program, instead of Nothirium's shader, and zeroes the
 *    offset right after. With the active program rewritten (ShaderPrograms), one multi-draw; otherwise, chunk by chunk like
 *    GL15. After the draw, the time query setup waits for and the measurement fence.
 *  - dispose (TAIL): deletes those VAOs and fences.
 *
 * When the VAOs are created and around each draw (in the validation window), GlErrorGuard checks for the addon's OpenGL errors
 * and falls back to GL15 if there are any.
 *
 * Extends the target's superclass only to see the inherited fields (vbos, chunks), as Mixin requires.
 */
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL43", remap = false, priority = 1500)
public abstract class ChunkRendererGL43Mixin extends ChunkRendererDynamicVbo implements ShaderModeRenderer {

    @Shadow
    @Final
    private MultiObject<Enum2IntMap<ChunkRenderPass>> vaos;

    @Shadow
    @Final
    private MultiObject<Enum2ObjMap<ChunkRenderPass, GLBuffer>> offsetBuffers;

    @Shadow
    @Final
    private MultiObject<Enum2ObjMap<ChunkRenderPass, GLBuffer>> commandBuffers;

    @Shadow
    @Final
    private IntMultiObject syncs;

    /** Measurement fence (BufferSync) of each buffer copy; 0 = none. */
    @Unique
    private long[] nthshaders$fences;

    /** Key: Nothirium's VAO map of each buffer copy. Value: our VAOs per layer. */
    @Unique
    private Map<Object, int[]> nthshaders$shaderVaos;

    /** Last VBO of each layer (to notice when it grows). */
    @Unique
    private int[] nthshaders$lastVbo;

    @Unique
    private boolean nthshaders$warnedMissingVao;

    /** Set by RendererSelection right after creating the renderer. */
    @Unique
    private boolean nthshaders$shaderMode;

    /**
     * Before setup writes offsets and commands (after Nothirium's wait): the BufferSync measurement, with the fence placed after
     * the last draw of this copy.
     */
    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lmeldexun/nothirium/util/collection/Enum2ObjMap;forEach(Ljava/util/function/BiConsumer;)V"))
    private void nthshaders$ChunkRendererGL43Mixin$checkBeforeWrite(IRenderChunkProvider<RenderChunk> provider, double cameraX,
            double cameraY, double cameraZ, Frustum frustum, int frame, CallbackInfo ci) {
        if (!this.nthshaders$shaderMode || !BufferSync.measuring()) {
            return;
        }
        long[] fences = this.nthshaders$fences();
        int copy = this.nthshaders$copyIndex();
        fences[copy] = BufferSync.checkBeforeWrite(fences[copy]);
    }

    @Unique
    private int nthshaders$copies() {
        return (int) this.vaos.stream().count();
    }

    /** Which copy setup chose (MultiObject does not expose the index). */
    @Unique
    private int nthshaders$copyIndex() {
        Enum2IntMap<ChunkRenderPass> current = this.vaos.get();
        int copies = this.nthshaders$copies();
        for (int i = 0; i < copies; i++) {
            if (this.vaos.get(i) == current) {
                return i;
            }
        }
        return 0;
    }

    @Unique
    private long[] nthshaders$fences() {
        if (this.nthshaders$fences == null) {
            this.nthshaders$fences = new long[this.nthshaders$copies()];
        }
        return this.nthshaders$fences;
    }

    @Inject(method = "initVAOs", at = @At("TAIL"))
    private void nthshaders$ChunkRendererGL43Mixin$initVAOs(ChunkRenderPass pass, CallbackInfo ci) {
        if (!this.nthshaders$shaderMode) {
            return;
        }
        if (this.nthshaders$shaderVaos == null) {
            this.nthshaders$shaderVaos = new IdentityHashMap<>();
            this.nthshaders$lastVbo = new int[ChunkRenderPass.ALL.length];
        }
        int vbo = this.vbos.get(pass).getVbo();
        if (this.nthshaders$lastVbo[pass.ordinal()] != 0 && this.nthshaders$lastVbo[pass.ordinal()] != vbo) {
            // The layer's VBO grew (new buffer): BufferSync diagnostics.
            BufferSync.noteVaoRebuild();
        }
        this.nthshaders$lastVbo[pass.ordinal()] = vbo;
        GlErrorGuard.before("vao");
        this.vaos.forEach((i, copy) -> {
            int[] perPass = this.nthshaders$shaderVaos.computeIfAbsent(copy, k -> new int[ChunkRenderPass.ALL.length]);
            ShaderVaos.delete(perPass[pass.ordinal()]);
            perPass[pass.ordinal()] = ShaderVaos.create(vbo, this.offsetBuffers.get(i).get(pass).getBuffer());
        });
        GlErrorGuard.after("vao");
    }

    /**
     * The same trick as Nothirium's ChunkRendererGL15Optifine: in the shadow pass, the frame number goes negative. The visible
     * chunk search marks each chunk with the frame; with the same number in both passes, the main pass search skipped everything
     * the shadow one had marked and 1 section was left drawn. ChunkRendererGL43Optifine does not do this because, without the addon,
     * GL43 never ran with shaders.
     */
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int nthshaders$ChunkRendererGL43Mixin$setupFrame(int frame) {
        return this.nthshaders$shaderMode && Shaders.isShadowPass ? -frame : frame;
    }

    /**
     * Shadow pass: builds the section volume OptiFine would draw (ShadowArea); the frustum test of each section outside it answers
     * "out of view" (RenderChunkShadowAreaMixin).
     */
    @Inject(method = "setup", at = @At("HEAD"))
    private void nthshaders$ChunkRendererGL43Mixin$shadowArea(IRenderChunkProvider<RenderChunk> provider, double cameraX, double cameraY,
            double cameraZ, Frustum frustum, int frame, CallbackInfo ci) {
        ShadowArea.beginSetup(this.nthshaders$shaderMode);
    }

    /**
     * In shader mode, the offset is relative to the camera ENTITY, like GL15's glTranslated (the game's gl_ModelViewMatrix is
     * relative to it): the difference is done in double and only becomes float at the end. Nothirium wrote its version before
     * (camera + ActiveRenderInfo adjustment, for its own shader); here it is overwritten.
     */
    @Inject(method = "record", at = @At("TAIL"))
    private void nthshaders$ChunkRendererGL43Mixin$record(RenderChunk renderChunk, ChunkRenderPass pass, int index, double cameraX,
            double cameraY, double cameraZ, CallbackInfo ci) {
        if (!this.nthshaders$shaderMode) {
            return;
        }
        GLBuffer offsetBuffer = this.offsetBuffers.get().get(pass);
        offsetBuffer.putFloat(index * 12, (float) (renderChunk.getX() - RenderUtil.getCameraEntityX()));
        offsetBuffer.putFloat(index * 12 + 4, (float) (renderChunk.getY() - RenderUtil.getCameraEntityY()));
        offsetBuffer.putFloat(index * 12 + 8, (float) (renderChunk.getZ() - RenderUtil.getCameraEntityZ()));
    }

    @WrapMethod(method = "renderChunks")
    private void nthshaders$ChunkRendererGL43Mixin$renderChunks(ChunkRenderPass pass, Operation<Void> original) {
        if (!this.nthshaders$shaderMode) {
            nthshaders.render.ShaderOffProbe.atTerrain(this, pass);
            original.call(pass);
            return;
        }
        int[] perPass = this.nthshaders$shaderVaos != null ? this.nthshaders$shaderVaos.get(this.vaos.get()) : null;
        int vao = perPass != null ? perPass[pass.ordinal()] : 0;
        if (vao == 0) {
            // Should never happen. Drawing with the 28-byte VAO over 56-byte data would be garbage on screen: better to skip the layer and
            // warn.
            if (!this.nthshaders$warnedMissingVao) {
                this.nthshaders$warnedMissingVao = true;
                Status.LOG.error("[gadolinium] Shader mode VAO missing for {}: layer not drawn", pass);
            }
            return;
        }
        boolean check = GlErrorGuard.before("draw");
        int program = Shaders.activeProgramID;
        if (ShaderPrograms.isPatched(program)) {
            ShaderVaos.draw(vao, this.commandBuffers.get().get(pass).getBuffer(), this.chunks.get(pass).size(), pass);
        } else {
            ShaderPrograms.notePerChunk(program, Shaders.activeProgram != null ? Shaders.activeProgram.getName() : "?");
            ShaderVaos.drawPerChunk(vao, this.chunks.get(pass), pass);
        }
        this.nthshaders$afterDraw();
        if (check) {
            GlErrorGuard.after("draw");
        }
    }

    /**
     * After each layer drawn with the current copy:
     *  - the time query Nothirium's setup waits for before writing to this copy again. Nothirium only places it after the
     *    translucent layer, and the shadow pass may not draw translucents: without it, there is no wait at all. The last layer of
     *    the pass counts (the previous ones are replaced);
     *  - the measurement fence (BufferSync), independent of that wait.
     */
    @Unique
    private void nthshaders$afterDraw() {
        if (BufferSync.measuring()) {
            long[] fences = this.nthshaders$fences();
            int copy = this.nthshaders$copyIndex();
            fences[copy] = BufferSync.replaceFence(fences[copy]);
        }
        if (BufferSync.queryPerLayer()) {
            int old = this.syncs.getInt();
            if (old != -1) {
                GL15.glDeleteQueries(old);
            }
            int query = GL15.glGenQueries();
            GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
            this.syncs.set(query);
        }
    }

    @Override
    public boolean nthshaders$isShaderMode() {
        return this.nthshaders$shaderMode;
    }

    @Override
    public void nthshaders$setShaderMode(boolean shaderMode) {
        this.nthshaders$shaderMode = shaderMode;
    }

    @Override
    public int nthshaders$bufferCopies() {
        return this.nthshaders$copies();
    }

    /**
     * Diagnostics: counts the sections uploaded to the GPU per pass (proof that there are rebuilds during the shadow pass).
     * GL43 does not declare this method; the mixin adds it, calling the original.
     */
    @Override
    public IVBOPart buffer(ChunkRenderPass pass, ByteBuffer data) {
        if (this.nthshaders$shaderMode) {
            BufferSync.noteUpload();
        }
        return super.buffer(pass, data);
    }

    @Inject(method = "dispose", at = @At("TAIL"))
    private void nthshaders$ChunkRendererGL43Mixin$dispose(CallbackInfo ci) {
        if (this.nthshaders$shaderVaos != null) {
            for (int[] perPass : this.nthshaders$shaderVaos.values()) {
                for (int vao : perPass) {
                    ShaderVaos.delete(vao);
                }
            }
            this.nthshaders$shaderVaos.clear();
        }
        if (this.nthshaders$fences != null) {
            for (long fence : this.nthshaders$fences) {
                BufferSync.deleteFence(fence);
            }
            this.nthshaders$fences = null;
        }
    }

}
