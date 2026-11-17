// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.lycanitesmobs.client.model.ModelCreatureObj;
import com.lycanitesmobs.client.model.ModelObjAnimationFrame;
import com.lycanitesmobs.client.model.ModelObjPart;
import com.lycanitesmobs.client.obj.ObjObject;
import com.lycanitesmobs.client.obj.Vertex;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.entity.Entity;

/**
 * LOD on Lycanites Mobs 2.0.8.10's .obj models (ModelCreatureObjLodMixin). The only LOD class with Lycanites types: only loads from the handlers, with the mod present.
 *  - Level 1: an .obj group (ObjObject) smaller than partPixels on screen is not drawn; the size is the diagonal of its vertices' box, stored per group.
 *  - Level 2: ModelCreatureObj generates, on each draw, the animation frames of all parts (generateAnimationFrames: rotations, offsets and scales in ModelObjPart.animationFrames) and clears them at the end. Every N frames they are generated and
 *    stored per entity; on the others, the stored ones go back to the parts. The model is one for all entities of the same type: that is why the list is stored per entity. The animation frames are new objects on each generation and only read
 *    afterwards, so keeping the reference is enough.
 */
public final class LycanitesLod {

    private LycanitesLod() {
    }

    private static final class Pose {
        Object model;
        int frame;
        ModelObjPart[] parts;
        ModelObjAnimationFrame[][] frames;
    }

    private static final Int2ObjectOpenHashMap<Pose> poses = new Int2ObjectOpenHashMap<>();
    private static final Map<ObjObject, float[]> sizes = new WeakHashMap<>();
    private static final ModelObjAnimationFrame[] NONE = new ModelObjAnimationFrame[0];
    private static boolean saveAfter;
    private static int lastPurge;

    /** Before generateAnimationFrames: true = this entity's stored frames went back (the original does not run). */
    public static boolean reuse(ModelCreatureObj model, Entity entity) {
        saveAfter = false;
        int every = Lod.animationEvery(entity);
        if (every <= 0) {
            return false;
        }
        int frame = Lod.frame();
        if (frame - lastPurge >= 600) {
            lastPurge = frame;
            poses.int2ObjectEntrySet().removeIf(e -> frame - e.getValue().frame > 600);
        }
        saveAfter = true;
        Pose p = poses.get(entity.getEntityId());
        if (p == null || p.model != model || frame - p.frame >= every) {
            return false;
        }
        saveAfter = false;
        for (int i = 0; i < p.parts.length; i++) {
            List<ModelObjAnimationFrame> list = p.parts[i].animationFrames;
            list.clear();
            Collections.addAll(list, p.frames[i]);
        }
        Lod.lycanitesReused++;
        return true;
    }

    /** After generateAnimationFrames of an entity with LOD: stores the frames. */
    public static void afterGenerate(ModelCreatureObj model, Entity entity) {
        if (!saveAfter) {
            return;
        }
        saveAfter = false;
        Pose p = poses.get(entity.getEntityId());
        if (p == null) {
            p = new Pose();
            poses.put(entity.getEntityId(), p);
        }
        Collection<ModelObjPart> parts = model.animationParts.values();
        if (p.model != model || p.parts == null || p.parts.length != parts.size()) {
            p.parts = parts.toArray(new ModelObjPart[0]);
            p.frames = new ModelObjAnimationFrame[p.parts.length][];
        }
        p.model = model;
        p.frame = Lod.frame();
        for (int i = 0; i < p.parts.length; i++) {
            List<ModelObjAnimationFrame> list = p.parts[i].animationFrames;
            p.frames[i] = list.isEmpty() ? NONE : list.toArray(NONE);
        }
        Lod.lycanitesPoses++;
    }

    /** Level 1: true = this group is too small on screen (renderGroup does not run). */
    public static boolean skip(ObjObject group) {
        float[] s = sizes.get(group);
        if (s == null) {
            s = size(group);
            sizes.put(group, s);
        }
        return s[0] > 0 && Lod.skipMeshPart(group, s);
    }

    /** {the diagonal of the vertices' box with 25% slack, minX, minY, minZ, maxX, maxY, maxZ}; {0} = empty. */
    private static float[] size(ObjObject group) {
        if (group.mesh == null || group.mesh.vertices == null || group.mesh.vertices.length == 0) {
            return new float[] { 0 };
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vertex v : group.mesh.vertices) {
            if (v == null || v.getPos() == null) {
                continue;
            }
            float x = v.getPos().x, y = v.getPos().y, z = v.getPos().z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        if (minX > maxX) {
            return new float[] { 0 };
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        // 25% slack, as on vanilla parts (the animations' scales do not enter the math).
        return new float[] { 1.25f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz), minX, minY, minZ, maxX, maxY, maxZ };
    }

}
