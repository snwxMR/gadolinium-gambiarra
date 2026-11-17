// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

/**
 * Level 1: the size (model units) of a part with its children, to compare with 1 pixel: the diagonal of the box joining its cubes and the children's boxes (each child at its rotation point, with a slack of its own size in all directions, because its
 * rotation changes with the pose). The part's position does not enter: a small cube far from the rotation point stays small on screen. Stored per part (with 25% slack) and redone if the number of cubes or children changes.
 * The box grows at each level (a child's box becomes a sphere and the sphere a box again), so on deep models it is far larger than the part: fine for level 1
 * (the part only vanishes later), wrong for measuring a model.
 *
 * Level 3 (Lod.measure): reach, the diameter of the sphere around the part's rotation point that holds the part and its children in any pose, with no
 * boxing between levels. An Ice and Fire fire dragon (9 levels from the body): size 2216 units, reach 73 x 2 (rc6 measured the dragon with size: 465 blocks).
 */
final class PartExtent {

    private static final Map<ModelRenderer, float[]> cache = new WeakHashMap<>();
    private static final float MARGIN = 1.25f;

    private PartExtent() {
    }

    /** The diagonal of the box of the part and its children, with the slack. */
    static float size(ModelRenderer part) {
        int cubes = part.cubeList == null ? 0 : part.cubeList.size();
        int children = part.childModels == null ? 0 : part.childModels.size();
        float[] v = cache.get(part);
        if (v == null || v[1] != cubes || v[2] != children) {
            float[] box = box(part, 0);
            float size = box == null ? 0 : length(box[3] - box[0], box[4] - box[1], box[5] - box[2]);
            v = new float[] { size * MARGIN, cubes, children, Float.NaN };
            cache.put(part, v);
        }
        return v[0];
    }

    /** Level 3: the diameter of the sphere around the rotation point that holds the part and its children in any pose, with the slack. */
    static float reach(ModelRenderer part) {
        size(part);
        float[] v = cache.get(part);
        if (Float.isNaN(v[3])) {
            v[3] = 2f * radius(part, 0) * MARGIN;
        }
        return v[3];
    }

    /** The farthest a point of the part or its children can be from the part's rotation point: rotations keep distances, so this holds for any pose. */
    private static float radius(ModelRenderer part, int depth) {
        float r = 0;
        if (part.cubeList != null) {
            for (ModelBox c : part.cubeList) {
                r = Math.max(r, length(Math.max(Math.abs(c.posX1), Math.abs(c.posX2)), Math.max(Math.abs(c.posY1), Math.abs(c.posY2)),
                        Math.max(Math.abs(c.posZ1), Math.abs(c.posZ2))));
            }
        }
        if (part.childModels != null && depth < 32) {
            for (ModelRenderer child : part.childModels) {
                float px = child.rotationPointX + 16f * child.offsetX;
                float py = child.rotationPointY + 16f * child.offsetY;
                float pz = child.rotationPointZ + 16f * child.offsetZ;
                r = Math.max(r, length(px, py, pz) + radius(child, depth + 1));
            }
        }
        return r;
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} in the part's space (origin at its rotation point); null = empty. */
    private static float[] box(ModelRenderer part, int depth) {
        float[] b = null;
        if (part.cubeList != null) {
            for (ModelBox c : part.cubeList) {
                b = union(b, c.posX1, c.posY1, c.posZ1, c.posX2, c.posY2, c.posZ2);
            }
        }
        if (part.childModels != null && depth < 32) {
            for (ModelRenderer child : part.childModels) {
                float[] cb = box(child, depth + 1);
                if (cb == null) {
                    continue;
                }
                // The child turns around its rotation point: a sphere around that point that covers its box.
                float r = length(Math.max(Math.abs(cb[0]), Math.abs(cb[3])), Math.max(Math.abs(cb[1]), Math.abs(cb[4])),
                        Math.max(Math.abs(cb[2]), Math.abs(cb[5])));
                float px = child.rotationPointX + 16f * child.offsetX;
                float py = child.rotationPointY + 16f * child.offsetY;
                float pz = child.rotationPointZ + 16f * child.offsetZ;
                b = union(b, px - r, py - r, pz - r, px + r, py + r, pz + r);
            }
        }
        return b;
    }

    private static float[] union(float[] b, float x1, float y1, float z1, float x2, float y2, float z2) {
        if (b == null) {
            return new float[] { Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2) };
        }
        b[0] = Math.min(b[0], Math.min(x1, x2));
        b[1] = Math.min(b[1], Math.min(y1, y2));
        b[2] = Math.min(b[2], Math.min(z1, z2));
        b[3] = Math.max(b[3], Math.max(x1, x2));
        b[4] = Math.max(b[4], Math.max(y1, y2));
        b[5] = Math.max(b[5], Math.max(z1, z2));
        return b;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

}
