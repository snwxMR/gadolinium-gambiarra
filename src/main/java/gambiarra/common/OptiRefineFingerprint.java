// SPDX-License-Identifier: MIT
package gambiarra.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * OptiRefine "fingerprint".
 *
 * Its jar does not record the commit (the version is always 0.0.1-indev) and the bytecode changes with the builder's JDK.
 * So instead of hashing the jar, we hash a CANONICAL description of what the addon depends on:
 *  - optirefine.default.mixin.json (a resource copied unchanged);
 *  - for the OptiRefine classes on the addon's render path: annotations (mixin targets and injection points), method
 *    signatures and the set of references each method makes (methods, fields, types, constants). Synthetic methods
 *    (lambdas, bridges) and invokedynamic are left out, because they depend on the compiler and not on the source.
 *
 * If something relevant changes in those classes, the fingerprint changes and the addon turns itself off (unless
 * forceOptiRefineFingerprint=true).
 */
public final class OptiRefineFingerprint {

    /**
     * The OptiRefine builds the mods were checked against: {name, commit, fingerprint}. Any other build (another commit of its dev
     * branch, a later release) has another fingerprint.
     *  - 78ff8ce: built from source at that commit (the only build before there was a release);
     *  - v0.1.0-alpha: the release of 2026-11-14 (commit 6c4855e). Built from source, its jar is byte for byte the one published on
     *    GitHub (the same SHA-256).
     */
    public static final String[][] KNOWN = {
            { "78ff8ce", "78ff8ce408e6687093d95f19218ffda3214c0475", "719127687719e2d313e9d262cee2e4bdd16cffcccfcbfcfda9af318cf758c780" },
            { "v0.1.0-alpha", "6c4855e9d82ff7f004291833c4eb1098fe4bfc8e", "b757ddf1dc9f14a65e2b8022dec7f10ee24c2f87bbce7f28cc9536a6ffa5d284" },
    };

    /** The release that fixed the zoom key (issue #5) and the shader float view (issue #6). */
    public static final String RELEASE_0_1_0_ALPHA = "v0.1.0-alpha";

    /** Class used to find the OptiRefine jar. */
    public static final String OPTIREFINE_CLASS = "mods/Hileb/optirefine/core/OptiRefineCore.class";

    static final String MIXIN_JSON = "optirefine.default.mixin.json";

    static final String[] CLASSES = {
            "mods/Hileb/optirefine/core/transformer/OptifineTransformerTransformer",
            "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/MixinBufferBuilder",
            "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/MixinEntityRenderer",
            "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/MixinRenderGlobal",
            "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/MixinVboRenderList",
            "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/vertex/MixinDefaultVertexFormats",
            "mods/Hileb/optirefine/mixin/defaults/optifine/MixinShaders",
    };

    private OptiRefineFingerprint() {
    }

    /** Name of the known build with this fingerprint ("78ff8ce", "v0.1.0-alpha"), or null for any other build. */
    @javax.annotation.Nullable
    public static String buildOf(@javax.annotation.Nullable String fingerprint) {
        for (String[] known : KNOWN) {
            if (known[2].equals(fingerprint)) {
                return known[0];
            }
        }
        return null;
    }

    /** The known builds, for the log: "78ff8ce or v0.1.0-alpha". */
    public static String knownNames() {
        List<String> names = new ArrayList<>();
        for (String[] known : KNOWN) {
            names.add(known[0]);
        }
        return String.join(" or ", names);
    }

    /** Fingerprint of the installed OptiRefine, or null if it is not present. */
    @javax.annotation.Nullable
    public static String ofInstalled() throws IOException {
        File jar = JarUtil.jarOf(OPTIREFINE_CLASS);
        return jar != null ? compute(jar) : null;
    }

