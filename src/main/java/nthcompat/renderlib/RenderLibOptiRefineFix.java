// SPDX-License-Identifier: MIT
package nthcompat.renderlib;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import gambiarra.common.JarUtil;
import gambiarra.common.OptiRefineFingerprint;
import nthcompat.CompatConfig;

/**
 * RenderLib x OptiRefine compatibility fix.
 *
 * RenderLib 1.4.5 registers an ASM patch on RenderGlobal#renderEntities that, when it finds OptiFine, looks for pieces of OptiFine's
 * CLASSIC patch (Shaders.endEntities etc.). OptiRefine turns that patch off by design, the pieces do not exist, RenderLib throws an
 * exception and RenderGlobal does not load: the game does not start.
 *
 * Removing only that patch is neutral with OptiRefine: its @WrapMethod on renderEntities reimplements the whole method and never calls
 * the original, so the body RenderLib would modify never runs.
 *
 * Nothing in RenderLib's jar is changed: the patch is taken, at run time, out of the patch map of the already registered transformer. It
 * only acts if EVERYTHING is recognized (RenderLib's version, the bytecode of the patch registration and the internal parts); otherwise
 * it does not touch anything and logs that this version is incompatible.
 */
public final class RenderLibOptiRefineFix {

    static final String RENDERLIB_VERSION = "1.4.5";
    static final String RENDERLIB_CLASS_RESOURCE = "meldexun/renderlib/asm/RenderLibClassTransformer.class";
    private static final org.apache.logging.log4j.Logger LOG = nthcompat.CompatStatus.LOG;
    static final String TRANSFORMER_CLASS = "meldexun.renderlib.asm.RenderLibClassTransformer";
    static final String PATCH_MAP_FIELD = "classTransformers";
    static final String TARGET_CLASS = "net.minecraft.client.renderer.RenderGlobal";
    static final String TARGET_METHOD = "renderEntities";
    static final String TARGET_METHOD_SRG = "func_180446_a";
    static final String FOUNDATION_TRANSFORMERS = "top.outlands.foundation.TransformerDelegate";

    /** RenderLib 1.4.5 classes that register patches in the same map. */
    private static final String[] PATCH_REGISTRARS = {
            "meldexun/renderlib/asm/RenderLibClassTransformer",
            "meldexun/renderlib/asm/pokecube/PokecubePatches",
            "meldexun/renderlib/asm/animaniacatsdogs/AnimaniaCatsDogsPatches",
            "meldexun/renderlib/asm/cbmultipart/CBMultipartPatches",
    };

    private static String result = "not run";
    private static String before;
    private static String after;

    private RenderLibOptiRefineFix() {
    }

    public static synchronized void apply() {
        try {
            if (!CompatConfig.fixRenderLibOptiRefine) {
                result = "disabled in the config (fixRenderLibOptiRefine=false)";
                LOG.info("[gambiarra] RenderLib x OptiRefine fix: {}", result);
                return;
            }
            if (JarUtil.jarOf(OptiRefineFingerprint.OPTIREFINE_CLASS) == null) {
                result = "OptiRefine absent: nothing to do";
                return;
            }
            File renderLibJar = JarUtil.jarOf(RENDERLIB_CLASS_RESOURCE);
            if (renderLibJar == null) {
                result = "RenderLib absent: nothing to do";
                return;
            }

            String version = JarUtil.modVersion(RENDERLIB_CLASS_RESOURCE, "renderlib");
            if (!RENDERLIB_VERSION.equals(version)) {
                incompatible("version " + version + " (expected " + RENDERLIB_VERSION + ")", version);
                return;
            }
            String bytecodeProblem = checkRegistrars(renderLibJar);
            if (bytecodeProblem != null) {
                incompatible(bytecodeProblem, version);
                return;
            }

            Object transformer = findTransformer();
            if (transformer == null) {
                incompatible("transformer " + TRANSFORMER_CLASS + " not found in "
                        + FOUNDATION_TRANSFORMERS + ".getTransformers()", version);
                return;
            }
            Map<String, List<?>> patches = patchMap(transformer);
            if (patches == null) {
                incompatible("field " + PATCH_MAP_FIELD + " (Map) not found in " + transformer.getClass().getName(), version);
                return;
            }
            List<?> targetPatches = patches.get(TARGET_CLASS);
            if (targetPatches == null || targetPatches.size() != 1) {
                incompatible("expected 1 patch in " + TARGET_CLASS + ", found "
                        + (targetPatches == null ? 0 : targetPatches.size()), version);
                return;
            }

            before = describe(patches);
            LOG.info("[gambiarra] RenderLib patches BEFORE the fix: {}", before);
            patches.remove(TARGET_CLASS);
            after = describe(patches);
            LOG.info("[gambiarra] RenderLib patches AFTER the fix: {}", after);
            result = "patch " + TARGET_CLASS + "#" + TARGET_METHOD + " removed (RenderLib " + version + ")";
            LOG.info("[gambiarra] RenderLib x OptiRefine fix applied: {}. RenderLib's other patches stay active.", result);
        } catch (Throwable t) {
            result = "failed: " + t;
            LOG.error("[gambiarra] RenderLib x OptiRefine fix failed; nothing was changed. This RenderLib version is "
                    + "incompatible with OptiRefine until the addon is updated.", t);
            gambiarra.common.StartupReport.failed("fixRenderLibOptiRefine", result);
        }
    }

