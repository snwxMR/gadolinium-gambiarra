// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

/**
 * How the quads of a section's opaque layer ended up after FaceSorter: where each direction group starts and the outermost
 * plane of each axis group. Exactly what goes to the GPU (8 words per layer, see cull.comp).
 *
 * Groups, in this order: UP, NORTH, WEST, ANY, EAST, SOUTH, DOWN. With this order, opposite directions sit on either side of
 * ANY, and those the camera can see form at most 4 continuous runs.
 *
 * words[0..5] = first vertex of groups 1 to 6 (from the layer's first vertex; group 0 starts at 0 and the last ends at
 * total). words[6] = planes of UP, NORTH, WEST and EAST; words[7] = planes of SOUTH and DOWN (signed 8 bits each, in 1/8
 * block, relative to the section corner).
 *
 * "Minimum" planes (UP, EAST, SOUTH: the face points to +axis, the group shows if the camera is above the lowest plane) round
 * down; -128 = always visible. "Maximum" planes (NORTH, WEST, DOWN) round up; 127 = always visible. Rounding only makes the
 * test looser, it never cuts more.
 *
 * Derived from Aetherium (https://github.com/entitybrian69-bit/aetherium), LGPL-3.0.
 */
public final class FaceLayout {

    public static final int UP = 0;
    public static final int NORTH = 1;
    public static final int WEST = 2;
    public static final int ANY = 3;
    public static final int EAST = 4;
    public static final int SOUTH = 5;
    public static final int DOWN = 6;
    public static final int GROUPS = 7;
    public static final int WORDS = 8;

    /** Planes that never cut anything (no layout: everything in ANY). */
    static final int ALWAYS_PLANES_0 = (0x80) | (0x7F << 8) | (0x7F << 16) | (0x80 << 24);
    static final int ALWAYS_PLANES_1 = (0x80) | (0x7F << 8);

    /** Vertices of the layer (the same as the VBO part; another value = layout of another mesh, not valid). */
    final int total;
    final int[] words;

    FaceLayout(int total, int[] words) {
        this.total = total;
        this.words = words;
    }

    /** First vertex of group g (0..7; 7 = total). */
    int start(int g) {
        return g == 0 ? 0 : g >= GROUPS ? total : words[g - 1];
    }

    /** No layout: everything in ANY (a single run). */
    static void writeAllAny(int[] out, int offset, int count) {
        out[offset] = 0;
        out[offset + 1] = 0;
        out[offset + 2] = 0;
        out[offset + 3] = count;
        out[offset + 4] = count;
        out[offset + 5] = count;
        out[offset + 6] = ALWAYS_PLANES_0;
        out[offset + 7] = ALWAYS_PLANES_1;
    }

    static boolean isMinPlane(int group) {
        return group == UP || group == EAST || group == SOUTH;
    }

    /** Axis of the group: 0 = x, 1 = y, 2 = z. */
    static int axisOf(int group) {
        switch (group) {
            case UP:
            case DOWN:
                return 1;
            case EAST:
            case WEST:
                return 0;
            default:
                return 2;
        }
    }

    /** Plane in 8 bits rounded towards drawing (see the class comment). */
    static int quantize(int group, float plane) {
        if (isMinPlane(group)) {
            if (!(plane > -16.0F)) {
                return -128;
            }
            return Math.min(127, (int) Math.floor(plane * 8.0F));
        }
        if (!(plane < 15.75F)) {
            return 127;
        }
        return Math.max(-128, (int) Math.ceil(plane * 8.0F));
    }

    /** Byte of each axis group within words 6 and 7. */
    static int planeShift(int group) {
        switch (group) {
            case UP:
                return 0;
            case NORTH:
                return 8;
            case WEST:
                return 16;
            case EAST:
                return 24;
            case SOUTH:
                return 32;
            default:
                return 40;
        }
    }

}
