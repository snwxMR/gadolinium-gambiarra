// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

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
 * Plugin of mixins.nthcompat.iceandfire.json (Ice and Fire fixes, see IafCompat). Registered after the regular mods join the
 * classpath (IafLateMixinLoader). Each mixin only applies with its option, the versions and the fingerprints checked; the injections
 * are counted in the final class.
 */
public class IafMixinPlugin implements IMixinConfigPlugin {

    private static boolean audit;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatConfig.load();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        IafCompat.evaluate();
        if (IafCompat.poseCacheAllowed || IafCompat.lookupAllowed || IafCompat.distantAllowed) {
            try {
                HandlerCallAudit.register();
                audit = true;
            } catch (Throwable t) {
                CompatStatus.LOG.warn("[gambiarra] Ice and Fire: injection check unavailable ({}); the fixes do not act", t.toString());
            gambiarra.common.StartupReport.failed("iceandfire", "injection check unavailable: " + t);
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
        String name = simpleName(mixinClassName);
        if (!audit) {
            return false;
        }
        if (IafCompat.POSE_CACHE_MIXIN.equals(name) || "ModelAnimatorAccessor".equals(name)) {
            return IafCompat.poseCacheAllowed;
        }
        if (IafCompat.DISTANT_MIXIN.equals(name)) {
            return IafCompat.distantAllowed;
        }
        if (IafCompat.ICE_LOOKUP_MIXIN.equals(name) || IafCompat.FIRE_LOOKUP_MIXIN.equals(name)
                || IafCompat.SERPENT_LOOKUP_MIXIN.equals(name) || "AdvancedModelRendererPairsMixin".equals(name)
                || "TabulaModelKeyMixin".equals(name)) {
            return IafCompat.lookupAllowed;
        }
        return false;
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
        String name = simpleName(mixinClassName);
        if (IafCompat.POSE_CACHE_MIXIN.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", IafCompat.POSE_CACHE_INJECTIONS, IafCompat::record);
        } else if (IafCompat.DISTANT_MIXIN.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", IafCompat.DISTANT_INJECTIONS, IafCompat::record);
        } else if (IafCompat.ICE_LOOKUP_MIXIN.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", IafCompat.ICE_LOOKUP_INJECTIONS, IafCompat::record);
        } else if (IafCompat.FIRE_LOOKUP_MIXIN.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", IafCompat.FIRE_LOOKUP_INJECTIONS, IafCompat::record);
        } else if (IafCompat.SERPENT_LOOKUP_MIXIN.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", IafCompat.SERPENT_LOOKUP_INJECTIONS, IafCompat::record);
        }
    }

}
