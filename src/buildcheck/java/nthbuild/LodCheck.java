// SPDX-License-Identifier: MIT
package nthbuild;

import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

/**
 * Build check (task checkLod): the LOD's part hooks, run outside the game on ModelRenderer trees, with the matrices already read (no OpenGL). The parts
 * are made without their constructors (the compile-time game jar mixes names and its constructors do not run) and get only the fields the LOD reads:
 * the cubes' corners, the children, the rotation points.
 *  1. Measuring (level 3) never hides a part: with level 1 off and the measure on, every hook says "draw".
 *  2. Measuring does not change level 1: the same parts, with the same sizes on screen, get the same answers with the measure on and off.
 *  3. A measure that fails stops measuring and the part is still drawn.
 *  4. The size measured for Ice and Fire's fire dragon (its Tabula model, read from the target mods' jar) stays within twice the model's size at rest
 *     (rc6 measured 30 times too much, and the dragon came out as 465 blocks).
 * Writes build/reports/lod.md and fails if any of them does not hold.
 */
public final class LodCheck {

    private static final String DRAGON_JAR_PREFIX = "iceandfire-";
    private static final String DRAGON_MODEL = "assets/iceandfire/models/tabula/firedragon/dragonFireBaseMale.tbl";

    private static final List<String> report = new ArrayList<>();
    private static int failures;

    private static Class<?> lod, ctxClass, stateClass;

    private LodCheck() {
    }

