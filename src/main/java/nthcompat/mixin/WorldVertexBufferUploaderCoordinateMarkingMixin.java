// SPDX-License-Identifier: MIT
package nthcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import nthcompat.optirefine.AtlasCoordinateMarking;

/**
 * Smart Animations, "coordinates" form (config smartAnimationsMarking = coordinates; see AtlasCoordinateMarking): before each immediate
 * draw, marks the animated textures by the texture coordinates of the quads. Outside what OptiRefine does in the method (higher
 * priority): the quads have not become triangles yet.
 */
@Mixin(value = WorldVertexBufferUploader.class, priority = 1500)
public abstract class WorldVertexBufferUploaderCoordinateMarkingMixin {

    @WrapMethod(method = "draw")
    private void nthcompat$WorldVertexBufferUploaderCoordinateMarkingMixin$draw(BufferBuilder buffer, Operation<Void> original) {
        AtlasCoordinateMarking.beforeDraw(buffer);
        original.call(buffer);
    }

}
