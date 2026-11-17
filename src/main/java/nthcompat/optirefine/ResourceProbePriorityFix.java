// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import gambiarra.common.JarUtil;
import gambiarra.common.MixinExtensionRegistry;
import gambiarra.common.StartupReport;
import nthcompat.CompatStatus;

/**
 * OptiRefine v0.1.0-alpha x VintageFix (option fixOptiRefineVintageFix; issue text in docs/upstream/issue-optirefine-mixin-conflicts.md).
 *
 * OptiRefine v0.1.0-alpha added to its MixinDefaultResourcePack a @WrapOperation in resourceExists (optiRefine$closeResourceProbe: it closes
 * the stream that resourceExists opens only to test it for null). VintageFix's resourcepacks group replaces that method with an @Overwrite,
 * at the same priority (1000). Mixin only lets an injector into a method another mixin merged when the merging mixin has a LOWER priority
 * (InjectionPoint.checkPriority), and it checks that before counting require: the game does not start ("... cannot inject into ...
 * merged by ...").
 *
 * VintageFix's resourceExists still calls getResourceStream (when OptiFine is present), the call OptiRefine looks for. So the two can live
 * together if OptiRefine's mixin counts as one priority above VintageFix's on this class, which is what OptiRefine itself would get with
 * priority = 1001. This Mixin extension does it in the preApply of DefaultResourcePack, before any of its mixins is applied; the order
 * the mixins are applied in was already decided and does not change. The priority goes back in export(), when the class is done.
 *
 * It only acts if both mixins are about to be applied to the class, both mixin classes are byte for byte the checked ones (CLASSES) and
 * Mixin has the expected structure. Otherwise it changes nothing and says why in the log: without it the game stops as it would without
 * Gambiarra, and the line gives the two ways out (WAYS_OUT).
 */
public final class ResourceProbePriorityFix implements IExtension {

    public static final String OPTION = "fixOptiRefineVintageFix";
    static final String TARGET = "net/minecraft/client/resources/DefaultResourcePack";
    static final String OPTIREFINE_MIXIN = "mods.Hileb.optirefine.mixin.defaults.minecraft.client.resources.MixinDefaultResourcePack";
    static final String VINTAGEFIX_MIXIN = "org.embeddedt.vintagefix.mixin.resourcepacks.MixinDefaultResourcePack";
    /** Any class of VintageFix (only to know it is installed). */
    public static final String VINTAGEFIX_CLASS = "org/embeddedt/vintagefix/mixin/resourcepacks/MixinDefaultResourcePack.class";

    /** The two mixin classes checked: {resource, SHA-256}. OptiRefine v0.1.0-alpha (commit 6c4855e) and VintageFix 0.7.2. */
    static final String[][] CLASSES = {
            { "mods/Hileb/optirefine/mixin/defaults/minecraft/client/resources/MixinDefaultResourcePack.class",
                    "967d13a5e45b4a3832d907024b16371114bfec8e4d38da8eaaa409dc2d56722c" },
            { VINTAGEFIX_CLASS, "75a422f4489f9586475f4c17973923875227ec288b6a52ecdb186e714a784e38" },
    };

    static final String WAYS_OUT = "set mixin.resourcepacks=false in config/vintagefix.properties, or use an OptiRefine build from commit 78ff8ce";

    private static final ResourceProbePriorityFix INSTANCE = new ResourceProbePriorityFix();
    private static boolean registered;
    private static volatile String result = "not run (DefaultResourcePack not loaded yet)";
    /** What export() puts back: the mixin, its priority field and the value it had. */
    private static IMixinInfo raised;
    private static Field raisedField;
    private static int originalPriority;

    private ResourceProbePriorityFix() {
    }

    /** Called in the nthcompat plugin's onLoad, before DefaultResourcePack is loaded. false = could not register (Mixin changed). */
    public static synchronized boolean register() {
        if (registered) {
            return true;
        }
        try {
            MixinExtensionRegistry.add(INSTANCE);
            registered = true;
            return true;
        } catch (Throwable t) {
            result = "could not register with Mixin: " + t;
            CompatStatus.LOG.warn("[gambiarra] {}: could not register with Mixin ({}). With OptiRefine v0.1.0-alpha and VintageFix the game will "
                    + "probably stop: {}", OPTION, t.toString(), WAYS_OUT);
            StartupReport.failed(OPTION, result);
            return false;
        }
    }

