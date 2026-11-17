// SPDX-License-Identifier: MIT
package nthcompat.mixin.iceandfire;

import java.util.IdentityHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import nthcompat.iceandfire.TabulaCubeCache;
import nthcompat.iceandfire.TabulaModelKey;

/** cacheTabulaPoses (TabulaCubeCache): each Tabula model's number and the stored fixed-name lookups. Fields only. */
@Mixin(targets = "com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel", remap = false)
public abstract class TabulaModelKeyMixin implements TabulaModelKey {

    @Unique
    private int nthcompat$modelId;

    @Unique
    private IdentityHashMap<String, Object> nthcompat$named;

    @Override
    public int nthcompat$modelId() {
        if (this.nthcompat$modelId == 0) {
            this.nthcompat$modelId = TabulaCubeCache.nextModelId();
        }
        return this.nthcompat$modelId;
    }

    @Override
    public IdentityHashMap<String, Object> nthcompat$named() {
        if (this.nthcompat$named == null) {
            this.nthcompat$named = new IdentityHashMap<>();
        }
        return this.nthcompat$named;
    }

}
