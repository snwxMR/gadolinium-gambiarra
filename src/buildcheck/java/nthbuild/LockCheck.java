// SPDX-License-Identifier: LGPL-3.0-only
package nthbuild;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import gambiarra.lock.CodeLock;
import nthshaders.common.JarUtil;

/**
 * Build check (task checkLocks): recomputes every code lock of the two mods and fails if one does not match the code.
 *
 * - Target mod classes: from the jars in the instance's mods folder (the same jars the game loads), by SHA-256 or method fingerprint.
 * - The game's own methods (Opt.vanilla): with ferramentas/vanilla/VanillaPrints, from the official client jar with the Cleanroom binpatches
 *   of the given version (Forge classes come from the universal jar as they are).
 * - OptiRefine: its canonical fingerprint (OptiRefineFingerprint). Every known build has to give its own from the jar buildOptiRefine
 *   made (.deps/mods/OptiRefine-<commit>.jar), and the OptiRefine in the mods folder has to be one of the known builds.
 *
 * A lock whose target mod is not in the folder is listed as "absent" and does not fail. The table goes to stdout and to the report file.
 *
 * Args: modsFolder clientJar universalJar mcpConfigZip reportFile builtOptiRefineFolder
 */
public final class LockCheck {

    private static final Map<String, List<File>> index = new HashMap<>();
    private static final List<String[]> rows = new ArrayList<>();
    private static int failures;
    private static int absent;

    private LockCheck() {
    }

    public static void main(String[] args) throws Exception {
        File mods = new File(args[0]);
        indexJars(mods);

        // Gambiarra, worldgen and server module: classes of the target mods and the game's own methods.
        Map<String, List<String[]>> vanilla = new LinkedHashMap<>();
        for (Object o : Class.forName("nthrc.Opt").getEnumConstants()) {
            Object group = get(o, "group");
            String lock = get(group, "id") + "." + get(o, "key");
            for (String[] row : (String[][]) get(o, "classes")) {
                checkClass("Gambiarra", lock, row);
            }
            for (String[] row : (String[][]) get(o, "vanilla")) {
                vanilla.computeIfAbsent(lock, k -> new ArrayList<>()).add(row);
            }
        }
        checkVanilla(args[1], args[2], args[3], vanilla);
        tables("Gambiarra", "minecraft.restingItemsSkipCollision (Ice and Fire listener)",
                new String[][] { { (String) get(Class.forName("nthrc.minecraft.ItemRest"), "IAF_LISTENER"),
                        "m:" + get(Class.forName("nthrc.minecraft.ItemRest"), "IAF_LISTENER_METHOD") + "="
                                + get(Class.forName("nthrc.minecraft.ItemRest"), "IAF_LISTENER_PRINT") } });

        // Gambiarra, client fixes.
        Class<?> iaf = Class.forName("nthcompat.iceandfire.IafCompat");
        tables("Gambiarra", "iceandfire.cacheTabulaPoses", (String[][]) get(iaf, "POSE_CACHE_CLASSES"), (String[][]) get(iaf, "LOOKUP_CLASSES"));
        tables("Gambiarra", "iceandfire.distantDragonAnimation", (String[][]) get(iaf, "DISTANT_CLASSES"));
        Class<?> lights = Class.forName("nthcompat.oflights.OfLightsGate");
        tables("Gambiarra", "optifinedynamiclights.lockFreeLookup", (String[][]) get(lights, "SNAPSHOT_CLASSES"));
        tables("Gambiarra", "optifinedynamiclights.updatePerBlock, reachedSectionsOnly", (String[][]) get(lights, "LIGHT_UPDATE_CLASSES"),
                (String[][]) get(lights, "NOTHIRIUM_CLASSES"));
        tables("Gambiarra", "pulsar.fixSmoothLightCrash", (String[][]) get(Class.forName("nthcompat.pulsar.PulsarCompat"), "CLASSES"));
        Class<?> lod = Class.forName("gambiarra.lod.LodModsMixinPlugin");
        tables("Gambiarra", "lod (LLibrary Tabula parts)", new String[][] { { (String) get(lod, "LLIB_RENDERER"), (String) get(lod, "LLIB_RENDERER_SHA") } });
        tables("Gambiarra", "lod (Lycanites .obj models)", (String[][]) get(lod, "LYC_CLASSES"));

        // Gadolinium.
        tables("Gadolinium", "compat.chunkCompileOrder", (String[][]) get(Class.forName("nthshaders.compat.CompilePriority"), "CLASSES"));
        tables("Gadolinium", "gpuCullingEntities", (String[][]) get(Class.forName("nthshaders.gpu.EntityGraphCulling"), "RENDERLIB_CLASSES"));
        checkOptiRefine("Gadolinium", Class.forName("nthshaders.common.OptiRefineFingerprint"), new File(args[5]));
        checkOptiRefine("Gambiarra", Class.forName("gambiarra.common.OptiRefineFingerprint"), new File(args[5]));
        checkVintageFixFix(new File(args[5]));
        checkDynamicTextureFix(new File(args[5]));

        report(new File(args[4]));
        if (failures > 0) {
            System.err.println("checkLocks: " + failures + " lock(s) do not match the code (table above)");
            System.exit(1);
        }
    }

