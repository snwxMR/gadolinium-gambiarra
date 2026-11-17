// SPDX-License-Identifier: MIT
package nthcompat.oflights;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import gambiarra.common.AddonVersion;
import gambiarra.common.HandlerCallAudit;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * Plugin of mixins.nthcompat.oflights.json (OptiFine dynamic lights, see OfLightsGate). Each mixin only applies with its option and
 * the classes checked; the injections are counted in the final class. Without OptiFine, nothing applies.
 */
public class OfLightsMixinPlugin implements IMixinConfigPlugin {

    private static boolean audit;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatConfig.load();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        OfLightsGate.evaluate();
        if (OfLightsGate.anyAllowed()) {
            try {
                HandlerCallAudit.register();
                audit = true;
            } catch (Throwable t) {
                CompatStatus.LOG.warn("[gambiarra] OptiFine dynamic lights: injection check unavailable ({}); the optimizations do not act",
                        t.toString());
            gambiarra.common.StartupReport.failed("optifinedynamiclights", "injection check unavailable: " + t);
            }
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return audit && OfLightsGate.applies(simpleName(mixinClassName));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        int expected = OfLightsGate.expectedInjections(simpleName(mixinClassName));
        if (expected > 0) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", expected, OfLightsGate::record);
        }
    }

}
