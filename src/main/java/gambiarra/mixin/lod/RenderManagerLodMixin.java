// SPDX-License-Identifier: MIT
package gambiarra.mixin.lod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import gambiarra.lod.Lod;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

/** Entity LOD (gambiarra.lod.Lod): each entity's context around its Render.doRender. */
@Mixin(RenderManager.class)
public abstract class RenderManagerLodMixin {

    @WrapOperation(method = "renderEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void gambiarra$RenderManagerLodMixin$doRender(Render<Entity> render, Entity entity, double x, double y, double z, float yaw,
            float partialTicks, Operation<Void> original) {
        Lod.render(render, entity, x, y, z, yaw, partialTicks, original);
    }

}
