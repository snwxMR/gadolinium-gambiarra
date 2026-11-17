// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import nthshaders.common.JarUtil;
import nthshaders.common.OptiRefineFingerprint;

/**
 * Detects the versions of the three mods the shader part depends on, WITHOUT loading any of their classes: everything is
 * read from the bytes inside the jars. This matters because the mixin plugin runs before the target classes exist, and
 * loading one of them too early would stop its own mixin from being applied.
 */
public final class Compat {

    /** Validated Nothirium version (mcmod.info). */
    public static final String NOTHIRIUM_VERSION = "0.4.9-beta";
    /** Validated OptiFine Config.VERSION. */
    public static final String OPTIFINE_VERSION = "OptiFine_1.12.2_HD_U_G5";
    // Accepted OptiRefine builds: OptiRefineFingerprint.KNOWN (78ff8ce and v0.1.0-alpha).

    static final String NOTHIRIUM_CLASS = "meldexun/nothirium/mc/Nothirium.class";
    static final String OPTIFINE_CLASS = "optifine/OptiFineClassTransformer.class";
    /** This mod's own @Mod class: the same name in the old NothiriumShaders jars (up to 1.0.0-rc7) and in Gadolinium. */
    static final String OWN_CLASS = "nthshaders/NthShaders.class";

    private Compat() {
    }

    /** Nothirium version from the mcmod.info of the jar containing the mod class, or null. */
    @Nullable
    public static String nothiriumVersion() throws IOException {
        return JarUtil.modVersion(NOTHIRIUM_CLASS, "nothirium");
    }

    /** OptiFine's Config.VERSION, read from Config.class in the OptiFine jar, or null. */
    @Nullable
    public static String optiFineVersion() throws IOException {
        File jar = JarUtil.jarOf(OPTIFINE_CLASS);
        if (jar == null) {
            return null;
        }
        byte[] bytes = JarUtil.readEntry(jar, "Config.class");
        if (bytes == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        for (FieldNode field : node.fields) {
            if ("VERSION".equals(field.name) && field.value instanceof String) {
                return (String) field.value;
            }
        }
        return null;
    }

    /**
     * The jars (or folders) that contain this mod's classes. More than one means an old NothiriumShaders jar was left next to
     * Gadolinium: both carry the same classes, and the class loader would mix them.
     */
    public static List<String> copiesOfThisMod() throws IOException {
        Set<String> where = new LinkedHashSet<>();
        Enumeration<URL> urls = Compat.class.getClassLoader().getResources(OWN_CLASS);
        while (urls.hasMoreElements()) {
            String s = urls.nextElement().toString();
            int bang = s.indexOf("!/");
            where.add(bang > 0 ? s.substring(0, bang) : s);
        }
        List<String> names = new ArrayList<>();
        for (String s : where) {
            names.add(s.substring(s.lastIndexOf('/') + 1));
        }
        return names;
    }

    /** Fingerprint of the installed OptiRefine jar, or null if it is not present. */
    @Nullable
    public static String optiRefineFingerprint() throws IOException {
        return OptiRefineFingerprint.ofInstalled();
    }

}
