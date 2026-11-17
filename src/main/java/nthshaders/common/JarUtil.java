// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads other mods' jars WITHOUT loading their classes.
 *
 * Used by both parts of the addon (nthshaders and nthcompat), which do not depend on each other: this package is the only
 * shared code.
 */
public final class JarUtil {

    private JarUtil() {
    }

    /** Jar (or folder) the class loader would load the resource from, or null. */
    @Nullable
    public static File jarOf(String resource) {
        URL url = JarUtil.class.getClassLoader().getResource(resource);
        if (url == null) {
            return null;
        }
        try {
            String s = url.toString();
            if (s.startsWith("jar:")) {
                // jar:file:/C:/.../Mod.jar!/package/Class.class -> the jar.
                // URI (not URLDecoder) so "+" does not become a space.
                return new File(new URI(s.substring("jar:".length(), s.indexOf("!/"))));
            }
            if (s.startsWith("file:")) {
                // Loose class in a folder (dev environment): goes up to the classpath root.
                File f = new File(url.toURI());
                for (int i = 0; i < resource.split("/").length && f != null; i++) {
                    f = f.getParentFile();
                }
                return f;
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    @Nullable
    public static byte[] readEntry(File jarOrDir, String entry) throws IOException {
        if (jarOrDir.isDirectory()) {
            File f = new File(jarOrDir, entry);
            return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
        }
        try (ZipFile zip = new ZipFile(jarOrDir)) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(e)) {
                return readAll(in);
            }
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** SHA-256 in lowercase hex. */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Version declared in the mcmod.info of the jar containing the resource, for the given modid. */
    @Nullable
    public static String modVersion(String classResource, String modid) throws IOException {
        File jar = jarOf(classResource);
        if (jar == null) {
            return null;
        }
        byte[] info = readEntry(jar, "mcmod.info");
        if (info == null) {
            return null;
        }
        JsonElement root = JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(info), StandardCharsets.UTF_8));
        JsonArray mods = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("modList");
        for (JsonElement e : mods) {
            JsonObject mod = e.getAsJsonObject();
            if (modid.equals(mod.get("modid").getAsString())) {
                return mod.get("version").getAsString();
            }
        }
        return null;
    }

}
