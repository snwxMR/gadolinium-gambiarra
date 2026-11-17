// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.optifine.shaders.Shaders;
import nthshaders.common.DevHooks;
import nthshaders.common.JarUtil;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;

/**
 * gpuCullingEntities: entities and tile entities are only drawn if some section touched by their box (see testBoxOf) was
 * reached by the main pass's visibility traversal, as Embeddium does. The GPU culling traversal goes through every section
 * visible from the camera along some path (without the frustum), so an entity in a cave with no connection to the camera
 * is not drawn. The rest (frustum, EntityCulling) stays the same, after this test.
 *
 * Left out (always drawn): the shadow pass (the shadow comes from where the light sees), the camera entity, whoever it rides
 * or rides it, entities with ignoreFrustumCheck, tile entities with an infinite box, boxes outside the world height, a section
 * with no RenderChunk, boxes too large (more than 64 sections) and entities whose rendering ignores the frustum
 * (rendererIgnoresFrustum, like the Ice and Fire dragon). Lightning (EntityLightningBolt) does not even get here: RenderLib only
 * tests the world's entity list, and it is in the weather effects list. Without GPU culling active, nothing changes.
 *
 * The mixins (nthgpu.mixin.RenderLibEntityGraphMixin and RenderLibTileEntityGraphMixin) wrap RenderLib 1.4.5's shouldRender and
 * only apply with the version and classes checked (SHA-256). Render thread only.
 */
public final class EntityGraphCulling {

    static final String RENDERLIB_MARKER = "meldexun/renderlib/RenderLib.class";
    /** {class, SHA-256} of RenderLib 1.4.5. */
    private static final String[][] RENDERLIB_CLASSES = {
            { "meldexun/renderlib/renderer/entity/EntityRenderer.class", "a5c45a858c7deae5f36a64952ba9f8a21479304df46dd2d4a372910c04355e2b" },
            { "meldexun/renderlib/renderer/tileentity/TileEntityRenderer.class", "471550ec649d2b6f700a4fbee2e277bb41dde546b0f2ef856257a82742d02618" },
    };
    private static final int MAX_SECTIONS = 64;
    /** Side (blocks) from which the box grows by its own size (models larger than the box: dragon wings). */
    private static final double BIG = 2.0;
    /** Dev: -Dnthshaders.entityGraphRenderBoxOnly=true tests only the render box + 0.5. */
    private static final boolean RENDER_BOX_ONLY = DevHooks.ON && Boolean.getBoolean("nthshaders.entityGraphRenderBoxOnly");

    private static boolean evaluated;
    private static boolean known;
    private static String problem;

    private static long entitiesHidden;
    private static long entitiesTested;
    private static long tileEntitiesHidden;
    private static long tileEntitiesTested;
    /** The total at the start and end of the last whole frame (F3): entities tested and hidden, tile entities likewise. */
    private static final long[] frameStart = new long[4];
    private static final int[] lastFrame = new int[4];
    /** Entity class -> its Render draws even with nothing in the frustum (rendererIgnoresFrustum). */
    private static final Map<Class<?>, Boolean> drawsAlways = new IdentityHashMap<>();
    /** Dev: hidden by type (entities by id, tile entities with "te:"). */
    private static final Map<String, Long> hiddenByType = new HashMap<>();
    /** Dev: the last decision for each entity (id) and the flips between hidden and drawn (noteDecision). */
    private static final Map<Integer, Boolean> lastDecision = new HashMap<>();
    private static final Map<String, Long> flipsByType = new HashMap<>();
    private static long decisionFlips;
    /** A camera that sees nothing (rendererIgnoresFrustum). */
    private static final ICamera NOWHERE = new ICamera() {
        @Override
        public boolean isBoundingBoxInFrustum(AxisAlignedBB box) {
            return false;
        }

        @Override
        public void setPosition(double x, double y, double z) {
        }
    };

    private EntityGraphCulling() {
    }

