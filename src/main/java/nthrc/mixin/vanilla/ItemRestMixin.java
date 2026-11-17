// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import nthrc.minecraft.ItemRest;

/**
 * restingItemsSkipCollision (see ItemRest): in EntityItem.onUpdate (func_70071_h_), the pushOutOfBlocks (func_145771_j) and the move (func_70091_d) of a still item repeat
 * the previous tick's result while nothing changes; handleWaterMovement (func_70072_I) repeats "no water".
 *
 * Low priority: this mixin applies first, so its @WrapOperation ends up inside the others on the same move (Universal Tweaks' @WrapWithCondition still decides whether move
 * runs). Aqua Acrobatics' redirect on hasNoGravity comes before the stretch and changes nothing.
 *
 * Extends Entity to read its fields (the jar's remap translates the names); fire is private (EntityFieldsMixin).
 */
@Mixin(value = EntityItem.class, remap = false, priority = 500)
public abstract class ItemRestMixin extends Entity {

    @Shadow
    private int field_70291_e;

    @Unique
    private ItemRest.Memo nthrc$memo;

    protected ItemRestMixin(World world) {
        super(world);
    }

    @Inject(method = "func_70071_h_", at = @At("HEAD"))
    private void nthrc$ItemRestMixin$begin(CallbackInfo ci) {
        if (!ItemRest.on(this.world) || !ItemRest.candidate((EntityItem) (Object) this)) {
            if (nthrc$memo != null) {
                nthrc$memo.clearNow = false;
                nthrc$memo.replay = false;
                nthrc$memo.record = false;
                nthrc$memo.validate = false;
            }
            return;
        }
        if (nthrc$memo == null) {
            nthrc$memo = new ItemRest.Memo();
        }
        ItemRest.beginTick((EntityItem) (Object) this, nthrc$memo);
    }

    @WrapOperation(method = "func_70071_h_", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;func_145771_j(DDD)Z"))
    private boolean nthrc$ItemRestMixin$push(EntityItem self, double x, double y, double z, Operation<Boolean> original) {
        ItemRest.Memo m = nthrc$memo;
        if (m != null && m.clearNow && ItemRest.on(this.world)) {
            nthrc$read(m.in);
            ItemRest.decide(self, m);
            if (m.replay) {
                return ItemRest.replayPush(m);
            }
        }
        boolean pushed = original.call(self, x, y, z);
        if (m != null) {
            m.out.pushed = pushed;
        }
        return pushed;
    }

    @WrapOperation(method = "func_70071_h_", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;func_70091_d(Lnet/minecraft/entity/MoverType;DDD)V"))
    private void nthrc$ItemRestMixin$move(EntityItem self, MoverType type, double x, double y, double z, Operation<Void> original) {
        ItemRest.Memo m = nthrc$memo;
        if (m != null && m.replay) {
            m.replay = false;
            nthrc$write(ItemRest.replayOut(m));
            return;
        }
        original.call(self, type, x, y, z);
        if (m != null && m.record) {
            m.record = false;
            boolean pushed = m.out.pushed;
            nthrc$read(m.out);
            m.out.pushed = pushed;
            ItemRest.recorded(self, m);
            m.validate = false;
        }
    }

    @Inject(method = "func_70072_I", at = @At("HEAD"), cancellable = true)
    private void nthrc$ItemRestMixin$waterHead(CallbackInfoReturnable<Boolean> cir) {
        ItemRest.Memo m = nthrc$memo;
        if (m == null || !ItemRest.on(this.world)) {
            return;
        }
        if (ItemRest.waterReplay((EntityItem) (Object) this, m)) {
            if (ItemRest.VALIDATE) {
                m.waterCheck = true;
                return;
            }
            this.inWater = false;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "func_70072_I", at = @At("RETURN"))
    private void nthrc$ItemRestMixin$waterTail(CallbackInfoReturnable<Boolean> cir) {
        ItemRest.Memo m = nthrc$memo;
        if (m == null || !ItemRest.on(this.world)) {
            return;
        }
        boolean inWater = cir.getReturnValueZ();
        if (m.waterCheck) {
            m.waterCheck = false;
            ItemRest.waterChecked(inWater);
        }
        ItemRest.waterResult((EntityItem) (Object) this, m, inWater);
    }

    /** The item state the stretch reads or writes. */
    @Unique
    private void nthrc$read(ItemRest.State s) {
        AxisAlignedBB bb = this.getEntityBoundingBox();
        s.posX = this.posX;
        s.posY = this.posY;
        s.posZ = this.posZ;
        s.motionX = this.motionX;
        s.motionY = this.motionY;
        s.motionZ = this.motionZ;
        s.minX = bb.minX;
        s.minY = bb.minY;
        s.minZ = bb.minZ;
        s.maxX = bb.maxX;
        s.maxY = bb.maxY;
        s.maxZ = bb.maxZ;
        s.onGround = this.onGround;
        s.collidedHorizontally = this.collidedHorizontally;
        s.collidedVertically = this.collidedVertically;
        s.collided = this.collided;
        s.isInWeb = this.isInWeb;
        s.outsideBorder = this.isOutsideBorder();
        s.isDead = this.isDead;
        s.fallDistance = this.fallDistance;
        s.stepHeight = this.stepHeight;
        s.fire = ((EntityFieldsMixin) (Object) this).nthrc$fire();
        s.health = this.field_70291_e;
    }

    /** The stretch's recorded output (what move and pushOutOfBlocks would write). */
    @Unique
    private void nthrc$write(ItemRest.State s) {
        AxisAlignedBB bb = this.getEntityBoundingBox();
        if (!s.sameBox(bb)) {
            this.setEntityBoundingBox(new AxisAlignedBB(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ));
        }
        this.posX = s.posX;
        this.posY = s.posY;
        this.posZ = s.posZ;
        this.motionX = s.motionX;
        this.motionY = s.motionY;
        this.motionZ = s.motionZ;
        this.onGround = s.onGround;
        this.collidedHorizontally = s.collidedHorizontally;
        this.collidedVertically = s.collidedVertically;
        this.collided = s.collided;
        this.isInWeb = s.isInWeb;
        this.setOutsideBorder(s.outsideBorder);
        this.fallDistance = s.fallDistance;
        ((EntityFieldsMixin) (Object) this).nthrc$setFire(s.fire);
    }

}
