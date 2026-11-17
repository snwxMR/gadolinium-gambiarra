// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import gambiarra.common.MixinExtensionRegistry;
import nthcompat.CompatStatus;

/**
 * TEMPORARY, until OptiRefine fixes it (option fixOptiRefineAccPlaceholders; issue text in docs/issue-optirefine-zoom.md).
 *
 * OptiRefine declares "placeholder" fields (and methods) in its mixins, annotated with @AccessTransformer(name = "<real name>"), to
 * change the access of the real member. Its CursedMixinExtensions.postApply DELETES the placeholder from the target, but does not
 * rewrite the references to it. In OptiRefine 78ff8ce, 8 are left (5 acc$ fields in EntityRenderer): NoSuchFieldError when releasing
 * zoom (mouseFilterX/YAxis) and when in water or lava with custom colours (fogColorRed/Green/Blue).
 *
 * This Mixin extension runs in the class's postApply, after all mixins and the postApply of all plugins (OptiRefine's included). It only
 * acts:
 *  - on classes where OptiRefine's mixins were applied;
 *  - on references to placeholders declared in the @AccessTransformer of those mixins;
 *  - when the placeholder no longer exists in the class (the reference is dangling);
 *  - when the real member exists in the class with the same type (and the same static).
 * Otherwise it does not replace and warns in the log. Each replacement goes to the log and to the diagnostics.
 * If OptiRefine fixes it, no dangling reference is left and this does nothing.
 */
public final class AccPlaceholderFix implements IExtension {

    static final String AT_DESC = "Lmods/Hileb/optirefine/library/cursedmixinextensions/annotations/AccessTransformer;";
    static final String OPTIREFINE_PACKAGE = "mods.Hileb.optirefine.";

    private static final AccPlaceholderFix INSTANCE = new AccPlaceholderFix();
    private static boolean registered;

    /** OptiRefine mixin -> placeholders declared in it (read once). */
    private static final Map<String, List<Placeholder>> byMixin = new ConcurrentHashMap<>();
    /** "Class: placeholder -> real name (n references)", in the order they happened. */
    private static final List<String> rewrites = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> refused = Collections.synchronizedList(new ArrayList<>());

    private AccPlaceholderFix() {
    }

    /** Called in the nthcompat plugin's onLoad. false = could not register (Mixin changed). */
    public static synchronized boolean register() {
        if (registered) {
            return true;
        }
        try {
            MixinExtensionRegistry.add(INSTANCE);
            registered = true;
            return true;
        } catch (Throwable t) {
            CompatStatus.LOG.warn("[gambiarra] Could not register the OptiRefine acc$ placeholder fix with Mixin", t);
            gambiarra.common.StartupReport.failed("fixOptiRefineAccPlaceholders", "could not register with Mixin: " + t);
            return false;
        }
    }

    public static boolean isRegistered() {
        return registered;
    }

    public static List<String> rewrites() {
        synchronized (rewrites) {
            return new ArrayList<>(rewrites);
        }
    }