    /** RenderLib 1.4.5 with the classes checked (the mixin plugin asks, before the classes load). */
    static synchronized boolean renderLibKnown() {
        if (!evaluated) {
            evaluated = true;
            try {
                File jar = JarUtil.jarOf(RENDERLIB_MARKER);
                String version = jar == null ? null : JarUtil.modVersion(RENDERLIB_MARKER, "renderlib");
                if (!"1.4.5".equals(version)) {
                    problem = "RenderLib " + version + " (checked: 1.4.5)";
                } else {
                    for (String[] c : RENDERLIB_CLASSES) {
                        byte[] bytes = JarUtil.readEntry(jar, c[0]);
                        if (bytes == null || !c[1].equals(JarUtil.sha256Hex(bytes))) {
                            problem = "class differs from the checked one: " + c[0];
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                problem = "failed checking RenderLib: " + t;
            }
            known = problem == null;
            Status.LOG.info("[gadolinium] Entity graph culling (gpuCullingEntities): {}", !NthConfig.gpuCullingEntities ? "disabled in the config"
                    : known ? "enabled" : "DISABLED (" + problem + ")");
            if (!known && NthConfig.gpuCullingEntities) {
                nthshaders.common.StartupReport.lock("gpuCullingEntities", problem);
            }
        }
        return known;
    }

    /** End of a frame: keeps how much this frame tested and hid (F3). */
    static void endFrame() {
        long[] now = { entitiesTested, entitiesHidden, tileEntitiesTested, tileEntitiesHidden };
        for (int i = 0; i < 4; i++) {
            lastFrame[i] = (int) (now[i] - frameStart[i]);
            frameStart[i] = now[i];
        }
    }

    static String state() {
        return !NthConfig.gpuCullingEntities ? "off" : GpuInjections.entityOk() ? "on" : "not verified";
    }

    static String frameLine() {
        return "By graph: " + lastFrame[1] + " of " + lastFrame[0] + " entities hidden, " + lastFrame[3] + " of " + lastFrame[2]
                + " chests and the like hidden";
    }

    /** Whether the test applies right now: option on, injections checked, GPU culling in use and outside the shadow pass. */
    private static GpuTerrain terrain() {
        if (!NthConfig.gpuCullingEntities || !GpuInjections.entityOk() || !GpuCulling.usable() || Shaders.isShadowPass) {
            return null;
        }
        return GpuCulling.activeTerrain();
    }

    /** RenderLib EntityRenderer.shouldRender, after the original said "yes" (cam = the position RenderLib used). */
    public static boolean entityVisible(Entity entity, double camX, double camY, double camZ) {
        GpuTerrain t = terrain();
        if (t == null) {
            return true;
        }
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (entity.ignoreFrustumCheck || entity == view || view != null && entity.isRidingOrBeingRiddenBy(view)
                || !RENDER_BOX_ONLY && rendererIgnoresFrustum(entity, camX, camY, camZ)) {
            return true;
        }
        entitiesTested++;
        boolean visible = boxReached(t, testBoxOf(entity));
        if (DevHooks.ON) {
            noteDecision(entity, visible);
        }
        if (visible) {
            return true;
        }
        entitiesHidden++;
        if (DevHooks.ON) {
            hiddenByType.merge(typeOf(entity), 1L, Long::sum);
        }
        return false;
    }

    /**
     * Dev: how many times each entity's decision flipped between hidden and drawn. With the camera only turning and entities
     * standing still, the traversal does not change (it does not use the frustum), so no flip is expected: a flip is a "blink".
     * Keeps the entities with the most flips.
     */
    private static void noteDecision(Entity entity, boolean visible) {
        Boolean last = lastDecision.put(entity.getEntityId(), visible);
        if (last != null && last != visible) {
            decisionFlips++;
            flipsByType.merge(typeOf(entity), 1L, Long::sum);
        }
    }

    /** Dev: resets the flip count (the harness calls this at the start of the measured window). */
    public static void resetFlips() {
        lastDecision.clear();
        flipsByType.clear();
        decisionFlips = 0;
    }

    /**
     * Does the mod's rendering ignore the frustum? Asks once per entity class, its Render, with a camera that sees nothing: if it
     * still says "draw", the mod always draws (the Ice and Fire 1.9.1 dragon: the wings go far past the box and no box says how
     * far the model goes). So the graph does not hide it either. When in doubt (error), neither.
     */
    private static boolean rendererIgnoresFrustum(Entity entity, double camX, double camY, double camZ) {
        Class<?> type = entity.getClass();
        Boolean known = drawsAlways.get(type);
        if (known == null) {
            boolean always;
            try {
                Render<Entity> render = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(entity);
                always = render != null && render.shouldRender(entity, NOWHERE, camX, camY, camZ);
            } catch (Throwable t) {
                always = true;
            }
            drawsAlways.put(type, always);
            if (always) {
                Status.LOG.info("[gadolinium] Entity graph culling: {} always drawn (the mod's rendering ignores the frustum)", typeOf(entity));
            }
            known = always;
        }
        return known;
    }

    private static String typeOf(Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        return key != null ? key.toString() : entity.getClass().getName();
    }

    /** RenderLib TileEntityRenderer.shouldRender, after the original said "yes". */
    public static boolean tileEntityVisible(TileEntity tileEntity) {
        GpuTerrain t = terrain();
        if (t == null) {
            return true;
        }
        AxisAlignedBB box = tileEntity.getRenderBoundingBox();
        if (box == TileEntity.INFINITE_EXTENT_AABB) {
            return true;
        }
        tileEntitiesTested++;
        if (boxReached(t, box.grow(0.5))) {
            return true;
        }
        tileEntitiesHidden++;
        if (DevHooks.ON) {
            ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
            hiddenByType.merge("te:" + (key != null ? key.toString() : tileEntity.getClass().getName()), 1L, Long::sum);
        }
        return false;
    }

    /**
     * The box tested for an entity (every section it touches counts):
     *  - the union of the render box (getRenderBoundingBox, which mods enlarge for big models) and the collision box;
     *  - plus the parts of a multipart game entity (getParts, like the Ender dragon);
     *  - a big entity (longest side from 2 blocks up) grows by its own longest side on each side: the model can go far past the
     *    box, and some do not even test the frustum when drawing (the Ice and Fire 1.9.1 dragon always draws itself, with the
     *    wings well outside the collision box). The others grow by 0.5, like the game's frustum test.
     * null = always draw (no box, or a box with NaN).
     */
    public static AxisAlignedBB testBoxOf(Entity entity) {
        AxisAlignedBB box = entity.getRenderBoundingBox();
        AxisAlignedBB body = entity.getEntityBoundingBox();
        if (RENDER_BOX_ONLY) {
            return box == null ? null : box.grow(0.5);
        }
        box = box == null ? body : body == null ? box : box.union(body);
        if (box == null || box.hasNaN()) {
            return null;
        }
        Entity[] parts = entity.getParts();
        if (parts != null) {
            for (Entity p : parts) {
                if (p != null) {
                    box = box.union(p.getEntityBoundingBox());
                }
            }
        }
        double side = Math.max(box.maxX - box.minX, Math.max(box.maxY - box.minY, box.maxZ - box.minZ));
        return box.grow(side >= BIG ? side : 0.5);
    }

    private static boolean boxReached(GpuTerrain t, AxisAlignedBB box) {
        if (box == null || box.maxY < 0.0 || box.minY >= 256.0) {
            return true;
        }
        int x0 = floor(box.minX) >> 4;
        int y0 = Math.max(0, floor(box.minY) >> 4);
        int z0 = floor(box.minZ) >> 4;
        int x1 = floor(box.maxX) >> 4;
        int y1 = Math.min(15, floor(box.maxY) >> 4);
        int z1 = floor(box.maxZ) >> 4;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_SECTIONS) {
            return true;
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (t.sectionReached(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Sections reached and total touched by the box (only for diagnostics). */
    private static String count(GpuTerrain t, AxisAlignedBB box) {
        if (box == null) {
            return "no box";
        }
        int reached = 0;
        int total = 0;
        for (int x = floor(box.minX) >> 4; x <= floor(box.maxX) >> 4; x++) {
            for (int z = floor(box.minZ) >> 4; z <= floor(box.maxZ) >> 4; z++) {
                for (int y = Math.max(0, floor(box.minY) >> 4); y <= Math.min(15, floor(box.maxY) >> 4); y++) {
                    total++;
                    if (t.sectionReached(x, y, z)) {
                        reached++;
                    }
                }
            }
        }
        return reached + " of " + total + " sections reached";
    }

    /** Dev (smoketest): the test of one entity, by the centre section, the collision box and the tested box. */
    public static String describeEntity(Entity e) {
        GpuTerrain t = GpuCulling.activeTerrain();
        if (t == null) {
            return "no GPU culling active";
        }
        AxisAlignedBB body = e.getEntityBoundingBox();
        AxisAlignedBB used = testBoxOf(e);
        boolean center = t.sectionReached(MathHelper.floor(e.posX) >> 4, MathHelper.floor(e.posY + e.height / 2.0) >> 4,
                MathHelper.floor(e.posZ) >> 4);
        return "centre " + (center ? "reached" : "NOT reached") + "; collision box + 0.5: " + count(t, body.grow(0.5))
                + "; box tested " + used + ": " + count(t, used) + "; the mod's rendering ignores the frustum: "
                + (RENDER_BOX_ONLY ? "not asked" : String.valueOf(drawsAlways.get(e.getClass()))) + "; drawn: "
                + (!RENDER_BOX_ONLY && Boolean.TRUE.equals(drawsAlways.get(e.getClass())) || boxReached(t, used))
                + (RENDER_BOX_ONLY ? " (render box only mode)" : "");
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", !NthConfig.gpuCullingEntities ? "disabled in the config"
                : !renderLibKnown() ? "disabled: " + problem : GpuInjections.entityOk() ? "enabled" : "injections not checked");
        m.put("entitiesTested", entitiesTested);
        m.put("entitiesHidden", entitiesHidden);
        m.put("tileEntitiesTested", tileEntitiesTested);
        m.put("tileEntitiesHidden", tileEntitiesHidden);
        List<String> always = new ArrayList<>();
        for (Map.Entry<Class<?>, Boolean> e : drawsAlways.entrySet()) {
            if (e.getValue()) {
                always.add(e.getKey().getSimpleName());
            }
        }
        m.put("alwaysDrawnByMod", always.toString());
        if (DevHooks.ON) {
            StringBuilder sb = new StringBuilder();
            hiddenByType.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(12)
                    .forEach(e -> sb.append(sb.length() > 0 ? ", " : "").append(e.getKey()).append('=').append(e.getValue()));
            m.put("hiddenByType", sb.toString());
            StringBuilder fl = new StringBuilder();
            flipsByType.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                    .forEach(e -> fl.append(fl.length() > 0 ? ", " : "").append(e.getKey()).append('=').append(e.getValue()));
            m.put("hiddenDrawnFlips", decisionFlips + (fl.length() > 0 ? " (" + fl + ")" : ""));
        }
        return m;
    }

}
