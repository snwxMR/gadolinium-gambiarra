// SPDX-License-Identifier: MIT
package nthrc.mixin.rc;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthrc.rc.MazeSearchGuard;

/**
 * Proposal A, part 2: before each candidate piece tested by IvToolkit's search (the test is expensive: that is where the search spends ~94% of its time), checks
 * whether Recurrent Complex has already given up on it. Only ends the search on the thread marked by part 1 and already interrupted.
 */
@Mixin(targets = "ivorius.ivtoolkit.maze.components.MazeComponentConnector", remap = false, priority = 1500)
public abstract class MazeSearchInterruptMixin {

    @WrapOperation(method = "connectMulti", at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"))
    private static boolean nthrc$MazeSearchInterruptMixin$test(Predicate<Object> predicate, Object candidate,
            Operation<Boolean> original) {
        MazeSearchGuard.check();
        return original.call(predicate, candidate);
    }

}