    public static List<String> refused() {
        synchronized (refused) {
            return new ArrayList<>(refused);
        }
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
        try {
            fix(context.getClassNode(), appliedMixins(context.getClassInfo()));
        } catch (Throwable t) {
            // Never brings down the class transformation: without the replacement, it stays as OptiRefine left it.
            CompatStatus.LOG.warn("[gambiarra] acc$ placeholders: failure in {}; class left as it was", context.getClassNode().name, t);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
    }

    /**
     * Mixins applied to this class. Mixin stores "applied" in the mixin's OWN ClassInfo (MixinInfo.postApply -> info.addAppliedMixin), not
     * the target's; the target's ClassInfo only lists the mixins that target it (getMixins). Applied = it targeted this class and the
     * mixin's ClassInfo already registered it (the postApply of all mixins has already run here).
     */
    static Set<IMixinInfo> appliedMixins(ClassInfo target) {
        Set<IMixinInfo> applied = new LinkedHashSet<>();
        for (Object o : (Collection<?>) target.getMixins()) {
            IMixinInfo mixin = (IMixinInfo) o;
            ClassInfo own = ClassInfo.forName(mixin.getClassRef());
            if (own != null && own.getAppliedMixins().contains(mixin)) {
                applied.add(mixin);
            }
        }
        return applied;
    }

    static void fix(ClassNode target, Set<IMixinInfo> applied) {
        Map<String, Placeholder> fields = new HashMap<>();
        Map<String, Placeholder> methods = new HashMap<>();
        for (IMixinInfo mixin : applied) {
            if (!isOptiRefineMixin(mixin)) {
                continue;
            }
            for (Placeholder p : byMixin.computeIfAbsent(mixin.getClassName(), k -> read(mixin))) {
                (p.field ? fields : methods).put(p.name + p.desc, p);
            }
        }
        if (fields.isEmpty() && methods.isEmpty()) {
            return;
        }
        boolean srg = isSrgRuntime(target);
        Map<String, int[]> done = new LinkedHashMap<>();
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Placeholder p = f.owner.equals(target.name) ? fields.get(f.name + f.desc) : null;
                    if (p == null || findField(target, f.name, f.desc) != null) {
                        continue;
                    }
                    boolean isStatic = f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC;
                    String real = p.realFieldName(target, srg, isStatic);
                    if (real == null) {
                        refuse(target, p, "real field " + p.describeTarget() + " does not exist with the type " + f.desc
                                + (isStatic ? " (static)" : ""));
                        continue;
                    }
                    f.name = real;
                    count(done, target, p, real);
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    Placeholder p = m.owner.equals(target.name) ? methods.get(m.name + m.desc) : null;
                    if (p == null || findMethod(target, m.name, m.desc) != null) {
                        continue;
                    }
                    String real = p.realMethodName(target, srg, m.getOpcode() == Opcodes.INVOKESTATIC);
                    if (real == null) {
                        refuse(target, p, "real method " + p.describeTarget() + m.desc + " does not exist");
                        continue;
                    }
                    m.name = real;
                    count(done, target, p, real);
                }
            }
        }
        for (Map.Entry<String, int[]> e : done.entrySet()) {
            String line = e.getKey() + " (" + e.getValue()[0] + (e.getValue()[0] == 1 ? " reference)" : " references)");
            rewrites.add(line);
            CompatStatus.LOG.info("[gambiarra] Untranslated OptiRefine placeholder fixed: {}", line);
        }
        if (!done.isEmpty()) {
            CompatStatus.markRan("OptiRefine acc$ placeholders", done.size() + " in " + target.name.replace('/', '.'));
        }
    }

    private static void count(Map<String, int[]> done, ClassNode target, Placeholder p, String real) {
        String key = target.name.replace('/', '.') + ": " + p.name + " -> " + real;
        done.computeIfAbsent(key, k -> new int[1])[0]++;
    }

    private static void refuse(ClassNode target, Placeholder p, String why) {
        String line = target.name.replace('/', '.') + ": " + p.name + " not replaced: " + why;
        if (!refused.contains(line)) {
            refused.add(line);
            CompatStatus.LOG.warn("[gambiarra] Untranslated OptiRefine placeholder, NOT fixed: {}", line);
            gambiarra.common.StartupReport.failed("fixOptiRefineAccPlaceholders", "not fixed: " + line);
        }
    }

    static boolean isOptiRefineMixin(IMixinInfo mixin) {
        String config = mixin.getConfig() != null ? mixin.getConfig().getName() : "";
        return mixin.getClassName().startsWith(OPTIREFINE_PACKAGE) && config.startsWith("optirefine");
    }

    /** Members with @AccessTransformer in the mixin (OptiRefine's postApply deletes them from the target). */
    static List<Placeholder> read(IMixinInfo mixin) {
        List<Placeholder> out = new ArrayList<>();
        ClassNode node = mixin.getClassNode(0);
        for (FieldNode f : node.fields) {
            AnnotationNode at = annotation(f.visibleAnnotations);
            if (at != null) {
                out.add(new Placeholder(true, f.name, f.desc, value(at, "name"), Boolean.TRUE.equals(value(at, "deobf"))));
            }
        }
        for (MethodNode m : node.methods) {
            AnnotationNode at = annotation(m.visibleAnnotations);
            String name = at != null ? (String) value(at, "name") : null;
            // "<class>" changes the class's access; "<init>" is a constructor: there is no call to translate.
            if (at != null && (name == null || !name.startsWith("<"))) {
                out.add(new Placeholder(false, m.name, m.desc, name, Boolean.TRUE.equals(value(at, "deobf"))));
            }
        }
        return out;
    }

    private static AnnotationNode annotation(List<AnnotationNode> list) {
        if (list != null) {
            for (AnnotationNode a : list) {
                if (AT_DESC.equals(a.desc)) {
                    return a;
                }
            }
        }
        return null;
    }

    private static Object value(AnnotationNode a, String key) {
        if (a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (key.equals(a.values.get(i))) {
                    return a.values.get(i + 1);
                }
            }
        }
        return null;
    }

    /** The same rule as CursedMixinExtensions: are there SRG names in the class? */
    static boolean isSrgRuntime(ClassNode target) {
        for (FieldNode f : target.fields) {
            if (f.name.matches("field_\\d+_[a-zA-Z]+")) {
                return true;
            }
        }
        for (MethodNode m : target.methods) {
            if (m.name.matches("func_\\d+_[a-zA-Z]+")) {
                return true;
            }
        }
        return false;
    }

    static FieldNode findField(ClassNode c, String name, String desc) {
        for (FieldNode f : c.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) {
                return f;
            }
        }
        return null;
    }

    static MethodNode findMethod(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    /** A placeholder declared in an OptiRefine @AccessTransformer. */
    static final class Placeholder {
        final boolean field;
        final String name;
        final String desc;
        /** The real name in the annotation (SRG); null = the placeholder's own name. */
        final String atName;
        final boolean deobf;

        Placeholder(boolean field, String name, String desc, Object atName, boolean deobf) {
            this.field = field;
            this.name = name;
            this.desc = desc;
            this.atName = atName instanceof String && !((String) atName).isEmpty() ? ((String) atName).replace('.', '/') : null;
            this.deobf = deobf;
        }

        String describeTarget() {
            return atName != null ? atName : name;
        }

        /** How OptiRefine resolves it: the annotation's name and, with deobf in an MCP runtime, the mapped name. */
        private List<String> candidates(ClassNode target, boolean srg) {
            List<String> c = new ArrayList<>();
            String raw = atName != null ? atName : name;
            c.add(raw);
            if (deobf && !srg) {
                c.add(field ? FMLDeobfuscatingRemapper.INSTANCE.mapFieldName(target.name, raw, desc)
                        : FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(target.name, raw, desc));
            }
            return c;
        }

        /** Real name only if the field exists in the class with the same type and the same static; otherwise null. */
        String realFieldName(ClassNode target, boolean srg, boolean isStatic) {
            for (String n : candidates(target, srg)) {
                FieldNode f = findField(target, n, desc);
                if (f != null && ((f.access & Opcodes.ACC_STATIC) != 0) == isStatic) {
                    return n;
                }
            }
            return null;
        }

        String realMethodName(ClassNode target, boolean srg, boolean isStatic) {
            for (String n : candidates(target, srg)) {
                MethodNode m = findMethod(target, n, desc);
                if (m != null && ((m.access & Opcodes.ACC_STATIC) != 0) == isStatic) {
                    return n;
                }
            }
            return null;
        }
    }

}
