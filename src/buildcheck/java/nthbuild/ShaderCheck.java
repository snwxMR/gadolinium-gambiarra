// SPDX-License-Identifier: LGPL-3.0-only
package nthbuild;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nthshaders.gpu.ShaderSource;
import nthshaders.render.ShaderRewriter;

/**
 * Build check (task checkShaders): every shader file in the two jars goes through the same line insertion the mod does at run time
 * (ShaderSource.variants), and #version must be the first statement, exactly once, in each result. ShaderRewriter, which inserts a
 * declaration into the shader pack's vertex shaders, is run on sample sources with comments and #extension lines before the code.
 */
public final class ShaderCheck {

    private static final List<String> EXTENSIONS = Arrays.asList(".comp", ".vsh", ".fsh", ".glsl", ".vert", ".frag", ".geom");
    private static int failures;

    private ShaderCheck() {
    }

    public static void main(String[] args) throws IOException {
        List<File> files = new ArrayList<>();
        for (String root : args) {
            collect(new File(root), files);
        }
        List<String> compiled = new ArrayList<>(Arrays.asList(ShaderSource.FILES));
        for (File f : files) {
            String source = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] variants = ShaderSource.variants(f.getName(), source);
            boolean known = compiled.remove(f.getName());
            for (int i = 0; i < variants.length; i++) {
                String label = f.getName() + (variants.length > 1 ? " (variant " + i + ")" : "") + (known ? "" : " (not compiled by GpuPrograms)");
                check(label, variants[i]);
            }
        }
        for (String missing : compiled) {
            fail(missing + ": GpuPrograms compiles it, but the file is not in the resources");
        }
        rewriter("no comments", "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        rewriter("license line first", "// SPDX-License-Identifier: MIT\n#version 330 compatibility\nin vec3 a;\nvoid main() { gl_Position = gl_Vertex; }\n");
        rewriter("block comment and #extension", "/* pack header\n   #version 999 inside a comment */\n#version 120\n#define X 1\n"
                + "#extension GL_EXT_gpu_shader4 : enable\nvoid main() { gl_Position = ftransform(); }\n");
        rewriter("#extension inside #if", "#version 130\n#if 1\n#extension GL_ARB_shader_texture_lod : enable\n#endif\n"
                + "void main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }\n");
        if (failures > 0) {
            System.err.println("checkShaders: " + failures + " problem(s)");
            System.exit(1);
        }
        System.out.println("checkShaders: " + files.size() + " shader files and 4 rewriter samples, all with #version first");
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        Arrays.sort(list);
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f, out);
            } else {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && EXTENSIONS.contains(name.substring(dot))) {
                    out.add(f);
                }
            }
        }
    }

    private static void check(String label, String source) {
        String first = ShaderSource.firstStatement(source);
        int count = ShaderSource.versionCount(source);
        if (!first.startsWith("#") || !first.substring(1).trim().startsWith("version")) {
            fail(label + ": the first statement is '" + first + "', not #version");
        } else if (count != 1) {
            fail(label + ": " + count + " #version directives");
        } else {
            System.out.println("OK   " + label + ": " + first);
        }
    }

    private static void rewriter(String label, String source) {
        ShaderRewriter.Result r = ShaderRewriter.rewrite(source);
        String out = r.source;
        check("ShaderRewriter, " + label, out);
        int declaration = out.indexOf(r.declaration);
        int lastExtension = out.lastIndexOf("#extension");
        if (declaration < 0) {
            fail("ShaderRewriter, " + label + ": the declaration is missing");
        } else if (lastExtension > declaration) {
            fail("ShaderRewriter, " + label + ": the declaration comes before an #extension");
        }
    }

    private static void fail(String what) {
        failures++;
        System.out.println("FAIL " + what);
    }

}
