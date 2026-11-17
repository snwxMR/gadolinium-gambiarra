// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.BufferBuilder;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * TEMPORARY, until OptiRefine fixes it (issue text in docs/issue-optirefine-floatbuffer.md; option fixOptiRefineFloatBuffer).
 *
 * OptiRefine's MixinBufferBuilder rebuilds rawFloatBuffer at the end of EVERY growBuffer (which runs per vertex) with
 * byteBuffer.asFloatBuffer(), and that view starts at the buffer's CURRENT position. WorldVertexBufferUploader leaves the position at
 * the offset of the last element of the format drawn (20 after a POSITION_TEX_COLOR). With the view shifted, OptiFine's
 * SVertexBuilder.calcNormal (shaders only) reads and writes the quad in the wrong place: midV lands on top of the x of vertices 1 to
 * 3, and the error stays recorded in the model's display list (bent chest lid, stretched strips coming out of mobs).
 *
 * Here, at the end of growBuffer and after OptiRefine (higher priority = applied later = runs after it at the end of the method), the
 * view goes back to starting at byte 0, as in the original OptiFine. It only acts when the view is shifted: if OptiRefine fixes it,
 * this stops doing anything.
 *
 * The anchored view of each BufferBuilder is kept and reused (clear(): position 0 and limit at the end, like a new one); it is only
 * rebuilt when the byte buffer changes (when it grows). Before 0.9.0 it was two new views per vertex (14 to 52 MB/s of garbage at the
 * frozen lake).
 */
@Mixin(value = BufferBuilder.class, priority = 1500)
public abstract class BufferBuilderFloatViewMixin {

    @Shadow
    private ByteBuffer byteBuffer;

    @Shadow
    private FloatBuffer rawFloatBuffer;

    @Inject(method = "growBuffer", at = @At("TAIL"))
    private void nthcompat$anchorFloatView(int increaseAmount, CallbackInfo ci) {
        ByteBuffer bytes = this.byteBuffer;
        FloatBuffer floats = this.rawFloatBuffer;
        if (bytes == null || floats == null || !bytes.isDirect() || !floats.isDirect()) {
            return;
        }
        if (MemoryUtil.memAddress0(floats) == MemoryUtil.memAddress0(bytes)) {
            return;
        }
        FloatBuffer anchored = this.nthcompat$anchoredFloats;
        if (anchored == null || !CompatConfig.fixOptiRefineFloatBufferReuseView || this.nthcompat$anchoredBytes != bytes
                || anchored.order() != bytes.order()) {
            ByteBuffer whole = bytes.duplicate();
            whole.clear();
            anchored = whole.order(bytes.order()).asFloatBuffer();
            this.nthcompat$anchoredBytes = bytes;
            this.nthcompat$anchoredFloats = anchored;
        } else {
            anchored.clear();
        }
        this.rawFloatBuffer = anchored;
        if (!nthcompat$reported) {
            nthcompat$reported = true;
            CompatStatus.markRan("BufferBuilder float view",
                    "shifted view " + (MemoryUtil.memAddress0(floats) - MemoryUtil.memAddress0(bytes)) + " bytes, anchored at the start");
        }
    }

    @Unique
    private static volatile boolean nthcompat$reported;

    /** The stored anchored view and the byte buffer it is a view of. */
    @Unique
    private ByteBuffer nthcompat$anchoredBytes;
    @Unique
    private FloatBuffer nthcompat$anchoredFloats;

}
