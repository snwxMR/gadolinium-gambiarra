// SPDX-License-Identifier: MIT
package nthrc.mixin.rc;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.world.gen.feature.RCGenerationDiagnostics;
import ivorius.reccomplex.world.gen.feature.RCWorldgenMonitor;
import ivorius.reccomplex.world.gen.feature.StructureGenerator;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import net.minecraft.nbt.NBTBase;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import nthrc.Hook;
import nthrc.rc.EarlyReject;

/**
 * Optimization F1 (see EarlyReject). For natural generation (SUGGEST) with no ready instance data, does the same steps as generateInternal up to placement ("preparing"
 * label, placement, same failure on exception or with no place); with the box placed (RC keeps it), checks limits and overlap. Refused: returns the same failure, without
 * preparing. Otherwise: calls the original, which finds the box already placed and carries on the same. With Volts' generation diagnostics on, it does not act.
 */
@Mixin(targets = "ivorius.reccomplex.world.gen.feature.StructureGenerator", remap = false, priority = 1500)
public abstract class RejectBeforePreparationMixin {

    @Shadow
    private NBTStorable instanceData;

    @Shadow
    private NBTBase instanceDataNBT;

    @Shadow
    private boolean allowOverlaps;

    @Shadow
    public abstract String structureID();

    @Shadow
    public abstract Environment environment();

    @Shadow
    public abstract Optional<StructureBoundingBox> boundingBox();

    @Shadow
    public abstract StructureSpawnContext.GenerateMaturity maturity();

    @Shadow
    protected abstract StructureGenerator.GenerationResult.Failure failGenerate(StructureGenerator.GenerationResult.Failure failure);

    @WrapMethod(method = "generateInternal")
    private StructureGenerator.GenerationResult nthrc$RejectBeforePreparationMixin$generate(
            Operation<StructureGenerator.GenerationResult> original) {
        if (!Hook.RC_REJECT.ok || instanceData != null || instanceDataNBT != null || !maturity().isSuggest()
                || RCGenerationDiagnostics.get().enabled()) {
            return original.call();
        }
        Optional<StructureBoundingBox> bb;
        RCWorldgenMonitor.start("preparing " + structureID());
        try {
            bb = boundingBox();
        } catch (Exception e) {
            return failGenerate(new StructureGenerator.GenerationResult.Failure.Exception(e));
        } finally {
            RCWorldgenMonitor.stop();
        }
        if (!bb.isPresent()) {
            return failGenerate(StructureGenerator.GenerationResult.Failure.placement);
        }
        StructureGenerator.GenerationResult.Failure verdict = EarlyReject.verdict(bb.get(), environment().world, allowOverlaps);
        if (verdict == null) {
            return original.call();
        }
        if (EarlyReject.VALIDATE) {
            long t0 = System.nanoTime();
            StructureGenerator.GenerationResult result = original.call();
            EarlyReject.validated(structureID(), verdict, result, System.nanoTime() - t0);
            return result;
        }
        EarlyReject.rejected(structureID());
        return failGenerate(verdict);
    }

}