    public static void main(String[] args) throws Exception {
        File targetMods = new File(args[0]);
        File out = new File(args[1]);
        lod = Class.forName("gambiarra.lod.Lod");
        ctxClass = Class.forName("gambiarra.lod.Lod$Ctx");
        stateClass = Class.forName("gambiarra.lod.Lod$State");
        set(null, "vanillaPartsOk", true);
        set(null, "llibraryPartsOk", true);
        set(null, "lycanitesOk", true);

        List<ModelRenderer> parts = new ArrayList<>();
        ModelRenderer chain = chain(parts, 9);
        float reach = (float) call(Class.forName("gambiarra.lod.PartExtent"), "reach", new Class<?>[] { ModelRenderer.class }, chain);
        // A chain of 9 segments of 8 units along z, each a 2 x 2 x 8 cube: farthest point = 8 x 9 from the root's pivot, plus the last cube's corner (1, 1, 0).
        float expected = 2f * (8f * 8 + (float) Math.sqrt(1 + 1 + 64)) * 1.25f;
        check(Math.abs(reach - expected) < 0.01f, String.format(Locale.ROOT, "reach of a 9-segment chain = %.2f units (expected %.2f)", reach, expected));

        // 1. Measure only: nothing is hidden, even with every part far below 1 pixel.
        Object c = ctx(false, true, 0.0001f);
        int hidden = 0;
        for (ModelRenderer p : parts) {
            hidden += skipVanilla(c, p) ? 1 : 0;
            hidden += skipTabula(c, p) ? 1 : 0;
        }
        hidden += skipMesh(c, new float[] { 3f, -1f, -1f, -1f, 1f, 2f, 1f }) ? 1 : 0;
        check(hidden == 0, "level 1 off, measure on, all parts below 1 px: " + hidden + " of " + (2 * parts.size() + 1) + " hooks said skip (must be 0)");
        float partUnits = getF(c, "partUnits");
        check(Math.abs(partUnits - reach * 0.0625f) < 1e-4f, String.format(Locale.ROOT, "the measure kept the largest part: %.4f (expected %.4f)", partUnits, reach * 0.0625f));
        set(c, "sx", 2f);
        call(lod, "finishMeasure", new Class<?>[] { ctxClass }, c);
        float blocks = getF(get(c, "state"), "modelBlocks");
        float mesh = 1.25f * (float) Math.sqrt(4 + 9 + 4);
        float expectedBlocks = Math.max(reach * 0.0625f, mesh) * 2f;
        check(Math.abs(blocks - expectedBlocks) < 1e-3f, String.format(Locale.ROOT, "model size = largest of the parts and the .obj box, times the modelview scale: %.3f (expected %.3f)", blocks, expectedBlocks));

        // 2. Level 1 on: the same answers with the measure on and off, for sizes on screen from far below to far above the limit.
        for (float pxPerUnit : new float[] { 0.0001f, 0.05f, 0.3f, 1f, 5f, 100f }) {
            Object off = ctx(true, false, pxPerUnit);
            Object on = ctx(true, true, pxPerUnit);
            StringBuilder a = new StringBuilder(), b = new StringBuilder();
            for (int round = 0; round < 3; round++) {
                for (ModelRenderer p : parts) {
                    a.append(skipVanilla(off, p) ? '1' : '0').append(skipTabula(off, p) ? '1' : '0');
                    b.append(skipVanilla(on, p) ? '1' : '0').append(skipTabula(on, p) ? '1' : '0');
                }
            }
            check(a.toString().equals(b.toString()), String.format(Locale.ROOT, "level 1 at %.4f px per unit: measure off %s, on %s", pxPerUnit, a, b));
        }

        // 3. A measure that fails (a null child makes the reach throw): the part is still drawn, and measuring stops.
        ModelRenderer bad = newPart(0, 0, 0);
        cube(bad, 0, 0, 0, 1, 1, 1);
        bad.childModels = new ArrayList<>();
        bad.childModels.add(null);
        Object f = ctx(false, true, 0.0001f);
        boolean skipped = skipVanilla(f, bad);
        boolean stopped = (boolean) get(null, "measureBroken");
        check(!skipped && stopped && !(boolean) get(f, "measure"), "a failing measure: skipped=" + skipped + " (must be false), measuring stopped=" + stopped);
        set(null, "measureBroken", false);

        // 4. The real fire dragon.
        dragon(targetMods);

        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            w.println("# LOD check");
            w.println();
            for (String line : report) {
                w.println("- " + line);
            }
        }
        System.out.println("checkLod: " + (report.size() - failures) + " of " + report.size() + " OK (" + out + ")");
        if (failures > 0) {
            throw new IllegalStateException("checkLod: " + failures + " failed");
        }
    }

    private static void dragon(File targetMods) throws Exception {
        File jar = null;
        File[] files = targetMods.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(DRAGON_JAR_PREFIX) && f.getName().endsWith(".jar")) {
                    jar = f;
                }
            }
        }
        if (jar == null) {
            check(false, "Ice and Fire's jar not found in " + targetMods);
            return;
        }
        JsonObject model;
        try (ZipFile outer = new ZipFile(jar)) {
            File tbl = File.createTempFile("dragon", ".tbl");
            tbl.deleteOnExit();
            java.nio.file.Files.copy(outer.getInputStream(outer.getEntry(DRAGON_MODEL)), tbl.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (ZipFile z = new ZipFile(tbl)) {
                model = JsonParser.parseReader(new InputStreamReader(z.getInputStream(z.getEntry("model.json")), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        float[] rest = null;
        for (JsonElement e : model.getAsJsonArray("cubes")) {
            String name = e.getAsJsonObject().get("name").getAsString();
            ModelRenderer root = part(e.getAsJsonObject());
            rest = restBox(e.getAsJsonObject(), 0, 0, 0, rest);
            Class<?> pe = Class.forName("gambiarra.lod.PartExtent");
            float size = (float) call(pe, "size", new Class<?>[] { ModelRenderer.class }, root);
            // Through the Tabula hook, as in the game: every part of the tree, measure on and level 1 off; what the measure kept, in model units.
            List<ModelRenderer> all = new ArrayList<>();
            walk(root, all);
            Object c = ctx(false, true, 1f);
            int skipped = 0;
            for (ModelRenderer p : all) {
                skipped += skipTabula(c, p) ? 1 : 0;
            }
            float measured = getF(c, "partUnits") / 0.0625f;
            float restDiag = (float) Math.sqrt(sq(rest[3] - rest[0]) + sq(rest[4] - rest[1]) + sq(rest[5] - rest[2]));
            check(skipped == 0 && measured > 0 && measured <= 2f * restDiag, String.format(Locale.ROOT,
                    "fire dragon (%s, %s, %d parts): measured %.1f units, model at rest %.1f units across (at most twice that), %d parts skipped; level 1's size %.1f units (not used to measure)",
                    jar.getName(), name, all.size(), measured, restDiag, skipped, size));
        }
    }

    /** A ModelRenderer from a Tabula cube: rotation point = position, the cube = offset and dimensions, and its children. */
    private static ModelRenderer part(JsonObject c) throws Exception {
        float[] pos = f3(c, "position"), off = f3(c, "offset"), dim = f3(c, "dimensions");
        ModelRenderer p = newPart(pos[0], pos[1], pos[2]);
        cube(p, off[0], off[1], off[2], off[0] + Math.round(dim[0]), off[1] + Math.round(dim[1]), off[2] + Math.round(dim[2]));
        if (c.has("children")) {
            for (JsonElement k : c.getAsJsonArray("children")) {
                child(p, part(k.getAsJsonObject()));
            }
        }
        return p;
    }

    private static Object unsafe;

    /** A ModelRenderer without its constructor: no cubes, no children, the rotation point given. */
    private static ModelRenderer newPart(float x, float y, float z) throws Exception {
        ModelRenderer p = (ModelRenderer) allocate(ModelRenderer.class);
        p.cubeList = new ArrayList<>();
        p.rotationPointX = x;
        p.rotationPointY = y;
        p.rotationPointZ = z;
        return p;
    }

    /** A cube from (x1, y1, z1) to (x2, y2, z2), in the part's units. */
    private static void cube(ModelRenderer p, float x1, float y1, float z1, float x2, float y2, float z2) throws Exception {
        ModelBox b = (ModelBox) allocate(ModelBox.class);
        String[] names = { "posX1", "posY1", "posZ1", "posX2", "posY2", "posZ2" };
        float[] v = { x1, y1, z1, x2, y2, z2 };
        for (int i = 0; i < 6; i++) {
            Field f = ModelBox.class.getDeclaredField(names[i]);
            f.setAccessible(true);
            f.setFloat(b, v[i]);
        }
        p.cubeList.add(b);
    }

    private static void walk(ModelRenderer p, List<ModelRenderer> all) {
        all.add(p);
        if (p.childModels != null) {
            for (ModelRenderer k : p.childModels) {
                walk(k, all);
            }
        }
    }

    private static void child(ModelRenderer parent, ModelRenderer child) {
        if (parent.childModels == null) {
            parent.childModels = new ArrayList<>();
        }
        parent.childModels.add(child);
    }

    private static Object allocate(Class<?> type) throws Exception {
        if (unsafe == null) {
            Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
        }
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    /** The box of all cubes at rest (rotations left out): each part at its parent's pivot plus its own position. */
    private static float[] restBox(JsonObject c, float x, float y, float z, float[] b) {
        float[] pos = f3(c, "position"), off = f3(c, "offset"), dim = f3(c, "dimensions");
        float px = x + pos[0], py = y + pos[1], pz = z + pos[2];
        float[] cube = { px + off[0], py + off[1], pz + off[2], px + off[0] + dim[0], py + off[1] + dim[1], pz + off[2] + dim[2] };
        if (b == null) {
            b = cube;
        } else {
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], cube[i]);
                b[i + 3] = Math.max(b[i + 3], cube[i + 3]);
            }
        }
        if (c.has("children")) {
            for (JsonElement k : c.getAsJsonArray("children")) {
                b = restBox(k.getAsJsonObject(), px, py, pz, b);
            }
        }
        return b;
    }

    private static float[] f3(JsonObject c, String key) {
        JsonArray v = c.getAsJsonArray(key);
        return new float[] { v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat() };
    }

    private static double sq(double v) {
        return v * v;
    }

    /** A straight chain of n parts: each a 2 x 2 x 8 cube, the next one 8 units further along z. */
    private static ModelRenderer chain(List<ModelRenderer> all, int n) throws Exception {
        ModelRenderer root = null, last = null;
        for (int i = 0; i < n; i++) {
            ModelRenderer p = newPart(0, 0, last == null ? 0 : 8);
            cube(p, -1, -1, 0, 1, 1, 8);
            if (last == null) {
                root = p;
            } else {
                child(last, p);
            }
            all.add(p);
            last = p;
        }
        return root;
    }

    /** A drawing context with the matrices already read: pxPerUnit pixels per model unit, modelview scale 1. */
    private static Object ctx(boolean parts, boolean measure, float pxPerUnit) throws Exception {
        Constructor<?> sc = stateClass.getDeclaredConstructor();
        sc.setAccessible(true);
        Constructor<?> cc = ctxClass.getDeclaredConstructor();
        cc.setAccessible(true);
        Object c = cc.newInstance();
        set(c, "state", sc.newInstance());
        set(c, "parts", parts);
        set(c, "measure", measure);
        set(c, "mvRead", true);
        set(c, "pxPerUnit", pxPerUnit);
        set(c, "sx", 1f);
        set(c, "proj5", 1f);
        set(c, "meshMinX", Float.MAX_VALUE);
        set(c, "meshMinY", Float.MAX_VALUE);
        set(c, "meshMinZ", Float.MAX_VALUE);
        set(c, "meshMaxX", -Float.MAX_VALUE);
        set(c, "meshMaxY", -Float.MAX_VALUE);
        set(c, "meshMaxZ", -Float.MAX_VALUE);
        return c;
    }

    private static boolean skipVanilla(Object c, ModelRenderer p) throws Exception {
        set(null, "current", c);
        return (boolean) call(lod, "skipVanillaPart", new Class<?>[] { ModelRenderer.class, float.class }, p, 0.0625f);
    }

    private static boolean skipTabula(Object c, ModelRenderer p) throws Exception {
        set(null, "current", c);
        return (boolean) call(lod, "skipLlibraryPart", new Class<?>[] { ModelRenderer.class, float.class, float.class }, p, 0.0625f, 1f);
    }

    private static boolean skipMesh(Object c, float[] s) throws Exception {
        set(null, "current", c);
        return (boolean) call(lod, "skipMeshPart", new Class<?>[] { Object.class, float[].class }, new Object(), s);
    }

    private static Object call(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Field field(Object o, String name) throws Exception {
        Field f = (o == null ? lod : o.getClass()).getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void set(Object o, String name, Object value) throws Exception {
        field(o, name).set(o, value);
    }

    private static Object get(Object o, String name) throws Exception {
        return field(o, name).get(o);
    }

    private static float getF(Object o, String name) throws Exception {
        return field(o, name).getFloat(o);
    }

    private static void check(boolean ok, String what) {
        report.add((ok ? "OK   " : "FAIL ") + what);
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

}
