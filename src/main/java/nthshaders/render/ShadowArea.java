// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import java.util.LinkedHashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.optifine.shaders.Iterator3d;
import net.optifine.shaders.Shaders;
import nthshaders.core.NthConfig;

/**
 * GL43 shadow pass: only the sections OptiFine would draw.
 *
 * Without Nothirium, OptiFine draws in the shadow pass only the sections of a volume along the sun direction
 * (ShadowUtils.makeShadowChunkIterator), when the shader pack limits the shadow distance (shadowDistanceRenderMul &gt; 0; Mellow asks
 * for 96 blocks): the shadow map only covers that area. Nothirium's setup does not know this and, in the shadow pass, traverses and
 * draws the whole view distance (measured: ~7,000 sections per frame at distance 16, 90% of them outside the 96 blocks), which the
 * GPU then throws away.
 *
 * Here, at the start of each shadow pass setup, the same volume is built with OptiFine's own Iterator3d, from the same end points
 * ShadowUtils computes; the frustum test of a section outside it answers "out of view" (RenderChunkShadowAreaMixin). With no limit in
 * the pack, nothing changes. Render thread only.
 */
public final class ShadowArea {

    private static final float PI = (float) Math.PI;
    private static final float HALF_PI = PI / 2.0F;
    private static final float DEG_TO_RAD = PI / 180.0F;

    private static final LongOpenHashSet SECTIONS = new LongOpenHashSet();
    /** The volume applies to the setup in progress (shadow pass, with a limit in the pack). */
    private static boolean active;

    private static long limitedSetups, fullSetups, sectionsTotal, excluded;

    private ShadowArea() {
    }

    /** Start of a GL43 setup (ChunkRendererGL43Mixin). Outside the shadow pass, turns the volume off. */
    public static void beginSetup(boolean shaderMode) {
        active = false;
        if (!shaderMode || !Shaders.isShadowPass || !NthConfig.limitShadowPass) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        World world = mc.world;
        if (view == null || world == null) {
            return;
        }
        int renderDistance = mc.gameSettings.renderDistanceChunks;
        float shadowDistance = Shaders.getShadowRenderDistance();
        // The same conditions as OptiFine for not limiting.
        if (shadowDistance <= 0.0F || shadowDistance >= (renderDistance - 1) * 16) {
            fullSetups++;
            return;
        }
        int r = MathHelper.ceil(shadowDistance / 16.0F) + 1;
        float angle = world.getCelestialAngleRadians(mc.getRenderPartialTicks());
        float sunPath = Shaders.sunPathRotation * DEG_TO_RAD;
        // With the sun below the horizon, the shadow is the moon's (the opposite side).
        float light = angle > HALF_PI && angle < 3.0F * HALF_PI ? angle + PI : angle;
        float dx = -MathHelper.sin(light);
        float dy = MathHelper.cos(light) * MathHelper.cos(sunPath);
        float dz = -MathHelper.cos(light) * MathHelper.sin(sunPath);
        BlockPos center = new BlockPos(MathHelper.floor(view.posX) >> 4, MathHelper.floor(view.posY) >> 4, MathHelper.floor(view.posZ) >> 4);
        BlockPos from = center.add(-dx * r, -dy * r, -dz * r);
        BlockPos to = center.add(dx * renderDistance, dy * renderDistance, dz * renderDistance);
        SECTIONS.clear();
        Iterator3d it = new Iterator3d(from, to, r, r);
        while (it.hasNext()) {
            BlockPos p = it.next();
            SECTIONS.add(key(p.getX(), p.getY(), p.getZ()));
        }
        active = true;
        limitedSetups++;
        sectionsTotal += SECTIONS.size();
    }

    /** Is the section (section coordinates) outside the volume of the shadow pass in progress? */
    public static boolean excludes(int sectionX, int sectionY, int sectionZ) {
        if (!active || !Shaders.isShadowPass) {
            return false;
        }
        if (SECTIONS.contains(key(sectionX, sectionY, sectionZ))) {
            return false;
        }
        excluded++;
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFFFL) << 32 | ((long) z & 0xFFFFFFL) << 8 | (y & 0xFFL);
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", NthConfig.limitShadowPass);
        m.put("shadowSetupsLimited", limitedSetups);
        m.put("shadowSetupsUnlimited", fullSetups);
        m.put("sectionsInVolumePerSetup", limitedSetups > 0 ? sectionsTotal / limitedSetups : 0);
        m.put("sectionsRefused", excluded);
        return m;
    }

}
