// SPDX-License-Identifier: MIT
package gambiarra.mixin.lod;

import javax.vecmath.Vector2f;
import javax.vecmath.Vector4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lycanitesmobs.client.model.ModelCreatureObj;
import com.lycanitesmobs.client.obj.ObjObject;
import com.lycanitesmobs.client.obj.TessellatorModel;
import com.lycanitesmobs.client.renderer.layer.LayerCreatureBase;

import gambiarra.lod.LycanitesLod;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;

/**
 * LOD on Lycanites Mobs 2.0.8.10's .obj models (gambiarra.lod.LycanitesLod), in ModelCreatureObj's render:
 *  - level 2: generateAnimationFrames (the animation frames of all parts) only runs every N frames;
 *  - level 1: the renderGroup of a group too small on screen does not run (the matrix stacking around it stays).
 */
@Mixin(targets = "com.lycanitesmobs.client.model.ModelCreatureObj", remap = false)
public abstract class ModelCreatureObjLodMixin {

    private static final String RENDER = "render(Lnet/minecraft/entity/Entity;FFFFFFLcom/lycanitesmobs/client/renderer/layer/LayerCreatureBase;Z)V";

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/lycanitesmobs/client/model/ModelCreatureObj;generateAnimationFrames"
            + "(Lnet/minecraft/entity/Entity;FFFFFFLcom/lycanitesmobs/client/renderer/layer/LayerCreatureBase;Z)V"))
    private void gambiarra$ModelCreatureObjLodMixin$generateAnimationFrames(ModelCreatureObj model, Entity entity, float time,
            float distance, float loop, float lookY, float lookX, float scale, LayerCreatureBase layer, boolean renderAsTrophy,
            Operation<Void> original) {
        if (LycanitesLod.reuse(model, entity)) {
            return;
        }
        original.call(model, entity, time, distance, loop, lookY, lookX, scale, layer, renderAsTrophy);
        LycanitesLod.afterGenerate(model, entity);
    }

    @WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/lycanitesmobs/client/obj/TessellatorModel;renderGroup"
            + "(Lcom/lycanitesmobs/client/obj/ObjObject;Ljavax/vecmath/Vector4f;Ljavax/vecmath/Vector2f;"
            + "Lnet/minecraft/client/renderer/vertex/VertexFormat;)V"))
    private void gambiarra$ModelCreatureObjLodMixin$renderGroup(TessellatorModel tessellator, ObjObject group, Vector4f color,
            Vector2f textureOffset, VertexFormat format, Operation<Void> original) {
        if (LycanitesLod.skip(group)) {
            return;
        }
        original.call(tessellator, group, color, textureOffset, format);
    }

}
