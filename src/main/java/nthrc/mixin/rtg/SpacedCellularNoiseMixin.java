// SPDX-License-Identifier: MIT
package nthrc.mixin.rtg;

import java.awt.geom.Point2D;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthrc.Hook;
import nthrc.rtg.VoronoiCells;
import rtg.api.util.noise.VoronoiResult;

/**
 * Optimization V (see VoronoiCells): each of the 9 cell evaluations in eval2D only runs if the cell can change the result.
 */
@Mixin(targets = "rtg.api.util.noise.SpacedCellularNoise", remap = false, priority = 1500)
public abstract class SpacedCellularNoiseMixin {

    @WrapOperation(method = "eval2D", at = @At(value = "INVOKE",
            target = "Lrtg/api/util/noise/VoronoiResult;evaluate([Ljava/awt/geom/Point2D$Double;DD)V"))
    private void nthrc$SpacedCellularNoiseMixin$cell(VoronoiResult result, Point2D.Double[] points, double x, double z,
            Operation<Void> original) {
        if (Hook.RTG.ok && VoronoiCells.useless(points, x, z, result.getNextDistance())) {
            if (VoronoiCells.VALIDATE) {
                double shortest = result.getShortestDistance();
                double next = result.getNextDistance();
                original.call(result, points, x, z);
                VoronoiCells.validated(shortest != result.getShortestDistance() || next != result.getNextDistance());
                return;
            }
            VoronoiCells.skipped();
            return;
        }
        original.call(result, points, x, z);
    }

}
