// SPDX-License-Identifier: LGPL-3.0-only
/*
 * Part of this file (the method nthcompat$renderFull) is derived from renderEntities of
 * OptiRefine (https://github.com/Ecdcaeb/OptiRefine), file
 * src/main/java/mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/MixinRenderGlobal.java,
 * commit 78ff8ce408e6687093d95f19218ffda3214c0475, under the licence below.
 * Changes: the two loops over renderInfos (entities and tile entities of the
 * chunks) were replaced by RenderLib's renderers; the block breaking condition
 * follows OptiRefine's fix in v0.1.0-alpha (commit 6c4855e). The other change of
 * that release in this method (a frustum test on the global tile entities) is in
 * one of the replaced loops: RenderLib already tests them.
 *
 * MIT License
 *
 * Copyright (c) 2026 Hileb
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package nthshaders.mixin.compat;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import meldexun.renderlib.renderer.EntityRenderManager;
import meldexun.renderlib.renderer.TileEntityRenderManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.MinecraftForgeClient;
import net.optifine.shaders.Shaders;
import nthshaders.compat.ShadersCompat;
import nthshaders.compat.EntityFixSupport;

/**
 * Entities and tile entities with Nothirium + OptiRefine.
 *
 * OptiRefine reimplements renderEntities (@WrapMethod, without calling the original) and looks for the visible chunks'
 * entities/tile entities in RenderGlobal.renderInfos, a list only vanilla setupTerrain fills. Nothirium cancels vanilla
 * setupTerrain, the list stays empty and mobs, chests, signs etc. do not show up. RenderLib's @Injects in this method
 * (EntityRenderManager setup/reset) are also dead, inside the original body.
 *
 * Two modes, chosen by capability in EntityFixSupport.mode():
 *  - FULL (all members found, OptiRefine recognized or not): OptiRefine's body, adapted below from its source (MIT), with the
 *    two chunk loops replaced by RenderLib, as RenderLib's own ASM patch did for classic OptiFine (with RenderLib's entity
 *    culling).
 *  - FALLBACK (last resort: a member only FULL uses is missing): runs OptiRefine as it is and then draws the chunks'
 *    entities/tile entities through RenderLib. Less faithful (the order of the shader hooks changes and global tile entities
 *    may be drawn twice), but invisible entities are worse.
 *
 * @WrapMethod with a higher priority than OptiRefine's: stays outside it.
 */
@Mixin(value = RenderGlobal.class, priority = 1500)
public abstract class RenderGlobalEntitiesMixin {

    @Shadow @Final private Minecraft mc;
    @Shadow private WorldClient world;
    @Shadow @Final private RenderManager renderManager;
    @Shadow private int renderEntitiesStartupCounter;
    @Shadow private int countEntitiesTotal;
    @Shadow private int countEntitiesRendered;
    @Shadow private int countEntitiesHidden;
    @Shadow @Final private Map<Integer, DestroyBlockProgress> damagedBlocks;
    @Shadow private ChunkRenderDispatcher renderDispatcher;

    @Shadow
    protected abstract void preRenderDamagedBlocks();

    @Shadow
    protected abstract void postRenderDamagedBlocks();

    @WrapMethod(method = "renderEntities")
    private void nthcompat$RenderGlobalEntitiesMixin$renderEntities(Entity entityIn, ICamera camera, float partialTicks,
            Operation<Void> original) {
        // renderDispatcher != null: vanilla chunk pipeline active (no Nothirium), renderInfos is filled.
        if (this.renderDispatcher != null) {
            original.call(entityIn, camera, partialTicks);
            return;
        }
        ShadersCompat.EntityMode mode = EntityFixSupport.mode();
        if (mode == ShadersCompat.EntityMode.OFF) {
            original.call(entityIn, camera, partialTicks);
            return;
        }
        int pass = MinecraftForgeClient.getRenderPass();
        // What RenderLib would do at this method's HEAD/RETURN (its @Injects are dead here).
        if (pass == 0) {
            EntityRenderManager.setup(camera, partialTicks);
            TileEntityRenderManager.setup(camera, partialTicks);
        }
        try {
            if (mode == ShadersCompat.EntityMode.FULL) {
                ShadersCompat.markRan("entities", "FULL mode");
                nthcompat$renderFull((RenderGlobal) (Object) this, entityIn, partialTicks, pass);
            } else {
                ShadersCompat.markRan("entities", "FALLBACK mode");
                boolean startup = this.renderEntitiesStartupCounter > 0;
                original.call(entityIn, camera, partialTicks);
                if (!startup) {
                    EntityFixSupport.renderChunkEntitiesAfterOptiRefine(this.mc, this.world, partialTicks, pass);
                }
            }
        } finally {
            if (pass == 1) {
                EntityRenderManager.reset();
                TileEntityRenderManager.reset();
            }
        }
    }

