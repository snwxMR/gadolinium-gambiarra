// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import nthshaders.common.AddonVersion;
import nthshaders.common.HandlerCallAudit;

/**
 * Plugin of mixins.nthshaders.compat.json (ShadersCompat): each mixin only applies with its own decision. The build order
 * and the Smart Animations race have their injections counted in the final class. Before any target loads, it also runs
 * the dormant copy of the RenderLib fix (only acts without Gambiarra).
 */
public class ShadersCompatPlugin implements IMixinConfigPlugin {

    static final String PACKAGE = "nthshaders.mixin.compat.";
    static final String UPDATE_CLOUDS = PACKAGE + "RenderGlobalUpdateCloudsMixin";
    static final String ENTITIES = PACKAGE + "RenderGlobalEntitiesMixin";
    static final String COMPILE_BUFFERS = PACKAGE + "NothiriumCompileBuffersMixin";
    static final String DISPATCHER_DISPOSE = PACKAGE + "NothiriumDispatcherDisposeMixin";
    static final String COMPILE_THREADS = PACKAGE + "NothiriumCompileThreadsMixin";
    static final String COMPILE_ORDER = PACKAGE + CompilePriority.MIXIN;
    static final String SA_RACE = PACKAGE + "SmartAnimationsActiveMixin";

    private static boolean audited;
    private static final Map<String, String> audit = new LinkedHashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        ShadersCompat.evaluate();
        if (ShadersCompat.dedicatedServer()) {
            return;
        }
        // Before RenderGlobal loads: without Gambiarra, the copy here removes RenderLib's patch.
        if (ShadersCompat.optiRefinePresent()) {
            RenderLibOptiRefineFix.apply();
        }
        if (CompilePriority.allowed() || ShadersCompat.smartAnimationsRaceAllowed()) {
            try {
                HandlerCallAudit.register();
                audited = true;
            } catch (Throwable t) {
                ShadersCompat.LOG.warn("[gadolinium] Injection audit unavailable ({}): build order and Smart Animations race stay out", t.toString());
            nthshaders.common.StartupReport.failed("chunkCompileOrder", "injection audit unavailable");
            }
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ShadersCompat.dedicatedServer()) {
            return false;
        }
        switch (mixinClassName) {
            case UPDATE_CLOUDS:
                return ShadersCompat.updateCloudsAllowed();
            case ENTITIES:
                return ShadersCompat.entityFixEnabled();
            case COMPILE_BUFFERS:
            case DISPATCHER_DISPOSE:
                return ShadersCompat.compileBufferMixinsAllowed();
            case COMPILE_THREADS:
                return ShadersCompat.compileThreadsAllowed();
            case COMPILE_ORDER:
                return audited && CompilePriority.allowed();
            case SA_RACE:
                return audited && ShadersCompat.smartAnimationsRaceAllowed();
            default:
                return false;
        }
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
        ShadersCompat.LOG.info("[gadolinium] Mixin applied: {} -> {}", mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1),
                targetClassName);
        // The handlers of these mixins keep the historical nthcompat$ prefix.
        if (COMPILE_ORDER.equals(mixinClassName)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", CompilePriority.INJECTIONS, CompilePriority::record);
        } else if (SA_RACE.equals(mixinClassName)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", 1, ShadersCompatPlugin::record);
        }
    }

    private static synchronized void record(String target, String mixin, int found, int expected) {
        audit.put(mixin, found + "/" + expected + " in " + target);
        if (found != expected) {
            ShadersCompat.LOG.warn("[gadolinium] {} has {} of {} injections in {}", mixin, found, expected, target);
            nthshaders.common.StartupReport.failed(ShadersCompat.optionOf(mixin), mixin + " has " + found + " of " + expected + " injections in " + target);
        }
    }

    static synchronized Map<String, String> auditResults() {
        return new LinkedHashMap<>(audit);
    }

}
