// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import nthshaders.common.AddonVersion;

/**
 * GPU info in the log and the diagnostics file to attach to issues (logs/gadolinium-diagnostics.txt).
 *
 * The file is rewritten on every event that matters (chunk renderer created, fallback to GL15) with: versions, GPU/driver,
 * system, the state of the addon and of the compatibility fixes (the ones here and, if installed, Gambiarra's), the mod
 * list. It carries nothing personal (user name, paths outside the instance).
 */
public final class Diagnostics {

    public static final String FILE_NAME = "gadolinium-diagnostics.txt";

    private static volatile String gpuVendor;
    private static volatile String gpuRenderer;
    private static volatile String glVersion;
    /** Session events, oldest to newest (the last MAX_EVENTS), repeated on every write. */
    private static final java.util.Deque<String> events = new java.util.ArrayDeque<>();
    private static final int MAX_EVENTS = 20;

    private Diagnostics() {
    }

    /** On the client thread, with the OpenGL context ready: reads and logs the GPU once. */
    public static void captureGpu() {
        if (gpuVendor != null) {
            return;
        }
        try {
            gpuVendor = GL11.glGetString(GL11.GL_VENDOR);
            gpuRenderer = GL11.glGetString(GL11.GL_RENDERER);
            glVersion = GL11.glGetString(GL11.GL_VERSION);
            Status.LOG.info("[gadolinium] GPU: vendor='{}', renderer='{}', OpenGL/driver='{}'", gpuVendor, gpuRenderer, glVersion);
        } catch (Throwable t) {
            gpuVendor = "?";
            Status.LOG.warn("[gadolinium] Could not read the GPU", t);
        }
    }

    public static String gpu() {
        return gpuVendor + " | " + gpuRenderer + " | " + glVersion;
    }

    /** Rewrites the diagnostics file. Never throws. */
    public static synchronized void write(String event) {
        try {
            File home = Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
            File dir = new File(home, "logs");
            dir.mkdirs();
            File file = new File(dir, FILE_NAME);
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            events.addLast(now.substring(11) + "  " + event);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Gadolinium ").append(AddonVersion.describe()).append(" - diagnostics\n");
            sb.append("Generated: ").append(now).append("\n");
            sb.append("Event: ").append(event).append("\n\n");
            sb.append("[Session events]\n");
            for (String e : events) {
                sb.append(e).append("\n");
            }
            sb.append("\n");

            sb.append("[System]\n");
            sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor"))
                    .append(")\n");
            sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ')
                    .append(System.getProperty("os.arch")).append("\n");
            sb.append("GPU: ").append(gpu()).append("\n\n");

            sb.append("[Gadolinium: shader mode]\n");
            append(sb, Status.describe());
            sb.append("\n[Gadolinium: compatibility fixes]\n");
            append(sb, nthshaders.compat.ShadersCompat.describe());
            sb.append("\n[Gambiarra: render]\n");
            append(sb, gambiarraDescribe());

            sb.append("\n[Mods]\n");
            try {
                for (ModContainer mod : Loader.instance().getActiveModList()) {
                    sb.append(mod.getModId()).append(' ').append(mod.getVersion()).append(" (")
                            .append(mod.getSource() != null ? mod.getSource().getName() : "?").append(")\n");
                }
            } catch (Throwable t) {
                sb.append("(list unavailable: ").append(t).append(")\n");
            }

            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            Status.LOG.warn("[gadolinium] Could not write logs/{}", FILE_NAME, e);
        }
    }

    /** Gambiarra is another mod (optional): read by reflection, if it exists. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> gambiarraDescribe() {
        try {
            Method m = Class.forName("nthcompat.CompatStatus").getMethod("describe");
            return (Map<String, Object>) m.invoke(null);
        } catch (Throwable t) {
            return java.util.Collections.singletonMap("unavailable", t instanceof ClassNotFoundException ? "Gambiarra not installed" : t.toString());
        }
    }

    private static void append(StringBuilder sb, Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
    }

}
