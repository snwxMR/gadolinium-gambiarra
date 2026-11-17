// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a pack's vertex shader source to add the chunk offset to the vertex position.
 *
 * In GL15, each chunk is drawn with its own glTranslated, and the position the shader reads (gl_Vertex) is relative to the chunk
 * corner. In GL43 all chunks go out in a single draw, with no per-chunk translation; each chunk's offset arrives in a per-draw
 * attribute (nth_ChunkOffset, location 6). The rewrite:
 *  - declares the attribute after the pack's #version and #extension lines (see insertionLine);
 *  - replaces each gl_Vertex (whole word only: gl_VertexID stays) with (gl_Vertex + vec4(nth_ChunkOffset, 0.0));
 *  - replaces ftransform() with the explicit multiplication using the added position.
 * Comments (// and /* *&#47;) stay intact.
 *
 * Outside terrain the attribute array is off and its current value is zero (zeroed after each layer), so the addition changes
 * nothing.
 */
public final class ShaderRewriter {

    public static final String ATTRIBUTE = "nth_ChunkOffset";
    static final String OFFSET_VERTEX = "(gl_Vertex + vec4(" + ATTRIBUTE + ", 0.0))";

    /** gl_Vertex as a whole word, or ftransform(). */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:(gl_Vertex)(?![A-Za-z0-9_])|ftransform\\s*\\(\\s*\\))");
    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)");
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\b");
    private static final Pattern ATTRIBUTE_KEYWORD = Pattern.compile("(?<![A-Za-z0-9_])attribute(?![A-Za-z0-9_])");

    private ShaderRewriter() {
    }

    public static final class Result {
        public final String source;
        public final int vertexReplacements;
        public final int ftransformReplacements;
        public final String declaration;

        Result(String source, int vertexReplacements, int ftransformReplacements, String declaration) {
            this.source = source;
            this.vertexReplacements = vertexReplacements;
            this.ftransformReplacements = ftransformReplacements;
            this.declaration = declaration;
        }

        public int substitutions() {
            return vertexReplacements + ftransformReplacements;
        }
    }

    public static Result rewrite(String source) {
        StringBuilder out = new StringBuilder(source.length() + 256);
        StringBuilder codeOnly = new StringBuilder(source.length());
        int[] counts = new int[2];

        // Separates code from comments; only code is rewritten.
        int i = 0;
        int n = source.length();
        int codeStart = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*')) {
                String code = source.substring(codeStart, i);
                out.append(replace(code, counts));
                codeOnly.append(code);
                int end;
                if (source.charAt(i + 1) == '/') {
                    end = source.indexOf('\n', i);
                    end = end < 0 ? n : end;
                } else {
                    end = source.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                }
                out.append(source, i, end);
                // A comment becomes a space in the analysis text (for the \b of "attribute").
                codeOnly.append(' ');
                i = end;
                codeStart = i;
            } else {
                i++;
            }
        }
        String tail = source.substring(codeStart);
        out.append(replace(tail, counts));
        codeOnly.append(tail);

        String rewritten = out.toString();
        String declaration = declaration(codeOnly.toString());
        return new Result(insertDeclaration(rewritten, declaration), counts[0], counts[1], declaration);
    }

    private static String replace(String code, int[] counts) {
        Matcher m = TOKEN.matcher(code);
        if (!m.find()) {
            return code;
        }
        StringBuffer sb = new StringBuffer(code.length() + 64);
        do {
            String replacement;
            if (m.group(1) != null) {
                counts[0]++;
                replacement = OFFSET_VERTEX;
            } else {
                counts[1]++;
                replacement = "(gl_ModelViewProjectionMatrix * " + OFFSET_VERTEX + ")";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * "attribute" if the shader already uses that word or is older than GLSL 1.30; otherwise "in" (in GLSL 1.30+ "attribute" is
     * deprecated and leaves the core profile).
     */
    private static String declaration(String codeOnly) {
        int version = 110;
        for (String line : codeOnly.split("\n", -1)) {
            Matcher v = VERSION.matcher(line);
            if (v.find()) {
                version = Integer.parseInt(v.group(1));
                break;
            }
        }
        boolean attributeStyle = version < 130 || ATTRIBUTE_KEYWORD.matcher(codeOnly).find();
        return (attributeStyle ? "attribute" : "in") + " vec3 " + ATTRIBUTE + ";";
    }

    /** What remains of the line without comments; comment[0] = inside a block comment. */
    private static String withoutComments(String line, boolean[] comment) {
        StringBuilder sb = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (comment[0]) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    return sb.toString();
                }
                comment[0] = false;
                i = end + 2;
            } else if (line.startsWith("//", i)) {
                return sb.toString();
            } else if (line.startsWith("/*", i)) {
                comment[0] = true;
                i += 2;
            } else {
                sb.append(line.charAt(i++));
            }
        }
        return sb.toString();
    }

    /**
     * Where the declaration goes: right after #version and, if any, the last #extension before the code. The GLSL rule is that
     * #extension comes before anything that is not a directive: NVIDIA tolerates a declaration before it, but stricter compilers (AMD,
     * Mesa) refuse, and OptiFine puts hundreds of #defines between #version and the pack's #extension. An #extension inside #if counts
     * at the #endif closing the outermost block, so the declaration does not vanish along with the block. The search stops at the first
     * code outside #if, or at the first code that already uses the attribute.
     */
    private static int insertionLine(String[] lines, int versionLine) {
        int insertAt = versionLine + 1;
        int depth = 0;
        boolean extensionInBlock = false;
        boolean continued = false;
        boolean[] comment = new boolean[1];
        for (int k = versionLine + 1; k < lines.length; k++) {
            String t = withoutComments(lines[k], comment).trim();
            if (continued) {
                continued = t.endsWith("\\");
                continue;
            }
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                continued = t.endsWith("\\");
                String directive = t.substring(1).trim();
                if (directive.startsWith("if")) {
                    depth++;
                } else if (directive.startsWith("endif")) {
                    depth = Math.max(0, depth - 1);
                    if (depth == 0 && extensionInBlock) {
                        extensionInBlock = false;
                        insertAt = k + 1;
                    }
                } else if (EXTENSION.matcher(t).find()) {
                    if (depth == 0) {
                        insertAt = k + 1;
                    } else {
                        extensionInBlock = true;
                    }
                }
                continue;
            }
            if (depth == 0 || t.contains(ATTRIBUTE)) {
                break;
            }
        }
        return insertAt;
    }

    /** Without #version, the search starts at the first line. */
    private static String insertDeclaration(String source, String declaration) {
        String[] lines = source.split("\n", -1);
        int versionLine = -1;
        for (int k = 0; k < lines.length; k++) {
            if (VERSION.matcher(lines[k]).find()) {
                versionLine = k;
                break;
            }
        }
        int insertAt = insertionLine(lines, versionLine);
        StringBuilder sb = new StringBuilder(source.length() + declaration.length() + 32);
        for (int k = 0; k < lines.length; k++) {
            if (k == insertAt) {
                sb.append(declaration).append(" // gadolinium\n");
            }
            sb.append(lines[k]);
            if (k < lines.length - 1) {
                sb.append('\n');
            }
        }
        if (insertAt >= lines.length) {
            sb.append('\n').append(declaration).append(" // gadolinium\n");
        }
        return sb.toString();
    }

}
