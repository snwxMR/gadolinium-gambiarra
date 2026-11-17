// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

/**
 * The text work on the compute shader sources, without OpenGL, so the build can check it too (ShaderCheck, src/buildcheck): which
 * sources each file gives and where a #define goes. GpuPrograms compiles exactly what this returns.
 */
public final class ShaderSource {

    /** The files GpuPrograms compiles, in assets/nthshaders/gpu. */
    public static final String[] FILES = { "cull.comp", "hiz_init.comp", "hiz_reduce.comp", "depth_copy.comp" };

    /** cull.comp is compiled once per stage, with STAGE defined. */
    public static final int CULL_STAGES = 3;

    private ShaderSource() {
    }

    /** The sources compiled from one file: cull.comp once per stage, the others as they are. */
    public static String[] variants(String file, String source) {
        if (!"cull.comp".equals(file)) {
            return new String[] { source };
        }
        String[] out = new String[CULL_STAGES];
        for (int stage = 0; stage < CULL_STAGES; stage++) {
            out[stage] = withDefine(source, "STAGE " + stage);
        }
        return out;
    }

    /**
     * The #define goes right after the #version directive, which comments (the license line, for example) may precede. GLSL wants
     * #version as the first statement. Without #version, the #define goes at the start.
     */
    public static String withDefine(String source, String define) {
        String line = "#define " + define + "\n";
        int start = firstStatementStart(source);
        if (start < 0 || !isVersion(source, start)) {
            return line + source;
        }
        int newline = source.indexOf('\n', start);
        if (newline < 0) {
            return source + "\n" + line;
        }
        return source.substring(0, newline + 1) + line + source.substring(newline + 1);
    }

    /** The first statement outside comments and blank space (up to the end of its line), or "" if there is none. */
    public static String firstStatement(String source) {
        int start = firstStatementStart(source);
        if (start < 0) {
            return "";
        }
        int newline = source.indexOf('\n', start);
        return source.substring(start, newline < 0 ? source.length() : newline).trim();
    }

    /** How many #version directives there are outside comments. */
    public static int versionCount(String source) {
        String code = withoutComments(source);
        int count = 0;
        for (String line : code.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#") && t.substring(1).trim().startsWith("version")) {
                count++;
            }
        }
        return count;
    }

    private static boolean isVersion(String source, int start) {
        return source.charAt(start) == '#' && source.substring(start + 1).trim().startsWith("version");
    }

    /** Index of the first character outside comments and blank space; -1 if there is none. */
    private static int firstStatementStart(String source) {
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                return i;
            }
        }
        return -1;
    }

    /** The source with comments turned into spaces (line breaks stay, so line numbers stay). */
    private static String withoutComments(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) {
                    sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else {
                sb.append(source.charAt(i++));
            }
        }
        return sb.toString();
    }

}
