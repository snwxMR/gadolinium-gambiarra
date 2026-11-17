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
 * Plugin of the worldgen module's "vanilla" group (mixins.nthrc.vanilla.json, Gambiarra 0.4.0): mixins in the game's own classes that load early (EntityItem and Entity in Bootstrap, BlockStateContainer with the blocks), before
 * WorldgenLateMixinLoader registers the other groups. That is why this group goes in the jar's manifest (MixinConfigs), like mixins.nthcompat.json.
 *
 * Unlike WorldgenMixinPlugin, it does not evaluate the target mods (their jars are not on the classpath yet):
 *  - shouldApplyMixin: only the config (the module and the option on);
 *  - preApply: the lock on the game's classes (WorldgenStatus.checkVanilla, the methods' fingerprint on the ClassNode);
 *  - postApply: the same injection audit. The optimization's final decision (Opt.allowed, in the late group's WorldgenStatus.evaluate) comes later; each handler checks Hook.active().
 */
public class VanillaMixinPlugin implements IMixinConfigPlugin {

    private static final String HANDLER_PREFIX = "nthrc";

    private static boolean auditTried;
    private static boolean auditRegistered;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        registerAudit();
    }

    private static synchronized void registerAudit() {
        if (auditTried) {
            return;
        }
        auditTried = true;
        try {
            HandlerCallAudit.register();
            auditRegistered = true;
        } catch (Throwable t) {
            WorldgenStatus.auditUnavailable(t);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return auditRegistered && targetPresent(targetClassName) && WorldgenStatus.earlyMixinAllowed(simpleName(mixinClassName));
    }

    /** Target outside the game (the RenderLib and RLTweaker class): without the mod, the mixin does not even apply (and Mixin does not warn). */
    private static boolean targetPresent(String targetClassName) {
        return targetClassName.startsWith("net.minecraft.")
                || gambiarra.common.JarUtil.jarOf(targetClassName.replace('.', '/') + ".class") != null;
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
        WorldgenStatus.checkVanilla(simpleName(mixinClassName), targetClassName, targetClass);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String simpleName = simpleName(mixinClassName);
        HandlerCallAudit.expect(targetClassName, mixinClassName, HANDLER_PREFIX, WorldgenStatus.expectedInjections(simpleName),
                WorldgenStatus::recordInjection);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

}
