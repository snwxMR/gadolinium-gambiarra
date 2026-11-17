// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import nthshaders.common.AddonVersion;

/**
 * Plugin of mixins.nthshaders.json.
 *
 * - onLoad: evaluates the "gate" (config + versions of the three mods). If it fails, shouldApplyMixin refuses ALL the
 *   addon's mixins. Also registers the injection audit (InjectionAudit).
 * - postApply: only notes what to check. The count of handler calls (every addon handler is named
 *   {@code nthshaders$<MixinName>$<something>}) is done by InjectionAudit at the end of the class transformation, after
 *   MixinExtras' @WrapMethod. If an injection is missing, the addon turns itself off at run time (Status).
 */
public class NthMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        Status.evaluateGate();
        if (Status.gatePassed() && !InjectionAudit.register()) {
            Status.auditUnavailable();
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return Status.gatePassed();
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
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        Status.injectionPending(simpleName, targetClassName);
        InjectionAudit.expect(targetClassName, mixinClassName, Injections.expected(simpleName));
    }

}
