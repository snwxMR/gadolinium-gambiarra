import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import gambiarra.lock.CodeLock;
import net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher;

/**
 * Fingerprints of the game's own methods (see Opt.vanilla), without opening the game: it does to the class what Cleanroom does
 * when loading it. Reads the official client jar, applies the binpatch from Cleanroom's universal jar (GDiff) and renames
 * notch to SRG with the mcp_config's joined.tsrg. The build check checkLocks uses it.
 *
 * Usage: java -cp repoClasses;universal;asm;asm-tree;asm-commons;guava VanillaPrints.java client.jar universal.jar
 *        mcp_config.zip class/Internal method[,method...] [class/Internal method...] [--conferir folder] [--salvar folder]
 *
 * "--conferir folder" compares with the classes exported by Mixin (.mixin.out/class): a method no mixin touched has to give
 * the same fingerprint. That is how the path (patch + rename) was checked. "--salvar folder" saves the patched, renamed classes.
 */
public class VanillaPrints {

    static final Map<String, String> SRG_TO_NOTCH = new HashMap<>();
    static final Map<String, String> NOTCH_TO_SRG = new HashMap<>();
    /** notch: class -> "name desc" -> srg (methods), "name" -> srg (fields). */
    static final Map<String, Map<String, String>> MEMBERS = new HashMap<>();
    static ZipFile client;
    /** "+universal.jar" as the second argument: only another source of classes (Forge's, which are not in the client jar), with no binpatch. */
    static ZipFile extra;
    static final Map<String, byte[]> PATCHES = new HashMap<>();
    /** Cleanroom 0.7.x: another binpatch format (see applyDelta). */
    static final Map<String, byte[]> DELTAS = new HashMap<>();
    static final Map<String, String[]> SUPERS = new HashMap<>();

    public static void main(String[] args) throws Exception {
        client = new ZipFile(args[0]);
        readPatches(args[1]);
        readMappings(args[2]);
        String check = null;
        String save = null;
        List<String[]> wanted = new ArrayList<>();
        for (int i = 3; i < args.length; i += 2) {
            if (args[i].equals("--conferir")) {
                check = args[i + 1];
            } else if (args[i].equals("--salvar")) {
                save = args[i + 1];
            } else {
                wanted.add(new String[] { args[i], args[i + 1] });
            }
        }
        for (String[] w : wanted) {
            byte[] mapped = remap(patched(SRG_TO_NOTCH.getOrDefault(w[0], w[0])));
            if (save != null) {
                java.nio.file.Path out = Paths.get(save, w[0] + ".class");
                Files.createDirectories(out.getParent());
                Files.write(out, mapped);
            }
            byte[] exported = null;
            if (check != null && Files.isRegularFile(Paths.get(check, w[0] + ".class"))) {
                exported = Files.readAllBytes(Paths.get(check, w[0] + ".class"));
            }
            for (String m : w[1].split(",")) {
                String print = CodeLock.vanillaPrint(mapped, m);
                String other = exported == null ? null : CodeLock.vanillaPrint(exported, m);
                System.out.println(w[0] + "#" + m + " " + print
                        + (exported == null ? "" : String.valueOf(print).equals(other) ? " (= exported)" : " (exported: " + other + ")"));
            }
        }
    }

    static byte[] patched(String notch) throws Exception {
        ZipEntry e = client.getEntry(notch + ".class");
        ZipFile from = client;
        if (e == null && extra != null) {
            e = extra.getEntry(notch + ".class");
            from = extra;
        }
        byte[] input;
        try (InputStream in = from.getInputStream(e)) {
            input = in.readAllBytes();
        }
        byte[] delta = DELTAS.get(notch);
        if (delta != null) {
            return applyDelta(input, delta, notch);
        }
        byte[] patch = PATCHES.get(notch);
        return patch == null ? input : new GDiffPatcher().patch(input, patch);
    }

