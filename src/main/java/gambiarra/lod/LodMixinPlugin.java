// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import gambiarra.common.AddonVersion;
import gambiarra.common.HandlerCallAudit;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * Plugin of mixins.gambiarra.lod.json (entity LOD on vanilla classes: RenderManager and ModelRenderer). Client only. Vanilla classes change with OptiFine, so the lock here is the injection count in the final class (as in Gambiarra's other vanilla
 * fixes): RenderManagerLodMixin must leave 1 call, ModelRendererLodMixin 2 (render and renderWithRotation); with another count, the level does not act.
 */
public class LodMixinPlugin implements IMixinConfigPlugin {

    static final String MANAGER = "RenderManagerLodMixin";
    static final String PARTS = "ModelRendererLodMixin";
    static final int MANAGER_INJECTIONS = 1;
    static final int PARTS_INJECTIONS = 2;

    private static boolean audit;
    private static final Map<String, String> injections = new LinkedHashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatStatus.evaluate();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        Lod.partsWanted = CompatConfig.lodSubPixelParts;
        Lod.animationWanted = CompatConfig.lodAnimationBySize;
        Lod.impostorsWanted = CompatConfig.lodImpostors;
        if (Lod.partsWanted || Lod.animationWanted || Lod.impostorsWanted) {
            try {
                HandlerCallAudit.register();
                audit = true;
            } catch (Throwable t) {
                Lod.LOG.warn("[gambiarra] LOD: injection check unavailable ({}); the LOD does not act", t.toString());
            gambiarra.common.StartupReport.failed("lod", "injection check unavailable: " + t);
            }
        }
        Lod.LOG.info("[gambiarra] Entity LOD: level 1 (small parts) {}, level 2 (animation by size) {}, level 3 (impostors) {}",
                Lod.partsWanted ? "on" : "off", Lod.animationWanted ? "on" : "off",
                Lod.impostorsWanted ? "on (experimental)" : "off");
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
        if (!audit || CompatStatus.dedicatedServer()) {
            return false;
        }
        String name = simpleName(mixinClassName);
        if (MANAGER.equals(name)) {
            return Lod.partsWanted || Lod.animationWanted || Lod.impostorsWanted;
        }
        if (PARTS.equals(name)) {
            // Level 3 measures the model through the part hooks, even with level 1 off.
            return Lod.partsWanted || Lod.impostorsWanted;
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
        if (MANAGER.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "gambiarra", MANAGER_INJECTIONS, LodMixinPlugin::record);
        } else if (PARTS.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "gambiarra", PARTS_INJECTIONS, LodMixinPlugin::record);
        }
    }

    /** HandlerCallAudit: the injection count of an LOD mixin (from this plugin and LodModsMixinPlugin). */
    static synchronized void record(String target, String mixin, int found, int expected) {
        injections.put(mixin, found + "/" + expected + " in " + target);
        boolean ok = found == expected;
        if (MANAGER.equals(mixin)) {
            Lod.managerOk = ok;
        } else if (PARTS.equals(mixin)) {
            Lod.vanillaPartsOk = ok;
        } else if (LodModsMixinPlugin.LLIBRARY.equals(mixin)) {
            Lod.llibraryPartsOk = ok && LodModsMixinPlugin.llibraryAllowed;
        } else if (LodModsMixinPlugin.LYCANITES.equals(mixin)) {
            Lod.lycanitesOk = ok && LodModsMixinPlugin.lycanitesAllowed;
        }
        if (!ok) {
            Lod.LOG.warn("[gambiarra] LOD: {} has {} of {} injections in {}; this part of the LOD does not act", mixin, found, expected, target);
            gambiarra.common.StartupReport.failed("lod (" + mixin + ")", found + " of " + expected + " injections in " + target);
        }
    }

    static synchronized Map<String, String> injections() {
        return new LinkedHashMap<>(injections);
    }

}
