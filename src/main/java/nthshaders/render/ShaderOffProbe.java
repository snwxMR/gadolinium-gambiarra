// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import nthshaders.core.Status;

/**
 * Diagnostics only, in the log (1.0.0-rc9, widened in rc10). After a shader pack is turned off, the terrain can stay black (with holes)
 * and the item in the hand disappears, until the game restarts. rc9 showed that texture unit 1 does hold the lightmap. Since the hand does
 * not go through Nothirium, the suspect is OpenGL state left over when OptiFine's shaders go away.
 *
 * Once a renderer is created without shaders after shaders were on in this session, it takes two snapshots, 1 s and 5 s later; the
 * first renderer of the session (before any shader pack) gets one reference snapshot 5 s later, to compare with. Each one is taken at
 * the next terrain draw (Nothirium's renderChunks) and at the next hand draw (Forge's RenderHandEvent), and is written as one log line:
 * the current program, the framebuffer and viewport, texture units 0 to 15, the vertex attribute arrays, the write masks, blend,
 * alpha and depth tests, the lightmap (on the GPU and as the game computed it) and, at the terrain, the attribute layout of Nothirium's VAO
 * for that pass.
 *
 * It only reads. The one thing it moves is the active texture unit, to look at each unit, and it goes back as it was through raw GL calls
 * that leave GlStateManager's cache untouched. The texture and the VAO are read without binding them (OpenGL 4.5; without it, that part
 * is skipped). A failure only goes to the log, and the other parts still run.
 *
 * rc10 found it: after shaders off, the lightmap on the GPU is all zeros while the game's copy is right. rc11 adds what tells the causes
 * apart: whether the array the game writes is the one the texture uploads (OptiRefine 78ff8ce swaps it the first time shaders upload
 * the lightmap; Gambiarra's fixOptiRefineDynamicTextureData keeps it), OptiRefine's shaders path for that texture, the texture's level 0
 * on the GPU, the pixel transfer state (pack and unpack) and the OpenGL error of the read. To tell the read's error from someone else's,
 * the errors already pending right before the read are taken (and counted in the line): only in these few snapshots.
 */
public final class ShaderOffProbe {

    private static final long[] AFTER_OFF = { 1000L, 5000L };
    private static final String[] AFTER_OFF_LABELS = { "1 s after shaders off", "5 s after shaders off" };
    /** The reference: the first renderer of the session, before any shader pack. */
    private static final long[] BASELINE = { 5000L };
    private static final String[] BASELINE_LABELS = { "reference, before any shader pack" };

    private static volatile boolean shadersSeen;
    private static boolean baselineArmed;
    private static volatile Object renderer;
    private static volatile long createdAt;
    private static volatile long uninitAt;
    private static long[] delays;
    private static String[] labels;
    private static int next;
    /** A snapshot is due: taken at the next terrain and hand draws. */
    private static volatile boolean due;
    private static long dueSince;
    private static String label;
    private static boolean terrainTaken;
    private static boolean handTaken;

    private ShaderOffProbe() {
    }

    /**
     * RendererSelection, for every renderer created. The first renderer without shaders of the session arms the reference snapshot; a
     * renderer without shaders after shaders were on arms the two after-off snapshots.
     */
    public static void onRendererCreated(Object created, boolean shaderMode) {
        if (shaderMode) {
            shadersSeen = true;
            renderer = null;
            due = false;
            return;
        }
        if (shadersSeen) {
            arm(created, AFTER_OFF, AFTER_OFF_LABELS);
        } else if (!baselineArmed) {
            baselineArmed = true;
            arm(created, BASELINE, BASELINE_LABELS);
        }
    }

    private static void arm(Object created, long[] when, String[] names) {
        delays = when;
        labels = names;
        next = 0;
        due = false;
        uninitAt = 0;
        createdAt = System.currentTimeMillis();
        renderer = created;
    }

    /** ShadersMixin, at the start of OptiFine's Shaders.uninit. */
    public static void onShadersUninit() {
        uninitAt = System.currentTimeMillis();
    }

