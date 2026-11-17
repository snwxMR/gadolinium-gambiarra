// SPDX-License-Identifier: MIT
package nthrc.mixin.vanilla;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.entity.Entity;

/**
 * restingItemsSkipCollision: Entity's private fire field (move reads and writes it). Also the point where the fingerprint of Entity.move and pushOutOfBlocks is checked
 * (VanillaMixinPlugin.preApply, before any mixin in the class).
 */
@Mixin(value = Entity.class, remap = false)
public interface EntityFieldsMixin {

    @Accessor("field_190534_ay")
    int nthrc$fire();

    @Accessor("field_190534_ay")
    void nthrc$setFire(int fire);

}