    /** OptiRefine's renderEntities body @ 78ff8ce, with the chunk loops replaced by RenderLib and the breaking condition of v0.1.0-alpha. */
    private void nthcompat$renderFull(RenderGlobal self, Entity entityIn, float partialTicks, int pass) {
        if (this.renderEntitiesStartupCounter > 0) {
            if (pass > 0) {
                return;
            }
            --this.renderEntitiesStartupCounter;
        } else {
            double d0 = entityIn.prevPosX + (entityIn.posX - entityIn.prevPosX) * partialTicks;
            double d1 = entityIn.prevPosY + (entityIn.posY - entityIn.prevPosY) * partialTicks;
            double d2 = entityIn.prevPosZ + (entityIn.posZ - entityIn.prevPosZ) * partialTicks;
            this.world.profiler.startSection("prepare");
            TileEntityRendererDispatcher.instance.prepare(this.world, this.mc.getTextureManager(), this.mc.fontRenderer, this.mc.getRenderViewEntity(), this.mc.objectMouseOver, partialTicks);
            this.renderManager.cacheActiveRenderInfo(this.world, this.mc.fontRenderer, this.mc.getRenderViewEntity(), this.mc.pointedEntity, this.mc.gameSettings, partialTicks);
            EntityFixSupport.addRenderEntitiesCounter(1);
            if (pass == 0) {
                this.countEntitiesTotal = 0;
                this.countEntitiesRendered = 0;
                this.countEntitiesHidden = 0;
                EntityFixSupport.resetCountTileEntitiesRendered(self);
            }
            Entity entity = this.mc.getRenderViewEntity();
            double d3 = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
            double d4 = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
            double d5 = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
            TileEntityRendererDispatcher.staticPlayerX = d3;
            TileEntityRendererDispatcher.staticPlayerY = d4;
            TileEntityRendererDispatcher.staticPlayerZ = d5;
            this.renderManager.setRenderPosition(d3, d4, d5);
            this.mc.entityRenderer.enableLightmap();
            this.world.profiler.endStartSection("global");
            List<Entity> list = this.world.getLoadedEntityList();
            if (pass == 0) {
                this.countEntitiesTotal = list.size();
            }
            if (EntityFixSupport.isFogOffStandard(this.mc.entityRenderer)) {
                GlStateManager.disableFog();
            }
            for (int i = 0; i < this.world.weatherEffects.size(); ++i) {
                Entity entity1 = this.world.weatherEffects.get(i);
                if (!entity1.shouldRenderInPass(pass)) {
                    continue;
                }
                ++this.countEntitiesRendered;
                if (entity1.isInRangeToRender3d(d0, d1, d2)) {
                    this.renderManager.renderEntityStatic(entity1, partialTicks, false);
                }
            }
            this.world.profiler.endStartSection("entities");
            boolean flag = Shaders.shaderPackLoaded;
            if (flag) {
                Shaders.beginEntities();
            }
            EntityFixSupport.updateItemRenderDistance();
            // [gadolinium] Here OptiRefine walked renderInfos (empty with Nothirium).
            // Like RenderLib's patch: entities, multipass, outline and glowing.
            EntityRenderManager.renderEntities(partialTicks);
            if (pass == 0) {
                this.countEntitiesRendered += EntityRenderManager.renderedEntities();
                this.countEntitiesHidden += EntityRenderManager.occludedEntities();
            }
            if (flag) {
                Shaders.endEntities();
                Shaders.beginBlockEntities();
            }
            this.world.profiler.endStartSection("blockentities");
            RenderHelper.enableStandardItemLighting();
            TileEntityRendererDispatcher.instance.preDrawBatch();
            EntityFixSupport.updateTextRenderDistance();
            // [gadolinium] Here OptiRefine walked renderInfos and setTileEntities.
            TileEntityRenderManager.renderTileEntities(partialTicks);
            TileEntityRendererDispatcher.instance.drawBatch(pass);
            EntityFixSupport.setRenderOverlayDamaged(self, true);
            this.preRenderDamagedBlocks();
            for (DestroyBlockProgress destroyblockprogress : this.damagedBlocks.values()) {
                BlockPos blockpos = destroyblockprogress.getPosition();
                if (this.world.getBlockState(blockpos).getBlock().hasTileEntity()) {
                    TileEntity tileentity2 = this.world.getTileEntity(blockpos);
                    if (tileentity2 instanceof TileEntityChest) {
                        TileEntityChest tileentitychest = (TileEntityChest) tileentity2;
                        if (tileentitychest.adjacentChestXNeg != null) {
                            blockpos = blockpos.offset(EnumFacing.WEST);
                            tileentity2 = this.world.getTileEntity(blockpos);
                        } else if (tileentitychest.adjacentChestZNeg != null) {
                            blockpos = blockpos.offset(EnumFacing.NORTH);
                            tileentity2 = this.world.getTileEntity(blockpos);
                        }
                    }
                    IBlockState iblockstate = this.world.getBlockState(blockpos);
                    // The condition of OptiRefine v0.1.0-alpha (and of OptiFine on Forge); 78ff8ce drew the breaking stage on any block with a
                    // tile entity, in both passes.
                    if (tileentity2 != null && (iblockstate.hasCustomBreakingProgress() || tileentity2.canRenderBreaking())
                            && tileentity2.shouldRenderInPass(pass)) {
                        if (flag) {
                            Shaders.nextBlockEntity(tileentity2);
                        }
                        TileEntityRendererDispatcher.instance.render(tileentity2, partialTicks, destroyblockprogress.getPartialBlockDamage());
                    }
                }
            }
            this.postRenderDamagedBlocks();
            EntityFixSupport.setRenderOverlayDamaged(self, false);
            if (flag) {
                Shaders.endBlockEntities();
            }
            EntityFixSupport.addRenderEntitiesCounter(-1);
            this.mc.entityRenderer.disableLightmap();
            this.mc.profiler.endSection();
        }
    }

}