    /**
     * Cleanroom 0.7.x binpatch: 32 bytes of SHA-256 and a list of operations. 01 = new bytes (length, bytes);
     * 00 = copy from the original class (position, length). Numbers in LEB128. The SHA matches the original or the final class.
     */
    static byte[] applyDelta(byte[] input, byte[] delta, String notch) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length * 2);
        int[] p = { 32 };
        while (p[0] < delta.length) {
            int op = delta[p[0]++] & 0xFF;
            if (op == 1) {
                int n = leb(delta, p);
                out.write(delta, p[0], n);
                p[0] += n;
            } else if (op == 0) {
                int from = leb(delta, p);
                int n = leb(delta, p);
                out.write(input, from, n);
            } else {
                throw new IllegalStateException("operation " + op + " in " + notch);
            }
        }
        byte[] result = out.toByteArray();
        byte[] expected = java.util.Arrays.copyOf(delta, 32);
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        if (!java.util.Arrays.equals(expected, sha.digest(result)) && !java.util.Arrays.equals(expected, sha.digest(input))) {
            throw new IllegalStateException("SHA does not match in " + notch);
        }
        return result;
    }

    private static int leb(byte[] b, int[] p) {
        int v = 0;
        int shift = 0;
        while (true) {
            int x = b[p[0]++] & 0xFF;
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return v;
            }
            shift += 7;
        }
    }

    static void readPatches(String universal) throws Exception {
        if (universal.equals("-")) {
            return;
        }
        if (universal.startsWith("+")) {
            extra = new ZipFile(universal.substring(1));
            return;
        }
        try (ZipFile z = new ZipFile(universal); InputStream raw = z.getInputStream(z.getEntry("binpatches.zip"));
                ZipInputStream in = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.getName().startsWith("binpatch/client/") || !e.getName().endsWith(".binpatch")) {
                    continue;
                }
                if (e.getName().endsWith(".class.binpatch")) {
                    String name = e.getName().substring("binpatch/client/".length());
                    DELTAS.put(name.substring(0, name.length() - ".class.binpatch".length()), in.readAllBytes());
                    continue;
                }
                ByteArrayDataInput d = ByteStreams.newDataInput(in.readAllBytes());
                d.readUTF();
                String source = d.readUTF();
                d.readUTF();
                if (d.readBoolean()) {
                    d.readInt();
                }
                byte[] p = new byte[d.readInt()];
                d.readFully(p);
                PATCHES.put(source.replace('.', '/'), p);
            }
        }
    }

    static void readMappings(String zip) throws Exception {
        try (ZipFile z = new ZipFile(zip); InputStream in = z.getInputStream(z.getEntry("config/joined.tsrg"))) {
            String cls = null;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.trim().split(" ");
                if (!line.startsWith("\t") && !line.startsWith(" ")) {
                    cls = p[0];
                    NOTCH_TO_SRG.put(p[0], p[1]);
                    SRG_TO_NOTCH.put(p[1], p[0]);
                } else if (p.length == 2) {
                    MEMBERS.computeIfAbsent(cls, k -> new HashMap<>()).put(p[0], p[1]);
                } else {
                    MEMBERS.computeIfAbsent(cls, k -> new HashMap<>()).put(p[0] + " " + p[1], p[2]);
                }
            }
        }
    }

    static String[] supers(String notch) {
        return SUPERS.computeIfAbsent(notch, n -> {
            try {
                ZipEntry e = client.getEntry(n + ".class");
                ZipFile from = client;
                if (e == null && extra != null) {
                    e = extra.getEntry(n + ".class");
                    from = extra;
                }
                if (e == null) {
                    return new String[0];
                }
                try (InputStream in = from.getInputStream(e)) {
                    ClassReader r = new ClassReader(in.readAllBytes());
                    String[] i = r.getInterfaces();
                    String[] out = new String[i.length + 1];
                    out[0] = r.getSuperName();
                    System.arraycopy(i, 0, out, 1, i.length);
                    return out;
                }
            } catch (Exception ex) {
                return new String[0];
            }
        });
    }

    static String find(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return null;
        }
        Map<String, String> m = MEMBERS.get(owner);
        if (m != null && m.containsKey(key)) {
            return m.get(key);
        }
        for (String s : supers(owner)) {
            String r = find(s, key, seen);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    static byte[] remap(byte[] input) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(input).accept(new ClassRemapper(w, new Remapper() {
            @Override
            public String map(String name) {
                return NOTCH_TO_SRG.getOrDefault(name, name);
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                String r = find(owner, name + " " + desc, new HashSet<>());
                return r != null ? r : name;
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String r = find(owner, name, new HashSet<>());
                return r != null ? r : name;
            }
        }), 0);
        return w.toByteArray();
    }

}
