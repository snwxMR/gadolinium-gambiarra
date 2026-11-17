// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import nthcompat.iceandfire.CubePairs;

/** cacheTabulaPoses (TabulaCubeCache): each part stores the matching parts per pose model. Just a field. */
@Mixin(targets = "net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer", remap = false)
public abstract class AdvancedModelRendererPairsMixin implements CubePairs {

    @Unique
    private Object[] nthcompat$pairs;

    @Override
    public Object[] nthcompat$pairs() {
        return this.nthcompat$pairs;
    }

    @Override
    public void nthcompat$setPairs(Object[] pairs) {
        this.nthcompat$pairs = pairs;
    }

}
