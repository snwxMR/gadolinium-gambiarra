// SPDX-License-Identifier: MIT
package gambiarra.lock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Locks that survive updates of the target mods (Gambiarra 0.3.1). An optimization depends on the behaviour of some methods of the mod and calls some of its members; the rest of the class can change freely in a new version.
 *
 * - Method fingerprint: SHA-256 of a method's bytecode, normalized (no constant pool numbers, no lines or frames; labels by order). Recompiling the same code gives the same fingerprint; changing the method, another.
 * - Links: reads the bytecode of OUR classes and checks that each method, field and class of the mod they use still exists (also looking in the mod's superclasses). So a new version never leads to a NoSuchMethodError mid-game: the optimization
 *   turns off before that.
 *
 * main(jar, class, method...): prints the fingerprints (development tool, to update the locks).
 */
public final class CodeLock {

    private CodeLock() {
    }

    /** Fingerprint of the methods with this name (all overloads, in descriptor order); null if the class has none. */
    public static String methodPrint(byte[] classBytes, String name) {
        return methodPrint(read(classBytes), name, true);
    }

    /**
     * The fingerprint for the game's classes: without the method's access modifiers (public, final...), which access transformers change without changing the code. It is the one WorldgenStatus checks on the ClassNode and the one the
     * ferramentas/vanilla tool computes from the game's jar and Cleanroom's patches.
     */
    public static String vanillaPrint(byte[] classBytes, String name) {
        return methodPrint(read(classBytes), name, false);
    }

    private static String methodPrint(ClassNode cn, String name, boolean withAccess) {
        List<MethodNode> methods = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) {
                methods.add(m);
            }
        }
        if (methods.isEmpty()) {
            return null;
        }
        methods.sort((a, b) -> a.desc.compareTo(b.desc));
        StringBuilder sb = new StringBuilder();
        for (MethodNode m : methods) {
            describe(m, sb, withAccess);
        }
        return sha256(sb.toString());
    }

    /**
     * The game fingerprint (vanillaPrint) of a class already loaded by Mixin (the ClassNode the plugin receives before applying). The class is written again and read like those of a jar (no lines or frames), to come out with the same rules. null
     * if the class does not have the method or cannot be written.
     */
    public static String vanillaPrint(ClassNode cn, String name) {
        try {
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cn.accept(cw);
            return vanillaPrint(cw.toByteArray(), name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void describe(MethodNode m, StringBuilder sb, boolean withAccess) {
        sb.append("M ").append(withAccess ? m.access : 0).append(' ').append(m.name).append(m.desc).append('\n');
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof LabelNode) {
                labels.put((LabelNode) n, labels.size());
            }
        }
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof LabelNode) {
                sb.append('L').append(labels.get(n)).append('\n');
                continue;
            }
            if (n.getOpcode() < 0) {
                continue;
            }
            sb.append(n.getOpcode());
            if (n instanceof IntInsnNode) {
                sb.append(' ').append(((IntInsnNode) n).operand);
            } else if (n instanceof VarInsnNode) {
                sb.append(' ').append(((VarInsnNode) n).var);
            } else if (n instanceof TypeInsnNode) {
                sb.append(' ').append(((TypeInsnNode) n).desc);
            } else if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) n;
                sb.append(' ').append(f.owner).append('.').append(f.name).append(' ').append(f.desc);
            } else if (n instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) n;
                sb.append(' ').append(mi.owner).append('.').append(mi.name).append(mi.desc).append(mi.itf ? " I" : "");
            } else if (n instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) n;
                sb.append(' ').append(d.name).append(d.desc).append(' ').append(handle(d.bsm));
                for (Object a : d.bsmArgs) {
                    sb.append(' ').append(constant(a));
                }
            } else if (n instanceof JumpInsnNode) {
                sb.append(" L").append(labels.get(((JumpInsnNode) n).label));
            } else if (n instanceof LdcInsnNode) {
                sb.append(' ').append(constant(((LdcInsnNode) n).cst));
            } else if (n instanceof IincInsnNode) {
                sb.append(' ').append(((IincInsnNode) n).var).append(' ').append(((IincInsnNode) n).incr);
            } else if (n instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode t = (TableSwitchInsnNode) n;
                sb.append(' ').append(t.min).append(' ').append(t.max).append(" L").append(labels.get(t.dflt));
                for (LabelNode l : t.labels) {
                    sb.append(" L").append(labels.get(l));
                }
            } else if (n instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode t = (LookupSwitchInsnNode) n;
                sb.append(" L").append(labels.get(t.dflt)).append(' ').append(t.keys);
                for (LabelNode l : t.labels) {
                    sb.append(" L").append(labels.get(l));
                }
            } else if (n instanceof MultiANewArrayInsnNode) {
                sb.append(' ').append(((MultiANewArrayInsnNode) n).desc).append(' ').append(((MultiANewArrayInsnNode) n).dims);
            }
            sb.append('\n');
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            sb.append("T L").append(labels.get(t.start)).append(" L").append(labels.get(t.end)).append(" L")
                    .append(labels.get(t.handler)).append(' ').append(t.type).append('\n');
        }
    }

    private static String handle(Handle h) {
        return h.getTag() + ":" + h.getOwner() + "." + h.getName() + h.getDesc() + (h.isInterface() ? " I" : "");
    }

    private static String constant(Object c) {
        if (c instanceof Handle) {
            return handle((Handle) c);
        }
        if (c instanceof Type) {
            return "T" + ((Type) c).getDescriptor();
        }
        return c.getClass().getSimpleName() + ":" + c;
    }

    /**
     * The missing links: members and classes whose owner starts with one of the prefixes (the target mod's package, with "/"), used by our class and absent in the installed version of the mod. classBytes returns the bytes of a mod class (internal
     * name) or null. Empty = everything exists.
     */
    public static List<String> missingLinks(byte[] ours, String[] prefixes, Function<String, byte[]> classBytes) {
        List<String> missing = new ArrayList<>();
        Map<String, ClassNode> cache = new HashMap<>();
        ClassNode cn = read(ours);
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) n;
                    check(mi.owner, mi.name, mi.desc, true, prefixes, classBytes, cache, missing);
                } else if (n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) n;
                    check(f.owner, f.name, f.desc, false, prefixes, classBytes, cache, missing);
                } else if (n instanceof TypeInsnNode) {
                    String t = Type.getObjectType(((TypeInsnNode) n).desc).getInternalName();
                    check(t, null, null, false, prefixes, classBytes, cache, missing);
                } else if (n instanceof InvokeDynamicInsnNode) {
                    for (Object a : ((InvokeDynamicInsnNode) n).bsmArgs) {
                        if (a instanceof Handle) {
                            Handle h = (Handle) a;
                            boolean method = h.getTag() >= 5;
                            check(h.getOwner(), h.getName(), h.getDesc(), method, prefixes, classBytes, cache, missing);
                        }
                    }
                }
            }
        }
        return missing;
    }

    private static void check(String owner, String name, String desc, boolean method, String[] prefixes,
            Function<String, byte[]> classBytes, Map<String, ClassNode> cache, List<String> missing) {
        if (owner.startsWith("[")) {
            return;
        }
        boolean ours = false;
        for (String p : prefixes) {
            ours |= owner.startsWith(p);
        }
        if (!ours) {
            return;
        }
        String what = name == null ? owner : owner + "." + name + (method ? desc : " " + desc);
        if (name == null) {
            if (node(owner, classBytes, cache) == null && !missing.contains(what)) {
                missing.add(what);
            }
            return;
        }
        if (declared(owner, name, desc, method, prefixes, classBytes, cache, new java.util.HashSet<>())) {
            return;
        }
        if (!missing.contains(what)) {
            missing.add(what);
        }
    }

    /**
     * The member is in the class, in a superclass or in an interface of it. If the hierarchy leaves the mod's packages (the game, Java), the member may be inherited from there: it counts as present (only what belongs to the mod can vanish in a new
     * version of the mod).
     */
    private static boolean declared(String c, String name, String desc, boolean method, String[] prefixes,
            Function<String, byte[]> classBytes, Map<String, ClassNode> cache, java.util.Set<String> seen) {
        if (c == null || !seen.add(c)) {
            return false;
        }
        boolean inMod = false;
        for (String p : prefixes) {
            inMod |= c.startsWith(p);
        }
        if (!inMod) {
            return true;
        }
        ClassNode n = node(c, classBytes, cache);
        if (n == null) {
            return false;
        }
        if (method) {
            for (MethodNode m : n.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    return true;
                }
            }
        } else {
            for (FieldNode f : n.fields) {
                if (f.name.equals(name) && f.desc.equals(desc)) {
                    return true;
                }
            }
        }
        if (n.superName != null && !"java/lang/Object".equals(n.superName)
                && declared(n.superName, name, desc, method, prefixes, classBytes, cache, seen)) {
            return true;
        }
        for (String i : n.interfaces) {
            if (declared(i, name, desc, method, prefixes, classBytes, cache, seen)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode node(String internalName, Function<String, byte[]> classBytes, Map<String, ClassNode> cache) {
        if (cache.containsKey(internalName)) {
            return cache.get(internalName);
        }
        byte[] b = classBytes.apply(internalName);
        ClassNode n = b == null ? null : read(b);
        cache.put(internalName, n);
        return n;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cn;
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Development: java gambiarra.lock.CodeLock jar class/Inner method [method...] */
    public static void main(String[] args) throws Exception {
        try (ZipFile zip = new ZipFile(new File(args[0])); java.io.InputStream in = zip.getInputStream(zip.getEntry(args[1] + ".class"))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) {
                out.write(buf, 0, r);
            }
            byte[] bytes = out.toByteArray();
            for (int i = 2; i < args.length; i++) {
                System.out.println(args[1] + "#" + args[i] + " " + methodPrint(bytes, args[i]));
            }
        }
    }

}
