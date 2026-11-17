// SPDX-License-Identifier: MIT
package nthrc;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import gambiarra.common.AddonVersion;
import gambiarra.common.HandlerCallAudit;

/**
 * Plugin of the worldgen module's mixin groups (mixins.nthrc.&lt;mod&gt;.json): the same for all groups.
 *
 * Applies on both sides: worldgen runs on the logical server, dedicated or integrated. Independent of nthshaders and nthcompat.
 *  - onLoad: evaluates config, versions and fingerprints (WorldgenStatus, once) and registers the audit (once);
 *  - shouldApplyMixin: only applies the mixins of allowed optimizations;
 *  - postApply: notes what to check; the count happens at the end of the class transformation (HandlerCallAudit), after MixinExtras' @WrapOperation/@WrapMethod.
 */
public class WorldgenMixinPlugin implements IMixinConfigPlugin {

    private static final String HANDLER_PREFIX = "nthrc";

    private static boolean auditTried;
    private static boolean auditRegistered;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        WorldgenStatus.evaluate();
        registerAudit();
    }

    private static synchronized void registerAudit() {
        if (auditTried) {
            return;
        }
        auditTried = true;
        boolean anyAllowed = false;
        for (Opt o : Opt.values()) {
            anyAllowed |= o.allowed;
        }
        if (anyAllowed) {
            try {
                HandlerCallAudit.register();
                auditRegistered = true;
            } catch (Throwable t) {
                WorldgenStatus.auditUnavailable(t);
            }
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Without audit, no optimization acts: it does not even apply.
        return auditRegistered && WorldgenStatus.mixinAllowed(simpleName(mixinClassName));
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
        String simpleName = simpleName(mixinClassName);
        // With @Local, MixinExtras calls the handler through a bridge method: the same injection may count twice.
        HandlerCallAudit.expect(targetClassName, mixinClassName, HANDLER_PREFIX, WorldgenStatus.expectedInjections(simpleName),
                WorldgenStatus::recordInjection);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

}