    /** Client tick: makes the next snapshot due when its time comes. */
    public static void onClientTick() {
        if (renderer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (due) {
            if (now - dueSince > 4000L) {
                // Whatever was not drawn by now (no hand in third person or with F1) is not going to be.
                due = false;
                finishIfLast();
            }
            return;
        }
        if (next < delays.length && now - createdAt >= delays[next]) {
            label = labels[next];
            next++;
            terrainTaken = false;
            handTaken = false;
            dueSince = now;
            due = true;
        }
    }

    /** At the start of Nothirium's renderChunks (the render thread). */
    public static void atTerrain(Object chunkRenderer, Enum<?> pass) {
        if (!due || terrainTaken) {
            return;
        }
        terrainTaken = true;
        take("terrain, " + pass.name(), chunkRenderer, pass);
        finishIfDone();
    }

    /** Forge's RenderHandEvent (the render thread). */
    public static void atHand() {
        if (!due || handTaken) {
            return;
        }
        handTaken = true;
        take("hand", null, null);
        finishIfDone();
    }

    private static void finishIfDone() {
        if (terrainTaken && handTaken) {
            due = false;
            finishIfLast();
        }
    }

    private static void finishIfLast() {
        if (next >= delays.length) {
            renderer = null;
        }
    }

    private static void take(String where, Object chunkRenderer, Enum<?> pass) {
        String when = label;
        StringBuilder sb = new StringBuilder();
        part(sb, "program", ShaderOffProbe::program);
        part(sb, "framebuffer", ShaderOffProbe::framebuffer);
        part(sb, "textures", ShaderOffProbe::textures);
        part(sb, "masks", ShaderOffProbe::masks);
        part(sb, "arrays", ShaderOffProbe::arrays);
        part(sb, "pixel store", ShaderOffProbe::pixelStore);
        part(sb, "lightmap", ShaderOffProbe::lightmap);
        if (chunkRenderer != null) {
            part(sb, "Nothirium", b -> nothirium(b, chunkRenderer, pass));
        }
        long uninit = uninitAt;
        sb.append("; OptiFine's Shaders.uninit ").append(uninit == 0 ? "not seen after the renderer"
                : (uninit - createdAt) + " ms after the renderer");
        Status.LOG.info("[gadolinium] Shader-off probe ({}, {}): {}", when, where, sb);
    }

    private interface Part {
        void read(StringBuilder sb) throws Throwable;
    }

    private static void part(StringBuilder sb, String name, Part part) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        int mark = sb.length();
        try {
            part.read(sb);
        } catch (Throwable t) {
            sb.setLength(mark);
            sb.append(name).append(": failed (").append(t).append(')');
        }
    }

