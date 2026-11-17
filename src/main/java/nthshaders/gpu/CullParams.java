// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import meldexun.matrixutil.Matrix4f;

/**
 * The "Params" block of cull.comp (std140 layout, 752 bytes), built on the CPU and uploaded once per culling call (the three
 * stages read the same block).
 *
 * Offsets: 0 info (uvec4), 16/32 offset origin (ivec4 + vec4), 48/64 plane origin, 80/96 fog origin (w = squared fog),
 * 112 Hi-Z (vec4), 128 camera matrix (mat4, column by column), 192 planes (vec4[32]), 704/720 per-direction face culling
 * (the camera, ivec4 + vec4; in light mode, the vec4 is the light direction), 736 mode and layers (uvec4).
 */
final class CullParams {

    static final int SIZE = 752;
    static final int MAX_PLANES = 32;

    static final int OFFSET_ORIGIN = 16;
    static final int PLANE_ORIGIN = 48;
    static final int FOG_ORIGIN = 80;
    static final int FACE_ORIGIN = 704;

    /** Per-direction culling: off, by the camera (main pass) or by the light direction (shadow pass). */
    static final int FACES_OFF = 0;
    static final int FACES_CAMERA = 1;
    static final int FACES_LIGHT = 2;

    final ByteBuffer buf = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.nativeOrder());

    void info(int sections, int maxCommands, int mode, int planes) {
        buf.putInt(0, sections);
        buf.putInt(4, maxCommands);
        buf.putInt(8, mode);
        buf.putInt(12, planes);
    }

    /** Origin as a double, split into integer (ivec4) and fractional (vec4) parts: the GPU only does small math. */
    void origin(int offset, double x, double y, double z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        buf.putInt(offset, ix);
        buf.putInt(offset + 4, iy);
        buf.putInt(offset + 8, iz);
        buf.putInt(offset + 12, 0);
        buf.putFloat(offset + 16, (float) (x - ix));
        buf.putFloat(offset + 20, (float) (y - iy));
        buf.putFloat(offset + 24, (float) (z - iz));
    }

    /** Squared fog distance; negative = no fog (the w of the fog origin, offset 108). */
    void fogEndSqr(float value) {
        buf.putFloat(FOG_ORIGIN + 16 + 12, value);
    }

    void hiz(float width, float height, int levels, int groups) {
        buf.putFloat(112, width);
        buf.putFloat(116, height);
        buf.putFloat(120, levels);
        buf.putFloat(124, groups);
    }

    void groups(int groups) {
        buf.putFloat(124, groups);
    }

    /** Camera matrix (RenderLib: mRC = row R, column C), written column by column as GLSL expects. */
    void viewProj(Matrix4f m) {
        float[] v = {
                m.m00, m.m10, m.m20, m.m30,
                m.m01, m.m11, m.m21, m.m31,
                m.m02, m.m12, m.m22, m.m32,
                m.m03, m.m13, m.m23, m.m33 };
        for (int i = 0; i < 16; i++) {
            buf.putFloat(128 + i * 4, v[i]);
        }
    }

    /** Per-direction culling: mode (FACES_*) and opaque layers it applies to (bits 0..2). */
    void faces(int mode, int layers) {
        buf.putInt(736, mode);
        buf.putInt(740, layers);
        buf.putInt(744, 0);
        buf.putInt(748, 0);
    }

    /** Light mode: the unit direction to the light, in place of the fractional part of the origin. */
    void lightDirection(float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            buf.putInt(FACE_ORIGIN + i * 4, 0);
        }
        buf.putFloat(FACE_ORIGIN + 16, x);
        buf.putFloat(FACE_ORIGIN + 20, y);
        buf.putFloat(FACE_ORIGIN + 24, z);
        buf.putFloat(FACE_ORIGIN + 28, 0.0F);
    }

    void plane(int index, float x, float y, float z, float w) {
        int o = 192 + index * 16;
        buf.putFloat(o, x);
        buf.putFloat(o + 4, y);
        buf.putFloat(o + 8, z);
        buf.putFloat(o + 12, w);
    }

}