    private static void indexJars(File mods) throws IOException {
        File[] jars = mods.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) {
            throw new IOException("mods folder not found: " + mods);
        }
        Arrays.sort(jars);
        for (File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") || name.endsWith(".json")) {
                        index.computeIfAbsent(name, k -> new ArrayList<>()).add(jar);
                    }
                }
            } catch (IOException e) {
                System.out.println("unreadable jar skipped: " + jar.getName());
            }
        }
    }

    /** Rows of {class, value...} or {jar index, class, value...}: every value after the .class is an accepted one. */
    private static void tables(String mod, String lock, String[][]... tables) throws IOException {
        for (String[][] table : tables) {
            for (String[] row : table) {
                checkClass(mod, lock, row);
            }
        }
    }

    private static void checkClass(String mod, String lock, String[] row) throws IOException {
        int c = 0;
        while (c < row.length && !row[c].endsWith(".class")) {
            c++;
        }
        String resource = row[c];
        String[] accepted = Arrays.copyOfRange(row, c + 1, row.length);
        String name = resource.substring(resource.lastIndexOf('/') + 1, resource.length() - ".class".length());
        List<File> jars = index.get(resource);
        if (jars == null) {
            absent++;
            rows.add(new String[] { mod, lock, name, "absent", "target mod not in the mods folder" });
            return;
        }
        for (File jar : jars) {
            byte[] bytes = JarUtil.readEntry(jar, resource);
            String sha = JarUtil.sha256Hex(bytes);
            boolean ok = false;
            String found = "SHA " + sha.substring(0, 12);
            for (String a : accepted) {
                if (a.startsWith("m:")) {
                    StringBuilder prints = new StringBuilder();
                    boolean all = true;
                    for (String part : a.substring(2).split(",")) {
                        int eq = part.indexOf('=');
                        String print = CodeLock.methodPrint(bytes, part.substring(0, eq));
                        all &= part.substring(eq + 1).equals(print);
                        prints.append(prints.length() > 0 ? ", " : "").append(part, 0, eq).append(' ').append(print == null ? "missing" : print.substring(0, 12));
                    }
                    ok |= all;
                    if (!all && !ok) {
                        found += "; " + prints;
                    }
                } else {
                    ok |= a.equals(sha);
                }
            }
            result(mod, lock, name + " (" + jar.getName() + ")", ok, found);
        }
    }

    private static final Pattern PRINT = Pattern.compile("^(\\S+)#(\\S+) (\\S+)$");

    /** The game's methods: one run of VanillaPrints with the binpatches (net/minecraft) and one with the universal jar as it is (Forge classes). */
    private static void checkVanilla(String client, String universal, String mcp, Map<String, List<String[]>> wanted) throws Exception {
        Map<String, String> prints = new TreeMap<>();
        List<String> patched = new ArrayList<>(Arrays.asList(client, universal, mcp));
        List<String> plain = new ArrayList<>(Arrays.asList(client, "+" + universal, mcp));
        for (List<String[]> list : wanted.values()) {
            for (String[] row : list) {
                String internal = row[0].replace('.', '/');
                StringBuilder methods = new StringBuilder();
                for (String part : row[1].split(",")) {
                    methods.append(methods.length() > 0 ? "," : "").append(part, 0, part.indexOf('='));
                }
                if (!internal.startsWith("net/minecraft")) {
                    continue;
                }
                List<String> args = internal.startsWith("net/minecraftforge/") ? plain : patched;
                args.add(internal);
                args.add(methods.toString());
            }
        }
        for (List<String> args : Arrays.asList(patched, plain)) {
            if (args.size() > 3) {
                prints.putAll(runVanillaPrints(args.toArray(new String[0])));
            }
        }
        for (Map.Entry<String, List<String[]>> e : wanted.entrySet()) {
            for (String[] row : e.getValue()) {
                String internal = row[0].replace('.', '/');
                if (!internal.startsWith("net/minecraft")) {
                    modClassInGameLock(e.getKey(), internal, row[1]);
                    continue;
                }
                for (String part : row[1].split(",")) {
                    int eq = part.indexOf('=');
                    String method = part.substring(0, eq);
                    String found = prints.get(internal + "#" + method);
                    String simple = internal.substring(internal.lastIndexOf('/') + 1) + "#" + method;
                    result("Gambiarra", e.getKey(), simple + " (game, Cleanroom binpatch)", part.substring(eq + 1).equals(found),
                            found == null ? "not computed" : found.substring(0, Math.min(12, found.length())));
                }
            }
        }
    }

    /** A mod class in Opt.vanilla (ReflectionField, in RenderLib and RLTweaker): the same print, without access modifiers, from each jar that has it. */
    private static void modClassInGameLock(String lock, String internal, String spec) throws IOException {
        String resource = internal + ".class";
        String simple = internal.substring(internal.lastIndexOf('/') + 1);
        List<File> jars = index.get(resource);
        if (jars == null) {
            absent++;
            rows.add(new String[] { "Gambiarra", lock, simple, "absent", "target mod not in the mods folder" });
            return;
        }
        for (File jar : jars) {
            byte[] bytes = JarUtil.readEntry(jar, resource);
            for (String part : spec.split(",")) {
                int eq = part.indexOf('=');
                String method = part.substring(0, eq);
                String found = CodeLock.vanillaPrint(bytes, method);
                result("Gambiarra", lock, simple + "#" + method + " (" + jar.getName() + ")", part.substring(eq + 1).equals(found),
                        found == null ? "missing" : found.substring(0, 12));
            }
        }
    }

    private static Map<String, String> runVanillaPrints(String[] args) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, "UTF-8"));
        try {
            Class.forName("VanillaPrints").getMethod("main", String[].class).invoke(null, (Object) args);
        } finally {
            System.setOut(out);
        }
        Map<String, String> prints = new HashMap<>();
        for (String line : new String(buffer.toByteArray(), StandardCharsets.UTF_8).split("\\R")) {
            Matcher m = PRINT.matcher(line.trim());
            if (m.matches()) {
                prints.put(m.group(1) + "#" + m.group(2), m.group(3));
            }
        }
        return prints;
    }

    private static void checkOptiRefine(String mod, Class<?> fingerprint, File built) throws Exception {
        java.lang.reflect.Method compute = fingerprint.getMethod("compute", File.class);
        for (String[] known : (String[][]) get(fingerprint, "KNOWN")) {
            File jar = new File(built, "OptiRefine-" + known[1].substring(0, 7) + ".jar");
            if (!jar.isFile()) {
                result(mod, "OptiRefine " + known[0], jar.getName(), false, "not built: run ./gradlew buildOptiRefine");
                continue;
            }
            String found = (String) compute.invoke(null, jar);
            result(mod, "OptiRefine " + known[0], jar.getName(), known[2].equals(found), "canonical " + found.substring(0, 12));
        }
        String json = (String) get(fingerprint, "MIXIN_JSON");
        List<File> jars = index.get(json);
        if (jars == null) {
            absent++;
            rows.add(new String[] { mod, "OptiRefine in the mods folder", json, "absent", "OptiRefine not in the mods folder" });
            return;
        }
        for (File jar : jars) {
            String found = (String) compute.invoke(null, jar);
            String build = (String) fingerprint.getMethod("buildOf", String.class).invoke(null, found);
            result(mod, "OptiRefine in the mods folder", jar.getName(), build != null,
                    (build != null ? build + ", " : "unknown build, ") + "canonical " + found.substring(0, 12));
        }
    }

    /**
     * fixOptiRefineVintageFix: VintageFix's mixin class by SHA-256 in the mods folder; OptiRefine's from the two built jars (the release has
     * to match the lock and inject into resourceExists; 78ff8ce must not inject there, so the fix stays "not needed" with it).
     */
    private static void checkVintageFixFix(File built) throws Exception {
        Class<?> fix = Class.forName("nthcompat.optirefine.ResourceProbePriorityFix");
        String[][] classes = (String[][]) get(fix, "CLASSES");
        String lock = "fixOptiRefineVintageFix";
        tables("Gambiarra", lock + " (VintageFix)", new String[][] { classes[1] });
        java.lang.reflect.Method injectsInto = fix.getDeclaredMethod("injectsInto", byte[].class, String[].class);
        injectsInto.setAccessible(true);
        String[] methods = { "resourceExists", "func_110589_b" };
        String[][] builds = { { "OptiRefine-6c4855e.jar", "true" }, { "OptiRefine-78ff8ce.jar", "false" } };
        for (String[] b : builds) {
            File jar = new File(built, b[0]);
            byte[] bytes = jar.isFile() ? nthshaders.common.JarUtil.readEntry(jar, classes[0][0]) : null;
            if (bytes == null) {
                result("Gambiarra", lock + " (OptiRefine)", b[0], false, "not built: run ./gradlew buildOptiRefine");
                continue;
            }
            boolean injects = (Boolean) injectsInto.invoke(null, bytes, methods);
            boolean ok = injects == Boolean.parseBoolean(b[1]);
            String sha = nthshaders.common.JarUtil.sha256Hex(bytes);
            if (injects) {
                ok &= classes[0][1].equals(sha);
            }
            result("Gambiarra", lock + " (OptiRefine)", b[0], ok, (injects ? "injects into resourceExists, " : "no injection there, ")
                    + "sha256 " + sha.substring(0, 12));
        }
    }

    /**
     * fixOptiRefineDynamicTextureData: OptiRefine 78ff8ce's MixinDynamicTexture is the one locked (the fix acts), and v0.1.0-alpha's is
     * a different class (it already gives every DynamicTexture the larger array: the fix is "not needed" there).
     */
    private static void checkDynamicTextureFix(File built) throws Exception {
        Class<?> fix = Class.forName("nthcompat.optirefine.DynamicTextureDataFix");
        String[] locked = (String[]) get(fix, "CLASS");
        String lock = "fixOptiRefineDynamicTextureData (OptiRefine)";
        String[][] builds = { { "OptiRefine-78ff8ce.jar", "true" }, { "OptiRefine-6c4855e.jar", "false" } };
        for (String[] b : builds) {
            File jar = new File(built, b[0]);
            byte[] bytes = jar.isFile() ? nthshaders.common.JarUtil.readEntry(jar, locked[0]) : null;
            if (bytes == null) {
                result("Gambiarra", lock, b[0], false, "not built: run ./gradlew buildOptiRefine");
                continue;
            }
            String sha = nthshaders.common.JarUtil.sha256Hex(bytes);
            boolean same = locked[1].equals(sha);
            result("Gambiarra", lock, b[0], same == Boolean.parseBoolean(b[1]),
                    (same ? "the locked class (fix active), " : "another class (fix off), ") + "sha256 " + sha.substring(0, 12));
        }
    }

    private static void result(String mod, String lock, String target, boolean ok, String found) {
        if (!ok) {
            failures++;
        }
        rows.add(new String[] { mod, lock, target, ok ? "OK" : "DOES NOT MATCH", found });
    }

    private static void report(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("| Mod | Lock | Class or method | Result | Found |\n|---|---|---|---|---|\n");
        for (String[] r : rows) {
            sb.append("| ").append(String.join(" | ", r)).append(" |\n");
        }
        sb.append("\n").append(rows.size()).append(" checks: ").append(rows.size() - failures - absent).append(" OK, ").append(failures)
                .append(" do not match, ").append(absent).append(" absent.\n");
        System.out.print(sb);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A field of an object or a static field of a class, private or not. */
    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target instanceof Class ? null : target);
            } catch (NoSuchFieldException e) {
                // an enum constant with a body is a subclass: the field is in the enum itself
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

}
