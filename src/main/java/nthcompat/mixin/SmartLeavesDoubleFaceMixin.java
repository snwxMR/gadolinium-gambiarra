// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import nthcompat.CompatStatus;

/**
 * General fix, NOT tied to Nothirium (option fixSmartLeavesVertexData; issue text in docs/issue-loliasm-smartleaves.md).
 *
 * OptiFine's SmartLeaves.getModelDoubleFace builds the back of each leaf face like this: new BakedQuad(face.getVertexData().clone(),
 * ...) and then reverses IN PLACE the order of that new quad's 4 vertices (4 System.arraycopy into a temporary array and a fifth, back,
 * over the quad's array). With LoliASM (vertexDataCanonicalization=true) BakedQuad's constructor swaps the array for an equal one that
 * already exists, which is the vanilla face's own: the in-place reversal turns the vanilla leaf model inside out (only the back face
 * shows). It only happens with LoliASM + OptiFine, without Nothirium.
 *
 * Here the back is born with its own array, already reversed, and the copy back (the one that would reverse in place) does not happen.
 * No shared array is changed, and LoliASM's memory saving stays. Each injection alone is already safe: if only one applies, the back
 * ends up equal to the front, but the vanilla model is never changed. Without LoliASM the result is the same as OptiFine's.
 */
@Mixin(targets = "net.optifine.SmartLeaves", remap = false, priority = 1500)
public abstract class SmartLeavesDoubleFaceMixin {

    @WrapOperation(method = "getModelDoubleFace", at = @At(value = "NEW",
            target = "([IILnet/minecraft/util/EnumFacing;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Lnet/minecraft/client/renderer/block/model/BakedQuad;"))
    private static BakedQuad nthcompat$backFaceOwnArray(int[] vertexData, int tintIndex, EnumFacing face, TextureAtlasSprite sprite,
            Operation<BakedQuad> original) {
        if (vertexData == null || vertexData.length == 0 || vertexData.length % 4 != 0) {
            return original.call(vertexData, tintIndex, face, sprite);
        }
        CompatStatus.markRan("SmartLeaves leaves", "back of leaves with its own array, no in-place reversal");
        return original.call(nthcompat$reversed(vertexData), tintIndex, face, sprite);
    }

    /** Skips only the copy back over the quad's array (whole source and destination, from 0). */
    @WrapOperation(method = "getModelDoubleFace", at = @At(value = "INVOKE",
            target = "Ljava/lang/System;arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V"))
    private static void nthcompat$noInPlaceReverse(Object src, int srcPos, Object dest, int destPos, int length,
            Operation<Void> original) {
        if (srcPos == 0 && destPos == 0 && src != dest && dest instanceof int[] && length == ((int[]) dest).length) {
            return;
        }
        original.call(src, srcPos, dest, destPos, length);
    }

    /** The same order swap OptiFine does: vertex 0 <-> 3, 1 <-> 2. */
    @Unique
    private static int[] nthcompat$reversed(int[] vd) {
        int step = vd.length / 4;
        int[] r = new int[vd.length];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(vd, i * step, r, (3 - i) * step, step);
        }
        return r;
    }

}
