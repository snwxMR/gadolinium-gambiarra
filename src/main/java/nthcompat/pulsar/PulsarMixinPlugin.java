// SPDX-License-Identifier: MIT
package nthcompat.pulsar;

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
 * Plugin of mixins.nthcompat.pulsar.json (Pulsar fix, see PulsarCompat). Registered after the regular mods join the classpath
 * (PulsarLateMixinLoader). The mixin only applies with the option, the version and the fingerprints checked; the injection is counted in
 * the final class.
 */
public class PulsarMixinPlugin implements IMixinConfigPlugin {

    private static boolean audit;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatConfig.load();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        PulsarCompat.evaluate();
        if (PulsarCompat.scratchAllowed) {
            try {
                HandlerCallAudit.register();
                audit = true;
            } catch (Throwable t) {
                CompatStatus.LOG.warn("[gambiarra] Pulsar: injection check unavailable ({}); the fix does not act", t.toString());
            gambiarra.common.StartupReport.failed("pulsar.fixSmoothLightCrash", "injection check unavailable: " + t);
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
        return audit && PulsarCompat.SCRATCH_MIXIN.equals(simpleName(mixinClassName)) && PulsarCompat.scratchAllowed;
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
        if (PulsarCompat.SCRATCH_MIXIN.equals(simpleName(mixinClassName))) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", PulsarCompat.SCRATCH_INJECTIONS, PulsarCompat::record);
        }
    }

}
