// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel;
import com.github.alexthe666.iceandfire.entity.EntityDragonBase;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import nthcompat.CompatConfig;

/**
 * distantDragonAnimation (off by default), and LOD level 2 on Tabula models (gambiarra.lod.Lod.animationEvery: any Ice and Fire Tabula
 * creature, by on-screen size). The distant one: a dragon farther than distantDragonAnimationDistance blocks from the camera only
 * recomputes its pose (the Tabula model's setRotationAngles) every distantDragonAnimationEveryFrames frames; on the others, its last
 * pose is put back on the model parts (TabulaModelDistantAnimationMixin).
 *
 * The model is shared by all dragons of the same type: that is why the pose is stored per entity (all model parts: rotation, rotation
 * point, offset, scale, opacity, hidden/visible). Within one frame (shadow pass and main pass) the pose is the same. Client only: AI,
 * collision and hitbox do not pass through here.
 */
public final class DistantDragonAnimation {

    private DistantDragonAnimation() {
    }

    private static final int VALUES = 13;
    private static final int PURGE_FRAMES = 600;

    private static final class Pose {
        IceAndFireTabulaModel model;
        int computedFrame;
        int lastSeen;
        float[] values;
        boolean[] flags;
    }

    private static final Int2ObjectMap<Pose> poses = new Int2ObjectOpenHashMap<>();
    private static int lastPurge;
    private static boolean lastFar;
    static long computed;
    static long reused;
    /** Decisions where N came from the LOD (level 2), not from the distance. */
    static long lodPoses;

    /**
     * Before setRotationAngles: true = this dragon's stored pose was put back (the original does not run this frame).
     */
    public static boolean reuse(IceAndFireTabulaModel model, Entity entity) {
        lastFar = false;
        int every = 0;
        if (CompatConfig.iceAndFireDistantDragonAnimation && entity instanceof EntityDragonBase) {
            Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
            double distance = CompatConfig.iceAndFireDistantDragonDistance;
            if (camera != null && entity.getDistanceSq(camera.posX, camera.posY, camera.posZ) >= distance * distance) {
                every = CompatConfig.iceAndFireDistantDragonEveryFrames;
            }
        }
        // LOD level 2: any Ice and Fire Tabula creature, by on-screen size; 1 = only the shadow and main pass of the same frame share the pose.
        int lod = gambiarra.lod.Lod.animationEvery(entity);
        if (lod > every) {
            every = lod;
            lodPoses++;
        }
        if (every <= 0) {
            return false;
        }
        lastFar = true;
        int frame = IafFrames.frame;
        purge(frame);
        Pose p = poses.get(entity.getEntityId());
        if (p == null || p.model != model) {
            return false;
        }
        p.lastSeen = frame;
        if (frame - p.computedFrame >= every) {
            return false;
        }
        restore(model, p);
        reused++;
        return true;
    }

    /** After a distant dragon's setRotationAngles: stores the computed pose. */
    public static void afterCompute(IceAndFireTabulaModel model, Entity entity) {
        if (!lastFar) {
            return;
        }
        lastFar = false;
        int frame = IafFrames.frame;
        Pose p = poses.get(entity.getEntityId());
        if (p == null) {
            p = new Pose();
            poses.put(entity.getEntityId(), p);
        }
        p.model = model;
        p.computedFrame = frame;
        p.lastSeen = frame;
        save(model, p);
        computed++;
    }

    private static void save(IceAndFireTabulaModel model, Pose p) {
        List<ModelRenderer> boxes = ((net.minecraft.client.model.ModelBase) model).boxList;
        int n = boxes.size();
        if (p.values == null || p.values.length != n * VALUES) {
            p.values = new float[n * VALUES];
            p.flags = new boolean[n * 2];
        }
        float[] v = p.values;
        boolean[] f = p.flags;
        for (int i = 0; i < n; i++) {
            ModelRenderer b = boxes.get(i);
            int o = i * VALUES;
            v[o] = b.rotateAngleX;
            v[o + 1] = b.rotateAngleY;
            v[o + 2] = b.rotateAngleZ;
            v[o + 3] = b.rotationPointX;
            v[o + 4] = b.rotationPointY;
            v[o + 5] = b.rotationPointZ;
            v[o + 6] = b.offsetX;
            v[o + 7] = b.offsetY;
            v[o + 8] = b.offsetZ;
            if (b instanceof AdvancedModelRenderer) {
                AdvancedModelRenderer a = (AdvancedModelRenderer) b;
                v[o + 9] = a.scaleX;
                v[o + 10] = a.scaleY;
                v[o + 11] = a.scaleZ;
                v[o + 12] = a.opacity;
            }
            f[i * 2] = b.isHidden;
            f[i * 2 + 1] = b.showModel;
        }
    }

    private static void restore(IceAndFireTabulaModel model, Pose p) {
        List<ModelRenderer> boxes = ((net.minecraft.client.model.ModelBase) model).boxList;
        int n = Math.min(boxes.size(), p.values.length / VALUES);
        float[] v = p.values;
        boolean[] f = p.flags;
        for (int i = 0; i < n; i++) {
            ModelRenderer b = boxes.get(i);
            int o = i * VALUES;
            b.rotateAngleX = v[o];
            b.rotateAngleY = v[o + 1];
            b.rotateAngleZ = v[o + 2];
            b.rotationPointX = v[o + 3];
            b.rotationPointY = v[o + 4];
            b.rotationPointZ = v[o + 5];
            b.offsetX = v[o + 6];
            b.offsetY = v[o + 7];
            b.offsetZ = v[o + 8];
            if (b instanceof AdvancedModelRenderer) {
                AdvancedModelRenderer a = (AdvancedModelRenderer) b;
                a.scaleX = v[o + 9];
                a.scaleY = v[o + 10];
                a.scaleZ = v[o + 11];
                a.opacity = v[o + 12];
            }
            b.isHidden = f[i * 2];
            b.showModel = f[i * 2 + 1];
        }
    }

    /** Drops poses of dragons not seen for PURGE_FRAMES frames (unloaded, dead, near the camera). */
    private static void purge(int frame) {
        if (frame - lastPurge < PURGE_FRAMES) {
            return;
        }
        lastPurge = frame;
        poses.int2ObjectEntrySet().removeIf(e -> frame - e.getValue().lastSeen > PURGE_FRAMES);
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("posesComputed", computed);
        m.put("posesRepeated", reused);
        m.put("dragonsStored", poses.size());
        m.put("lodDecisions", lodPoses);
        return m;
    }

}