    private static void program(StringBuilder sb) {
        sb.append("program ").append(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        sb.append(" (OptiFine: shaderPackLoaded=").append(net.optifine.shaders.Shaders.shaderPackLoaded)
                .append(", activeProgramID=").append(net.optifine.shaders.Shaders.activeProgramID).append(')');
    }

    private static void framebuffer(StringBuilder sb) {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Minecraft mc = Minecraft.getMinecraft();
        int ours = mc.getFramebuffer() != null ? mc.getFramebuffer().framebufferObject : -1;
        sb.append("framebuffer draw ").append(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)).append(" read ")
                .append(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)).append(" (Minecraft's ").append(ours)
                .append("), viewport ").append(viewport[0]).append(',').append(viewport[1]).append(',').append(viewport[2]).append(',')
                .append(viewport[3]).append(" (display ").append(mc.displayWidth).append('x').append(mc.displayHeight).append(')');
    }

    /** Units 0 to 15: the texture bound to GL_TEXTURE_2D and whether GL_TEXTURE_2D is on (fixed pipeline); only the units in use. */
    private static void textures(StringBuilder sb) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        StringBuilder units = new StringBuilder();
        try {
            for (int u = 0; u < 16; u++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
                int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                boolean on = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                if (bound != 0 || on) {
                    units.append(units.length() > 0 ? " " : "").append('u').append(u).append('=').append(bound).append(on ? "(2D on)" : "(2D off)");
                }
            }
        } finally {
            GL13.glActiveTexture(active);
        }
        sb.append("textures ").append(units.length() > 0 ? units : "none").append(", active unit ").append(active - GL13.GL_TEXTURE0)
                .append(" (GlStateManager's u1=").append(cachedTexture(1)).append(')');
    }

    private static void masks(StringBuilder sb) {
        ByteBuffer color = BufferUtils.createByteBuffer(16);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, color);
        float[] current = new float[4];
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, current);
        sb.append("color mask ").append(flag(color.get(0))).append(flag(color.get(1))).append(flag(color.get(2))).append(flag(color.get(3)))
                .append(", depth mask ").append(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? "on" : "off")
                .append(", depth test ").append(onOff(GL11.GL_DEPTH_TEST)).append(" (func 0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)))
                .append("), blend ").append(onOff(GL11.GL_BLEND)).append(" (0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_SRC)))
                .append("/0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_DST)))
                .append("), alpha test ").append(onOff(GL11.GL_ALPHA_TEST)).append(" (func 0x")
                .append(Integer.toHexString(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC))).append(" ref ").append(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF))
                .append("), cull ").append(onOff(GL11.GL_CULL_FACE)).append(", lighting ").append(onOff(GL11.GL_LIGHTING))
                .append(", fog ").append(onOff(GL11.GL_FOG)).append(", color ").append(current[0]).append(',').append(current[1]).append(',')
                .append(current[2]).append(',').append(current[3]);
    }

    /** The generic attribute arrays enabled in the bound VAO, and the fixed pipeline's client arrays. */
    private static void arrays(StringBuilder sb) {
        int max = Math.min(GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS), 32);
        StringBuilder on = new StringBuilder();
        for (int i = 0; i < max; i++) {
            if (GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0) {
                on.append(on.length() > 0 ? "," : "").append(i);
            }
        }
        sb.append("VAO ").append(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)).append(" generic arrays on [").append(on)
                .append("], client vertex ").append(onOff(GL11.GL_VERTEX_ARRAY)).append(" color ").append(onOff(GL11.GL_COLOR_ARRAY))
                .append(" normal ").append(onOff(GL11.GL_NORMAL_ARRAY)).append(" texcoord ").append(onOff(GL11.GL_TEXTURE_COORD_ARRAY));
    }

    /** The lightmap: its texture, its brightness on the GPU and as the game computed it, and how many texels differ. */
    private static void lightmap(StringBuilder sb) throws IllegalAccessException {
        EntityRenderer er = Minecraft.getMinecraft().entityRenderer;
        DynamicTexture texture = null;
        int[] colors = null;
        Boolean pending = null;
        for (Field f : EntityRenderer.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == DynamicTexture.class && texture == null) {
                f.setAccessible(true);
                texture = (DynamicTexture) f.get(er);
            } else if (f.getType() == boolean.class && ("field_78536_aa".equals(f.getName()) || "lightmapUpdateNeeded".equals(f.getName()))) {
                f.setAccessible(true);
                pending = f.getBoolean(er);
            } else if (f.getType() == int[].class && colors == null) {
                f.setAccessible(true);
                int[] a = (int[]) f.get(er);
                if (a != null && a.length == 256) {
                    colors = a;
                }
            }
        }
        if (texture == null) {
            sb.append("lightmap: not found");
            return;
        }
        int id = texture.getGlTextureId();
        sb.append("lightmap texture ").append(id).append(", game's copy ").append(luma(colors));
        // rc11: OptiRefine 78ff8ce swaps the texture's array for a new one (3 times the size) the first time shaders upload it;
        // the game keeps writing the old one (EntityRenderer.lightmapColors), and the texture uploads the new one.
        int[] data = texture.getTextureData();
        if (colors != null && data != null) {
            sb.append(colors == data ? " (the texture's own array, " + data.length + " ints)"
                    : " (NOT the array the texture uploads: that one has " + data.length + " ints, "
                            + luma(java.util.Arrays.copyOf(data, Math.min(256, data.length))) + ")");
        }
        if (pending != null) {
            sb.append(", update pending ").append(pending);
        }
        sb.append(", ").append(optiRefineState(texture));
        if (!GL.getCapabilities().OpenGL45) {
            sb.append(", GPU copy not read (no OpenGL 4.5)");
            return;
        }
        if (!GL11.glIsTexture(id)) {
            sb.append(", GL: ").append(id).append(" is not a texture");
            return;
        }
        sb.append(", GL: ").append(GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_WIDTH)).append('x')
                .append(GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_HEIGHT)).append(" format 0x")
                .append(Integer.toHexString(GL45.glGetTextureLevelParameteri(id, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)))
                .append(" min filter 0x").append(Integer.toHexString(GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_MIN_FILTER)))
                .append(" levels ").append(GL45.glGetTextureParameteri(id, GL12.GL_TEXTURE_BASE_LEVEL)).append('-')
                .append(GL45.glGetTextureParameteri(id, GL12.GL_TEXTURE_MAX_LEVEL));
        if (GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != 0) {
            // The read would go into that buffer, not into ours.
            sb.append(", GPU copy not read (a pixel pack buffer is bound)");
            return;
        }
        // The errors already pending are someone else's: counted here, so that the one after the read is the read's.
        int before = 0;
        while (GL11.glGetError() != GL11.GL_NO_ERROR && before < 16) {
            before++;
        }
        IntBuffer gpu = BufferUtils.createIntBuffer(256);
        GL45.glGetTextureImage(id, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, gpu);
        int readError = GL11.glGetError();
        int[] onGpu = new int[256];
        gpu.get(onGpu);
        int differ = 0;
        if (colors != null) {
            for (int i = 0; i < 256; i++) {
                if ((onGpu[i] & 0xFFFFFF) != (colors[i] & 0xFFFFFF)) {
                    differ++;
                }
            }
        }
        sb.append(", on the GPU ").append(luma(onGpu)).append(colors != null ? ", " + differ + " of 256 texels differ" : "");
        if (before > 0) {
            sb.append(", ").append(before).append(" OpenGL error(s) from other code were pending");
        }
        if (readError != GL11.GL_NO_ERROR) {
            sb.append(", the read failed (OpenGL error 0x").append(Integer.toHexString(readError)).append(')');
        }
    }

    /** OptiRefine's view of the lightmap: whether it uploads through the shaders path, and the texture's shader textures. */
    private static String optiRefineState(DynamicTexture texture) {
        StringBuilder sb = new StringBuilder("OptiRefine: ");
        try {
            sb.append("isShaders=").append(Class.forName("mods.Hileb.optirefine.optifine.Config").getMethod("isShaders").invoke(null));
        } catch (Throwable t) {
            sb.append("isShaders unknown (").append(t.getClass().getSimpleName()).append(')');
        }
        try {
            for (Field f : DynamicTexture.class.getDeclaredFields()) {
                if (f.getType() == boolean.class && f.getName().contains("shadersInitialized")) {
                    f.setAccessible(true);
                    sb.append(", shadersInitialized=").append(f.getBoolean(texture));
                }
            }
            for (Field f : net.minecraft.client.renderer.texture.AbstractTexture.class.getDeclaredFields()) {
                if (f.getName().contains("multiTex")) {
                    f.setAccessible(true);
                    Object multi = f.get(texture);
                    sb.append(", multiTex ");
                    if (multi == null) {
                        sb.append("none");
                    } else {
                        sb.append("base ").append(intField(multi, "base")).append(" norm ").append(intField(multi, "norm")).append(" spec ")
                                .append(intField(multi, "spec"));
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(", fields unreadable (").append(t.getClass().getSimpleName()).append(')');
        }
        return sb.toString();
    }

    private static int intField(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(owner);
    }

    /** Pixel transfer state: the buffers bound for unpacking (uploads) and packing (reads), and the non-default store modes. */
    private static void pixelStore(StringBuilder sb) {
        sb.append("pixel store: unpack buffer ").append(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)).append(" row ")
                .append(GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)).append(" skip ").append(GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS))
                .append('/').append(GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS)).append(" align ")
                .append(GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)).append(" image height ").append(GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT))
                .append(" skip images ").append(GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES)).append(" swap ")
                .append(GL11.glGetBoolean(GL11.GL_UNPACK_SWAP_BYTES)).append(" lsb ").append(GL11.glGetBoolean(GL11.GL_UNPACK_LSB_FIRST))
                .append("; pack buffer ").append(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)).append(" row ")
                .append(GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH)).append(" skip ").append(GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS))
                .append('/').append(GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS)).append(" align ").append(GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT));
    }

    /** "min/avg/max" brightness (0-255) of ARGB texels. */
    private static String luma(int[] argb) {
        if (argb == null) {
            return "unknown";
        }
        int min = 255;
        int max = 0;
        long sum = 0;
        for (int c : argb) {
            int l = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
            min = Math.min(min, l);
            max = Math.max(max, l);
            sum += l;
        }
        return "brightness " + min + "/" + sum / argb.length + "/" + max;
    }

    /** Nothirium's program and samplers, and the attribute layout of its VAO for this pass (read without binding it). */
    private static void nothirium(StringBuilder sb, Object chunkRenderer, Enum<?> pass) throws ReflectiveOperationException {
        Object shader = field(chunkRenderer, "shader");
        int program = shader != null ? (Integer) shader.getClass().getMethod("getProgram").invoke(shader) : 0;
        sb.append("Nothirium's program ").append(program);
        if (program > 0) {
            sb.append(" u_LightTex=").append(uniform(program, "u_LightTex")).append(" u_BlockTex=").append(uniform(program, "u_BlockTex"));
        }
        Object vaos = field(chunkRenderer, "vaos");
        if (vaos == null || !GL.getCapabilities().OpenGL45) {
            return;
        }
        // Nothirium's MultiObject: get() is the copy update() chose; before the first update() it is null (rc10's NPE). Then the copy
        // at the current index, read from its fields.
        Object perPass = vaos.getClass().getMethod("get").invoke(vaos);
        if (perPass == null) {
            Object values = field(vaos, "values");
            Object index = field(vaos, "index");
            if (values instanceof Object[] && index instanceof Integer && (Integer) index < ((Object[]) values).length) {
                perPass = ((Object[]) values)[(Integer) index];
                sb.append(", VAO copy not chosen yet (index ").append(index).append(')');
            }
        }
        if (perPass == null) {
            sb.append(", no VAO for this pass yet");
            return;
        }
        Method getInt = perPass.getClass().getMethod("getInt", Enum.class);
        int vao = (Integer) getInt.invoke(perPass, pass);
        sb.append(", VAO ").append(vao).append(':');
        for (int i = 0; i < 8; i++) {
            if (GL45.glGetVertexArrayIndexedi(vao, i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0) {
                continue;
            }
            int size = GL45.glGetVertexArrayIndexedi(vao, i, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE);
            int type = GL45.glGetVertexArrayIndexedi(vao, i, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE);
            boolean normalized = GL45.glGetVertexArrayIndexedi(vao, i, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) != 0;
            int stride = GL45.glGetVertexArrayIndexedi(vao, i, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE);
            int divisor = GL45.glGetVertexArrayIndexedi(vao, i, GL43.GL_VERTEX_ATTRIB_ARRAY_DIVISOR);
            long offset = GL45.glGetVertexArrayIndexed64i(vao, i, GL43.GL_VERTEX_BINDING_OFFSET)
                    + GL45.glGetVertexArrayIndexedi(vao, i, GL43.GL_VERTEX_ATTRIB_RELATIVE_OFFSET);
            sb.append(" a").append(i).append('=').append(size).append("x0x").append(Integer.toHexString(type)).append(normalized ? "n" : "")
                    .append(" stride ").append(stride).append(" offset ").append(offset).append(divisor != 0 ? " divisor " + divisor : "");
        }
    }

    private static Object field(Object owner, String name) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (name.equals(f.getName())) {
                    f.setAccessible(true);
                    return f.get(owner);
                }
            }
        }
        return null;
    }

    private static int uniform(int program, String name) {
        int location = GL20.glGetUniformLocation(program, name);
        return location < 0 ? Integer.MIN_VALUE : GL20.glGetUniformi(program, location);
    }

    /** GlStateManager's idea of the texture bound to a unit (textureState[unit].textureName); -2 if it cannot be read. */
    private static int cachedTexture(int unit) {
        try {
            for (Field f : GlStateManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType().isArray()
                        && f.getType().getComponentType().getName().endsWith("$TextureState")) {
                    f.setAccessible(true);
                    Object state = java.lang.reflect.Array.get(f.get(null), unit);
                    for (Field g : state.getClass().getDeclaredFields()) {
                        if (g.getType() == int.class && !Modifier.isStatic(g.getModifiers())) {
                            g.setAccessible(true);
                            return g.getInt(state);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // only diagnostics
        }
        return -2;
    }

    private static String onOff(int cap) {
        return GL11.glIsEnabled(cap) ? "on" : "off";
    }

    private static char flag(byte b) {
        return b != 0 ? 'T' : 'F';
    }

}
