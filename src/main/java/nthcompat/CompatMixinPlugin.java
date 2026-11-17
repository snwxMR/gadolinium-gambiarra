// SPDX-License-Identifier: MIT
package nthcompat;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import gambiarra.common.AddonVersion;
import gambiarra.common.HandlerCallAudit;
import nthcompat.optirefine.AccPlaceholderFix;
import nthcompat.optirefine.ResourceProbePriorityFix;
import nthcompat.optirefine.SmartAnimationsGate;
import nthcompat.renderlib.RenderLibOptiRefineFix;

/**
 * Plugin of mixins.nthcompat.json (Gambiarra): the OptiRefine and OptiFine fixes that do not depend on Nothirium (float view, acc$
 * placeholders, Smart Animations, SmartLeaves leaves). Those that depend on Nothirium live in Gadolinium (nthshaders.compat).
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {

    static final String FLOAT_VIEW = "nthcompat.mixin.BufferBuilderFloatViewMixin";
    static final String SMART_LEAVES = "nthcompat.mixin.SmartLeavesDoubleFaceMixin";
    static final String DYNAMIC_TEXTURE = "nthcompat.mixin.DynamicTextureDataMixin";
    /**
     * Smart Animations (see optirefine.SmartAnimationsFix): SmartAnimationsGate holds which mixins apply in each marking form. These two
     * have a version lock in postApply.
     */
    static final String SA_UPDATE = "nthcompat.mixin.RenderGlobalSmartAnimationsMixin";
    static final String SA_BRIGHTNESS_QUADS = "nthcompat.mixin.BlockModelRendererSpriteMarkingMixin";

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatStatus.evaluate();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        // Runs here because RenderLib's transformer is already registered and RenderGlobal has not been loaded yet.
        RenderLibOptiRefineFix.apply();
        // Before any OptiRefine target (EntityRenderer etc.) is loaded.
        if (CompatStatus.accPlaceholderFixAllowed()) {
            AccPlaceholderFix.register();
        }
        // Before DefaultResourcePack is loaded (resource packs load long after the mixin configs).
        if (CompatStatus.vintageFixFixAllowed()) {
            ResourceProbePriorityFix.register();
        }
        // Smart Animations: each injection is checked in the final class; without the check, the package does not apply.
        if (SmartAnimationsGate.allowed()) {
            try {
                HandlerCallAudit.register();
            } catch (Throwable t) {
                SmartAnimationsGate.auditUnavailable(t.toString());
                CompatStatus.LOG.warn("[gambiarra] Smart Animations: {}", SmartAnimationsGate.reason());
                gambiarra.common.StartupReport.failed("fixSmartAnimations", SmartAnimationsGate.reason());
            }
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FLOAT_VIEW.equals(mixinClassName)) {
            return CompatStatus.floatBufferFixAllowed();
        }
        if (SMART_LEAVES.equals(mixinClassName)) {
            return CompatStatus.smartLeavesFixAllowed();
        }
        if (DYNAMIC_TEXTURE.equals(mixinClassName)) {
            return CompatStatus.dynamicTextureFixAllowed();
        }
        // Smart Animations: the usual 3 and those of the chosen marking form (SmartAnimationsGate).
        return SmartAnimationsGate.applies(simpleName(mixinClassName));
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

    /**
     * Does any method of the class (only those with methodNamePart in the name, if not null) call a method whose name ends in nameSuffix,
     * of owner owner (null = any owner)? The suffix also catches OptiRefine's "stubs" (BufferBuilder_putSprite), which only become the
     * real call after the plugins' postApply.
     */
    private static boolean calls(ClassNode node, String methodNamePart, String owner, String nameSuffix) {
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (methodNamePart != null && !m.name.contains(methodNamePart)) {
                continue;
            }
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) insn;
                    if (call.name.endsWith(nameSuffix) && (owner == null || call.owner.equals(owner))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        CompatStatus.LOG.info("[gambiarra] Mixin applied: {} -> {}", mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1),
                targetClassName);
        int expected = SmartAnimationsGate.expectedInjections(simpleName(mixinClassName));
        if (expected > 0) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "nthcompat", expected, SmartAnimationsGate::recordInjection);
        }
        // Smart Animations version lock. Here (and not in preApply, which Mixin calls before applying any mixin of the class) the methods of
        // OptiRefine's mixins are already in the class. If one already does what ours would, ours does not do it again: the handlers check
        // these flags at call time, and the class only loads after this.
        if (SA_UPDATE.equals(mixinClassName) && calls(targetClass, null, "net/optifine/SmartAnimations", "update")) {
            SmartAnimationsGate.setOptiRefineCallsUpdate();
            CompatStatus.LOG.info("[gambiarra] Smart Animations: RenderGlobal already calls SmartAnimations.update() (newer OptiRefine?); "
                    + "the addon does not call it.");
        }
        if (SA_BRIGHTNESS_QUADS.equals(mixinClassName)
                && (calls(targetClass, "BrightnessColorQuads", null, "putSprite") || calls(targetClass, "func_178264_a", null, "putSprite")
                        || calls(targetClass, "BrightnessColorQuads", "net/optifine/SmartAnimations", "spriteRendered")
                        || calls(targetClass, "func_178264_a", "net/optifine/SmartAnimations", "spriteRendered"))) {
            SmartAnimationsGate.setOptiRefineMarksBrightnessQuads();
            CompatStatus.LOG.info("[gambiarra] Smart Animations: renderModelBrightnessColorQuads already marks the textures (newer "
                    + "OptiRefine?); the addon does not mark them.");
        }
    }

}
