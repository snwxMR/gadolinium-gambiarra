// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

/**
 * Per-direction face culling (gpuFaceCulling), build side: reorders the quads of an opaque layer into 7 groups (UP, NORTH,
 * WEST, ANY, EAST, SOUTH, DOWN; see FaceLayout). The same vertices, just in another order: the mesh stays valid for any path
 * that draws it whole.
 *
 * A quad only goes into an axis group if the geometry proves it: its 4 vertices share the coordinate on exactly one axis
 * (a plane perpendicular to it) and the winding (the vertex order, which decides the front face for OpenGL face culling)
 * points to one side. With shaders, the normal OptiFine writes must also point to the same side, and the blocks in the
 * pack's block.properties (those the pack may move in the vertex shader, like leaves and plants in the wind) go to ANY. The
 * rest (X-shaped plants, slanted models, degenerate quads) goes to ANY, which is always drawn.
 *
 * Runs on Nothirium's build threads. Derived from Aetherium's CompactTerrain (LGPL-3.0,
 * https://github.com/entitybrian69-bit/aetherium), adapted to Nothirium's 28-byte format and OptiFine's 56-byte format with
 * shaders.
 */
final class FaceSorter {

    /** Nothirium's vertex without shaders (DefaultVertexFormats.BLOCK): position, colour, uv, light. */
    static final int STRIDE_VANILLA = 28;
    /** OptiFine's vertex with shaders: + normal (28), midTexCoord, tangent and mc_Entity (48). */
    static final int STRIDE_SHADERS = 56;
    private static final int NORMAL_OFFSET = 28;
    private static final int ENTITY_OFFSET = 48;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        byte[] groupOf = new byte[4096];
        ByteBuffer out = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder());

        byte[] groups(int quads) {
            if (groupOf.length < quads) {
                groupOf = new byte[Math.max(quads, groupOf.length * 2)];
            }
            return groupOf;
        }

        ByteBuffer output(int bytes) {
            if (out.capacity() < bytes) {
                int capacity = out.capacity();
                while (capacity < bytes) {
                    capacity *= 2;
                }
                out = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            }
            return out;
        }
    }

    /** Count per group from the last call (F3 statistics). */
    static final class Result {
        FaceLayout layout;
        final int[] quads = new int[FaceLayout.GROUPS];
    }

    private FaceSorter() {
    }

    /**
     * Reorders data[0, vertices * stride) in place.
     *
     * @param waving mc_Entity ids that go to ANY (shaders only; null = none)
     * @return false = data untouched (unknown format, incomplete quads, nothing outside ANY)
     */
    static boolean sort(ByteBuffer data, int vertices, int stride, BitSet waving, Result result) {
        result.layout = null;
        java.util.Arrays.fill(result.quads, 0);
        if (stride != STRIDE_VANILLA && stride != STRIDE_SHADERS || vertices <= 0 || (vertices & 3) != 0) {
            return false;
        }
        ByteBuffer src = data.duplicate().order(ByteOrder.nativeOrder());
        int quads = vertices / 4;
        int quadBytes = stride * 4;
        if (src.limit() < quads * quadBytes) {
            return false;
        }
        boolean shaders = stride == STRIDE_SHADERS;
        Scratch scratch = SCRATCH.get();
        byte[] groupOf = scratch.groups(quads);
        int[] count = result.quads;
        float[] plane = new float[FaceLayout.GROUPS];
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            plane[g] = FaceLayout.isMinPlane(g) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }
        for (int q = 0; q < quads; q++) {
            int quad = q * quadBytes;
            int group = classify(src, quad, stride, shaders, waving);
            groupOf[q] = (byte) group;
            count[group]++;
            if (group != FaceLayout.ANY) {
                float p = src.getFloat(quad + 4 * FaceLayout.axisOf(group));
                plane[group] = FaceLayout.isMinPlane(group) ? Math.min(plane[group], p) : Math.max(plane[group], p);
            }
        }
        if (count[FaceLayout.ANY] == quads) {
            return false;
        }
        int[] start = new int[FaceLayout.GROUPS + 1];
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            start[g + 1] = start[g] + count[g];
        }
        int bytes = quads * quadBytes;
        ByteBuffer out = scratch.output(bytes);
        int[] cursor = new int[FaceLayout.GROUPS];
        System.arraycopy(start, 0, cursor, 0, FaceLayout.GROUPS);
        for (int q = 0; q < quads; q++) {
            int from = q * quadBytes;
            int to = cursor[groupOf[q]]++ * quadBytes;
            for (int i = 0; i < quadBytes; i += 8) {
                out.putLong(to + i, src.getLong(from + i));
            }
        }
        // Everything computed: only now the original mesh is replaced.
        for (int i = 0; i < bytes; i += 8) {
            src.putLong(i, out.getLong(i));
        }
        int[] words = new int[FaceLayout.WORDS];
        for (int g = 1; g < FaceLayout.GROUPS; g++) {
            words[g - 1] = start[g] * 4;
        }
        long planes = 0L;
        for (int g = 0; g < FaceLayout.GROUPS; g++) {
            if (g == FaceLayout.ANY) {
                continue;
            }
            int v;
            if (count[g] == 0) {
                v = FaceLayout.isMinPlane(g) ? -128 : 127;
            } else {
                v = FaceLayout.quantize(g, plane[g]);
            }
            planes |= (long) (v & 0xFF) << FaceLayout.planeShift(g);
        }
        words[6] = (int) planes;
        words[7] = (int) (planes >>> 32);
        result.layout = new FaceLayout(vertices, words);
        return true;
    }

    /** Group of a quad (see the class comment). */
    static int classify(ByteBuffer src, int quad, int stride, boolean shaders, BitSet waving) {
        if (shaders && waving != null && waving.get(src.getShort(quad + ENTITY_OFFSET) & 0xFFFF)) {
            return FaceLayout.ANY;
        }
        float x0 = src.getFloat(quad);
        float y0 = src.getFloat(quad + 4);
        float z0 = src.getFloat(quad + 8);
        boolean flatX = true;
        boolean flatY = true;
        boolean flatZ = true;
        for (int v = 1; v < 4; v++) {
            int at = quad + v * stride;
            flatX &= src.getFloat(at) == x0;
            flatY &= src.getFloat(at + 4) == y0;
            flatZ &= src.getFloat(at + 8) == z0;
        }
        int axis;
        if (flatY && !flatX && !flatZ) {
            axis = 1;
        } else if (flatX && !flatY && !flatZ) {
            axis = 0;
        } else if (flatZ && !flatX && !flatY) {
            axis = 2;
        } else {
            return FaceLayout.ANY;
        }
        float winding = windingAlong(src, quad, stride, axis, 0, 1, 2);
        if (winding == 0.0F) {
            winding = windingAlong(src, quad, stride, axis, 0, 2, 3);
        }
        if (winding == 0.0F || winding != winding) {
            return FaceLayout.ANY;
        }
        boolean positive = winding > 0.0F;
        if (shaders && !normalAgrees(src, quad, stride, axis, positive)) {
            return FaceLayout.ANY;
        }
        switch (axis) {
            case 0:
                return positive ? FaceLayout.EAST : FaceLayout.WEST;
            case 1:
                return positive ? FaceLayout.UP : FaceLayout.DOWN;
            default:
                return positive ? FaceLayout.SOUTH : FaceLayout.NORTH;
        }
    }

    /** OptiFine's normal (3 signed bytes, the same on all 4 vertices) points to the winding's side, dominant on the axis. */
    private static boolean normalAgrees(ByteBuffer src, int quad, int stride, int axis, boolean positive) {
        for (int v = 0; v < 4; v++) {
            int at = quad + v * stride + NORMAL_OFFSET;
            int n = src.get(at + axis);
            int a = src.get(at + (axis + 1) % 3);
            int b = src.get(at + (axis + 2) % 3);
            if (positive ? n <= 0 : n >= 0) {
                return false;
            }
            int m = Math.abs(n);
            if (Math.abs(a) * 4 > m || Math.abs(b) * 4 > m) {
                return false;
            }
        }
        return true;
    }

    /** Component of (b - a) x (c - a) on the axis: positive when the triangle faces +axis (CCW). */
    private static float windingAlong(ByteBuffer src, int quad, int stride, int axis, int a, int b, int c) {
        int ia = quad + a * stride;
        int ib = quad + b * stride;
        int ic = quad + c * stride;
        float ax = src.getFloat(ia);
        float ay = src.getFloat(ia + 4);
        float az = src.getFloat(ia + 8);
        float ux = src.getFloat(ib) - ax;
        float uy = src.getFloat(ib + 4) - ay;
        float uz = src.getFloat(ib + 8) - az;
        float vx = src.getFloat(ic) - ax;
        float vy = src.getFloat(ic + 4) - ay;
        float vz = src.getFloat(ic + 8) - az;
        switch (axis) {
            case 0:
                return uy * vz - uz * vy;
            case 1:
                return uz * vx - ux * vz;
            default:
                return ux * vy - uy * vx;
        }
    }

}