    public static String result() {
        return result;
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        if (!TARGET.equals(context.getClassNode().name)) {
            return;
        }
        try {
            raise(context);
        } catch (Throwable t) {
            // Never brings down the class transformation: without the change, Mixin goes on as it would without this.
            result = "failed, nothing changed: " + t;
            CompatStatus.LOG.warn("[gambiarra] {}: failed, nothing changed ({}). If the game stops now: {}", OPTION, t.toString(), WAYS_OUT);
            StartupReport.failed(OPTION, t.toString());
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
        if (raised == null || classNode == null || !TARGET.equals(classNode.name)) {
            return;
        }
        try {
            raisedField.setInt(raised, originalPriority);
        } catch (Throwable t) {
            // OptiRefine's mixin only targets this class: a priority left raised changes nothing else.
            CompatStatus.LOG.warn("[gambiarra] {}: could not put the priority back ({})", OPTION, t.toString());
        }
        raised = null;
    }

    private static void raise(ITargetClassContext context) throws ReflectiveOperationException {
        Collection<?> mixins = mixinsOf(context);
        IMixinInfo optiRefine = find(mixins, OPTIREFINE_MIXIN);
        IMixinInfo vintageFix = find(mixins, VINTAGEFIX_MIXIN);
        if (optiRefine == null || vintageFix == null) {
            notNeeded((optiRefine == null ? "OptiRefine's" : "VintageFix's") + " DefaultResourcePack mixin is not applied here");
            return;
        }
        byte[] optiRefineBytes = classBytes(CLASSES[0][0]);
        if (optiRefineBytes == null || !injectsInto(optiRefineBytes, "resourceExists", "func_110589_b")) {
            notNeeded("this OptiRefine build does not inject into resourceExists");
            return;
        }
        if (optiRefine.getPriority() > vintageFix.getPriority()) {
            notNeeded("OptiRefine's mixin already has a higher priority than VintageFix's");
            return;
        }
        String problem = lockProblem(optiRefineBytes, classBytes(CLASSES[1][0]));
        if (problem != null) {
            result = "OFF by the code lock: " + problem;
            CompatStatus.LOG.warn("[gambiarra] {}: not applied, {}. OptiRefine and VintageFix both change DefaultResourcePack.resourceExists, "
                    + "and the game will probably stop: {}", OPTION, problem, WAYS_OUT);
            StartupReport.lock(OPTION, problem);
            return;
        }
        Field field = priorityField(optiRefine.getClass());
        int before = field.getInt(optiRefine);
        int after = vintageFix.getPriority() + 1;
        field.setInt(optiRefine, after);
        if (optiRefine.getPriority() != after) {
            field.setInt(optiRefine, before);
            throw new IllegalStateException("the mixin's priority did not change (" + optiRefine.getPriority() + ")");
        }
        raised = optiRefine;
        raisedField = field;
        originalPriority = before;
        result = "active: OptiRefine's DefaultResourcePack mixin at priority " + after + " (was " + before + ") on that class only, above "
                + "VintageFix's " + vintageFix.getPriority();
        CompatStatus.LOG.info("[gambiarra] OptiRefine x VintageFix: {}. Both apply as they are written: VintageFix's resourceExists, and "
                + "OptiRefine closes the stream it opens there.", result);
        CompatStatus.markRan(OPTION, "priority " + before + " -> " + after);
    }

    private static void notNeeded(String why) {
        result = "not needed: " + why;
        CompatStatus.LOG.info("[gambiarra] OptiRefine x VintageFix: {}", result);
        StartupReport.notNeeded(OPTION, result);
    }

    /** The mixins about to be applied to the class (TargetClassContext.mixins, a SortedSet of MixinInfo). */
    static Collection<?> mixinsOf(ITargetClassContext context) throws ReflectiveOperationException {
        Field field = field(context.getClass(), "mixins");
        if (!Collection.class.isAssignableFrom(field.getType())) {
            throw new NoSuchFieldException("TargetClassContext.mixins is a " + field.getType().getName());
        }
        return (Collection<?>) field.get(context);
    }

    static IMixinInfo find(Collection<?> mixins, String className) {
        for (Object o : mixins) {
            if (o instanceof IMixinInfo && className.equals(((IMixinInfo) o).getClassName())) {
                return (IMixinInfo) o;
            }
        }
        return null;
    }

    /** MixinInfo.priority (a final int, read by getPriority and by every MixinTargetContext of this application). */
    static Field priorityField(Class<?> mixinInfo) throws ReflectiveOperationException {
        Field field = field(mixinInfo, "priority");
        if (field.getType() != int.class) {
            throw new NoSuchFieldException("MixinInfo.priority is a " + field.getType().getName());
        }
        return field;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // the next superclass
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static byte[] classBytes(String resource) {
        try {
            File jar = JarUtil.jarOf(resource);
            return jar != null ? JarUtil.readEntry(jar, resource) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** null if both classes are the checked ones; otherwise, which one differs. */
    static String lockProblem(byte[] optiRefine, byte[] vintageFix) {
        List<String> differ = new ArrayList<>();
        if (optiRefine == null || !CLASSES[0][1].equals(JarUtil.sha256Hex(optiRefine))) {
            differ.add("OptiRefine's MixinDefaultResourcePack is not v0.1.0-alpha's");
        }
        if (vintageFix == null || !CLASSES[1][1].equals(JarUtil.sha256Hex(vintageFix))) {
            differ.add("VintageFix's MixinDefaultResourcePack is not 0.7.2's");
        }
        return differ.isEmpty() ? null : String.join("; ", differ);
    }

    /** Does any injector of this mixin class name one of these target methods? */
    static boolean injectsInto(byte[] mixinClass, String... methods) {
        ClassNode node = new ClassNode();
        new ClassReader(mixinClass).accept(node, ClassReader.SKIP_CODE);
        for (MethodNode m : node.methods) {
            for (List<AnnotationNode> list : asList(m.visibleAnnotations, m.invisibleAnnotations)) {
                for (AnnotationNode a : list) {
                    if (a.values == null) {
                        continue;
                    }
                    for (int i = 0; i + 1 < a.values.size(); i += 2) {
                        if ("method".equals(a.values.get(i)) && a.values.get(i + 1) instanceof List) {
                            for (Object target : (List<?>) a.values.get(i + 1)) {
                                for (String method : methods) {
                                    if (String.valueOf(target).startsWith(method)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @SafeVarargs
    private static List<List<AnnotationNode>> asList(List<AnnotationNode>... lists) {
        List<List<AnnotationNode>> out = new ArrayList<>();
        for (List<AnnotationNode> l : lists) {
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

}
