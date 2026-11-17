// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import nthshaders.common.AddonVersion;
import nthshaders.common.HandlerCallAudit;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * Plugin of mixins.nthgpu.json (GPU culling, see GpuCulling).
 *
 * The mixins only apply with the same version gate as the shader part (Status: known Nothirium, OptiFine and OptiRefine) and
 * with gpuCulling on. Each injection is counted in the final class (HandlerCallAudit, handlers nthgpu$&lt;Mixin&gt;$...): if
 * one is missing, GPU culling does not turn on and Nothirium carries on as before. A failure here does not touch the rest of
 * the addon.
 */
public class GpuMixinPlugin implements IMixinConfigPlugin {

    private static boolean allowed;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        Status.evaluateGate();
        if (!Status.gatePassed()) {
            Status.LOG.info("[gadolinium] GPU culling: off (the shader part did not pass the version gate)");
            return;
        }
        if (!NthConfig.gpuCulling) {
            Status.LOG.info("[gadolinium] GPU culling: disabled in the config (gpuCulling=false)");
            return;
        }
        try {
            HandlerCallAudit.register();
            allowed = true;
        } catch (Throwable t) {
            Status.LOG.warn("[gadolinium] GPU culling: off (the injection audit is not available: {})", t.toString());
            nthshaders.common.StartupReport.failed("gpuCulling", "injection audit unavailable: " + t);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (GpuInjections.FACE_MIXINS.contains(simpleName) && !NthConfig.gpuFaceCulling) {
            return false;
        }
        if (GpuInjections.ENTITY_EXPECTED.containsKey(simpleName)) {
            return allowed && NthConfig.gpuCullingEntities && EntityGraphCulling.renderLibKnown();
        }
        return allowed;
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
        Integer expected = GpuInjections.EXPECTED.get(simpleName);
        if (expected == null) {
            expected = GpuInjections.FACE_EXPECTED.get(simpleName);
        }
        if (expected == null) {
            expected = GpuInjections.ENTITY_EXPECTED.get(simpleName);
        }
        if (expected != null) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthgpu", expected, GpuInjections::record);
        }
    }

}
