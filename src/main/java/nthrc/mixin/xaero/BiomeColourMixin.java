// SPDX-License-Identifier: MIT
package nthrc.mixin.xaero;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import gambiarra.common.ValidationLog;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import nthrc.Hook;
import nthrc.WorldgenConfig;

/**
 * biomeColourMethodHandle: BiomeColorCalculator.resolve asks for the biome colour through Method.invoke (via XaeroLib), for each map pixel, with a new Object[] and the Integer back.
 * Here the same call through a MethodHandle, found once for the Method Xaero stored. Any error falls to the original path.
 */
@Mixin(targets = "xaero.map.biome.BiomeColorCalculator", remap = false)
public abstract class BiomeColourMixin {

    @Unique
    private static final boolean nthrc$VALIDATE = WorldgenConfig.validate;

    @Shadow
    private Method colorResolverGetColorMethod;

    @Unique
    private MethodHandle nthrc$handle;
    @Unique
    private Method nthrc$handleOf;

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void nthrc$BiomeColourMixin$resolve(Object resolver, Biome biome, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        Method method = colorResolverGetColorMethod;
        if (!Hook.XAERO_BIOME_COLOUR.ok || method == null) {
            return;
        }
        try {
            MethodHandle h = nthrc$handle;
            if (h == null || nthrc$handleOf != method) {
                method.setAccessible(true);
                h = MethodHandles.lookup().unreflect(method).asType(MethodType.methodType(int.class, Object.class, Biome.class, BlockPos.class));
                nthrc$handle = h;
                nthrc$handleOf = method;
            }
            int colour = (int) h.invokeExact(resolver, biome, pos);
            if (nthrc$VALIDATE) {
                int expected = (Integer) method.invoke(resolver, biome, pos);
                ValidationLog.check("xaeroworldmap.biomeColourMethodHandle", colour == expected, "colour " + colour + " and reflection " + expected);
            }
            cir.setReturnValue(colour);
        } catch (Throwable t) {
            // The original path runs.
        }
    }

}