    private static void incompatible(String why, String version) {
        result = "not applied: " + why;
        LOG.error("[gambiarra] RenderLib {} not recognized ({}). Not removing the patch of {}#{}. This RenderLib version is "
                        + "incompatible with OptiRefine until the addon is updated: the game is expected to fail loading {}.",
                version, why, TARGET_CLASS, TARGET_METHOD, TARGET_CLASS);
        gambiarra.common.StartupReport.lock("fixRenderLibOptiRefine", why);
    }

    /**
     * Checks, in RenderLib's bytecode, that RenderGlobal appears as a patch target ONLY once, and together with
     * renderEntities/func_180446_a. So the only RenderGlobal entry in the map is, for sure, the renderEntities patch. Returns null if
     * everything is as expected, or the description of the problem.
     */
    static String checkRegistrars(File renderLibJar) throws java.io.IOException {
        int targetRefs = 0;
        boolean methodFound = false;
        for (String name : PATCH_REGISTRARS) {
            byte[] bytes = JarUtil.readEntry(renderLibJar, name + ".class");
            if (bytes == null) {
                return "class " + name + " missing from RenderLib's jar";
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            for (MethodNode m : node.methods) {
                boolean hasTarget = false;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode && TARGET_CLASS.equals(((LdcInsnNode) insn).cst)) {
                        targetRefs++;
                        hasTarget = true;
                    }
                }
                if (hasTarget) {
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof LdcInsnNode && TARGET_METHOD_SRG.equals(((LdcInsnNode) insn).cst)) {
                            methodFound = true;
                        }
                    }
                }
            }
        }
        if (targetRefs != 1 || !methodFound) {
            return "unexpected patch registration (" + TARGET_CLASS + " cited " + targetRefs + "x, "
                    + TARGET_METHOD_SRG + (methodFound ? "" : " did not") + " found together)";
        }
        return null;
    }

    /** Registered instance of RenderLib's transformer, via Foundation's public API. */
    private static Object findTransformer() throws ReflectiveOperationException {
        Class<?> delegate = Class.forName(FOUNDATION_TRANSFORMERS);
        Method getTransformers = delegate.getMethod("getTransformers");
        for (Object t : (List<?>) getTransformers.invoke(null)) {
            if (t != null && TRANSFORMER_CLASS.equals(t.getClass().getName())) {
                return t;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<?>> patchMap(Object transformer) throws IllegalAccessException {
        for (Class<?> c = transformer.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (PATCH_MAP_FIELD.equals(f.getName()) && Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Map<String, List<?>>) f.get(transformer);
                }
            }
        }
        return null;
    }

    private static String describe(Map<String, List<?>> patches) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map.Entry<String, List<?>> e : patches.entrySet()) {
            counts.put(e.getKey(), e.getValue() != null ? e.getValue().size() : 0);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return parts.toString();
    }

    public static synchronized Map<String, Object> describeResult() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("result", result);
        m.put("before", before);
        m.put("after", after);
        return m;
    }

}
