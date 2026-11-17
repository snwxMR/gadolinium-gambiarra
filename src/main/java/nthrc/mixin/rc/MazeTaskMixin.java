// SPDX-License-Identifier: MIT
package nthrc.mixin.rc;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import nthrc.rc.MazeSearchGuard;

/**
 * Proposal A, part 1: wraps the search Recurrent Complex sends to the maze thread, to mark that thread (see MazeSearchGuard). The original task runs the same.
 *
 * The submission is in getPlacedRooms up to 2.0.1.2 and in solveRooms from 2.0.1.3 on (the same submission with the same mazeTimeout, in a separate method): one
 * of the two has the call, so the injection is always one.
 */
@Mixin(targets = "ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator", remap = false, priority = 1500)
public abstract class MazeTaskMixin {

    @Shadow
    public String mazeID;

    @WrapOperation(method = { "getPlacedRooms", "solveRooms" }, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;"))
    private Future<?> nthrc$MazeTaskMixin$submit(ExecutorService executor, Callable<?> task, Operation<Future<?>> original) {
        return original.call(executor, MazeSearchGuard.wrap(task, mazeID));
    }

}