    public static String compute(File jar) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] json = JarUtil.readEntry(jar, MIXIN_JSON);
        sb.append("json ").append(json != null ? sha256(json) : "<absent>").append('\n');
        for (String name : CLASSES) {
            byte[] bytes = JarUtil.readEntry(jar, name + ".class");
            if (bytes == null) {
                sb.append("class ").append(name).append(" <absent>\n");
            } else {
                describe(bytes, sb);
            }
        }
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void describe(byte[] bytes, StringBuilder sb) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        sb.append("class ").append(node.name).append(" extends ").append(node.superName).append('\n');
        appendAnnotations(sb, "  ", node.visibleAnnotations, node.invisibleAnnotations);

        List<FieldNode> fields = new ArrayList<>(node.fields);
        fields.sort((a, b) -> (a.name + a.desc).compareTo(b.name + b.desc));
        for (FieldNode f : fields) {
            if ((f.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            sb.append("  field ").append(f.name).append(' ').append(f.desc).append('\n');
            appendAnnotations(sb, "    ", f.visibleAnnotations, f.invisibleAnnotations);
        }

        List<MethodNode> methods = new ArrayList<>(node.methods);
        methods.sort((a, b) -> (a.name + a.desc).compareTo(b.name + b.desc));
        for (MethodNode m : methods) {
            if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || m.name.startsWith("lambda$")) {
                continue;
            }
            sb.append("  method ").append(m.name).append(' ').append(m.desc)
                    .append((m.access & Opcodes.ACC_STATIC) != 0 ? " static" : "").append('\n');
            appendAnnotations(sb, "    ", m.visibleAnnotations, m.invisibleAnnotations);
            List<String> refs = new ArrayList<>();
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    refs.add("m " + mi.owner + '.' + mi.name + mi.desc);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    refs.add("f " + fi.getOpcode() + ' ' + fi.owner + '.' + fi.name + ' ' + fi.desc);
                } else if (insn instanceof TypeInsnNode) {
                    refs.add("t " + insn.getOpcode() + ' ' + ((TypeInsnNode) insn).desc);
                } else if (insn instanceof LdcInsnNode) {
                    refs.add("c " + constant(((LdcInsnNode) insn).cst));
                }
            }
            Collections.sort(refs);
            for (String r : refs) {
                sb.append("    ").append(r).append('\n');
            }
        }
    }

    @SafeVarargs
    private static void appendAnnotations(StringBuilder sb, String indent, List<AnnotationNode>... lists) {
        List<String> all = new ArrayList<>();
        for (List<AnnotationNode> list : lists) {
            if (list != null) {
                for (AnnotationNode a : list) {
                    all.add(annotation(a));
                }
            }
        }
        Collections.sort(all);
        for (String a : all) {
            sb.append(indent).append('@').append(a).append('\n');
        }
    }

    private static String annotation(AnnotationNode a) {
        Map<String, String> values = new TreeMap<>();
        if (a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                values.put(String.valueOf(a.values.get(i)), value(a.values.get(i + 1)));
            }
        }
        return a.desc + values;
    }

    private static String value(Object v) {
        if (v instanceof AnnotationNode) {
            return annotation((AnnotationNode) v);
        }
        if (v instanceof List) {
            List<String> items = new ArrayList<>();
            for (Object o : (List<?>) v) {
                items.add(value(o));
            }
            return items.toString();
        }
        if (v instanceof String[]) {
            String[] e = (String[]) v;
            return "enum " + e[0] + '.' + e[1];
        }
        return constant(v);
    }

    private static String constant(Object v) {
        if (v instanceof Type) {
            return "type " + ((Type) v).getDescriptor();
        }
        if (v instanceof String) {
            return '"' + (String) v + '"';
        }
        return v == null ? "null" : v.getClass().getSimpleName() + ' ' + v;
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Usage: java gambiarra.common.OptiRefineFingerprint <OptiRefine jar> [--describe] */
    public static void main(String[] args) throws IOException {
        File jar = new File(args[0]);
        if (args.length > 1 && "--describe".equals(args[1])) {
            byte[] json = JarUtil.readEntry(jar, MIXIN_JSON);
            System.out.println("json " + (json != null ? sha256(json) : "<absent>"));
            for (String name : CLASSES) {
                byte[] bytes = JarUtil.readEntry(jar, name + ".class");
                StringBuilder sb = new StringBuilder();
                if (bytes != null) {
                    describe(bytes, sb);
                }
                System.out.print(sb);
            }
        }
        System.out.println(compute(jar));
    }

}
