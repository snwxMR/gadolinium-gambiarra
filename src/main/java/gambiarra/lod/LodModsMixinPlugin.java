// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import gambiarra.common.AddonVersion;
import gambiarra.common.HandlerCallAudit;
import gambiarra.common.JarUtil;
import nthcompat.CompatConfig;
import nthcompat.CompatStatus;

/**
 * Plugin of mixins.gambiarra.lod.mods.json (LOD on mod models: LLibrary's Tabula and Lycanites' .obj), registered after the regular mods join the classpath (LodLateMixinLoader). Each one only applies with the mod's version and the classes it touches
 * byte for byte equal to the checked ones (SHA-256), and only acts with the injections counted in the final class. Level 2 on Tabula models stays in mixins.nthcompat.iceandfire.json (TabulaModelDistantAnimationMixin).
 */
public class LodModsMixinPlugin implements IMixinConfigPlugin {

    static final String LLIBRARY = "AdvancedModelRendererLodMixin";
    static final String LYCANITES = "ModelCreatureObjLodMixin";
    static final int LLIBRARY_INJECTIONS = 1;
    static final int LYCANITES_INJECTIONS = 2;

    static final String LLIB_MARKER = "net/ilexiconn/llibrary/LLibrary.class";
    static final String LYC_MARKER = "com/lycanitesmobs/LycanitesMobs.class";
    static final String LLIB_RENDERER = "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer.class";
    static final String LLIB_RENDERER_SHA = "4ab2e558f9b07eeef042e0a793ea601286bf1e3907e9ecb70c07b70741bb9da8";
    /** {class, SHA-256} of Lycanites Mobs 1.12.2-2.0.8.10. */
    static final String[][] LYC_CLASSES = {
            { "com/lycanitesmobs/client/model/ModelCreatureObj.class", "bfa7479afa24a1a5aaefc8b38663ce4aa54245409b444e69dc64f1ce43d1285f" },
            { "com/lycanitesmobs/client/model/ModelObjPart.class", "c9ffb5b9df4fd2b1e965884848cda9d116d8e13a24cee3ae78ef428278f39c3d" },
            { "com/lycanitesmobs/client/obj/TessellatorModel.class", "867521f1052053ae7c44d82d46b2d45b0aad37e30bd4a74bc80ae405bf7a391e" },
    };

    static boolean llibraryAllowed;
    static boolean lycanitesAllowed;
    private static boolean audit;

    @Override
    public void onLoad(String mixinPackage) {
        AddonVersion.logOnce();
        CompatStatus.evaluate();
        if (CompatStatus.dedicatedServer()) {
            return;
        }
        // Level 3 measures the model through the same part hooks (Lod.measureModel), even with level 1 off.
        boolean parts = CompatConfig.lodSubPixelParts || CompatConfig.lodImpostors;
        boolean anim = CompatConfig.lodAnimationBySize;
        if (!parts && !anim) {
            return;
        }
        try {
            File llib = JarUtil.jarOf(LLIB_MARKER);
            if (parts && llib != null) {
                String v = JarUtil.modVersion(LLIB_MARKER, "llibrary");
                byte[] bytes = JarUtil.readEntry(llib, LLIB_RENDERER);
                llibraryAllowed = "1.7.20-1.12.2".equals(v) && bytes != null && LLIB_RENDERER_SHA.equals(JarUtil.sha256Hex(bytes));
                Lod.LOG.info("[gambiarra] LOD: Tabula parts (LLibrary {}): {}", v, llibraryAllowed ? "checked"
                        : "OFF (checked: LLibrary 1.7.20-1.12.2 with the AdvancedModelRenderer of known SHA-256)");
                if (!llibraryAllowed) {
                    gambiarra.common.StartupReport.lock("lod.subPixelParts (LLibrary)", "LLibrary " + v + " (checked: 1.7.20-1.12.2 with the known AdvancedModelRenderer)");
                }
            }
            File lyc = JarUtil.jarOf(LYC_MARKER);
            if (lyc != null) {
                String v = JarUtil.modVersion(LYC_MARKER, "lycanitesmobs");
                String bad = null;
                for (String[] c : LYC_CLASSES) {
                    byte[] bytes = JarUtil.readEntry(lyc, c[0]);
                    if (bytes == null || !c[1].equals(JarUtil.sha256Hex(bytes))) {
                        bad = c[0];
                        break;
                    }
                }
                lycanitesAllowed = "1.12.2-2.0.8.10".equals(v) && bad == null;
                Lod.LOG.info("[gambiarra] LOD: Lycanites .obj models ({}): {}", v, lycanitesAllowed ? "checked"
                        : "OFF (checked: 1.12.2-2.0.8.10" + (bad != null ? "; class differs: " + bad : "") + ")");
                if (!lycanitesAllowed) {
                    gambiarra.common.StartupReport.lock("lod (Lycanites)", "Lycanites " + v + (bad != null ? ", class differs: " + bad : " (checked: 1.12.2-2.0.8.10)"));
                }
            }
        } catch (Throwable t) {
            llibraryAllowed = false;
            lycanitesAllowed = false;
            Lod.LOG.warn("[gambiarra] LOD: failed checking the mods ({}); the LOD on mod models does not act", t.toString());
            gambiarra.common.StartupReport.failed("lod (mod models)", t.toString());
        }
        if (llibraryAllowed || lycanitesAllowed) {
            try {
                HandlerCallAudit.register();
                audit = true;
            } catch (Throwable t) {
                Lod.LOG.warn("[gambiarra] LOD: injection check unavailable ({}); the LOD on mod models does not act", t.toString());
                gambiarra.common.StartupReport.failed("lod (mod models)", "injection check unavailable: " + t);
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
        if (!audit) {
            return false;
        }
        String name = simpleName(mixinClassName);
        if (LLIBRARY.equals(name)) {
            return llibraryAllowed;
        }
        if (LYCANITES.equals(name)) {
            return lycanitesAllowed;
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
        if (LLIBRARY.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "gambiarra", LLIBRARY_INJECTIONS, LodMixinPlugin::record);
        } else if (LYCANITES.equals(name)) {
            HandlerCallAudit.expect(targetClassName, mixinClassName, "gambiarra", LYCANITES_INJECTIONS, LodMixinPlugin::record);
        }
    }

}
