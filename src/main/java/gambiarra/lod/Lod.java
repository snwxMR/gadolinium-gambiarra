// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import gambiarra.common.DevHooks;
import nthcompat.CompatConfig;

/**
 * Entity LOD (Gambiarra 0.2.0): only the image changes (AI, collision, hits and sound do not pass through here).
 *
 * Each living entity drawn by RenderManager gets a context (RenderManagerLodMixin), with the exceptions already applied: players, bosses, named mobs, mounts, the entity under the crosshair and the config lists never have LOD. With the context:
 *  - level 1 (subPixelParts): a model part smaller than partPixels on screen is not drawn (ModelRendererLodMixin, LLibrary's AdvancedModelRendererLodMixin, Lycanites' ModelCreatureObjLodMixin). The size comes from the real matrices (projection
 *    with the field of view and OptiFine's zoom; modelview with the entity's scale), read once per entity. Hysteresis: the part vanishes below partPixels and only comes back above 1.3 times that;
 *  - level 2 (animationBySize): animationEvery says every how many frames the pose is recomputed (Ice and Fire's Tabula in nthcompat.iceandfire.DistantDragonAnimation; Lycanites' .obj in LycanitesLod), with 15% hysteresis;
 *  - level 3 (impostors, off): Impostors.
 * In the shadow pass (shaders): no level 1, and level 2 uses the N decided in the main pass (the same pose in both).
 * Outside a perspective projection (screens, mob previews in the GUI), nothing.
 */
public final class Lod {

    public static final Logger LOG = LogManager.getLogger("gambiarra");

    /** Decided at load (LodMixinPlugin): config and side. */
    static boolean partsWanted;
    static boolean animationWanted;
    static boolean impostorsWanted;
    /** Hooks checked (HandlerCallAudit): the levels only act with them. */
    static volatile boolean managerOk;
    static volatile boolean vanillaPartsOk;
    static volatile boolean llibraryPartsOk;
    static volatile boolean lycanitesOk;

    private static int frame;
    /** The last target entity and the frame it was the target (see exclusion). */
    private static int lastTargetId = Integer.MIN_VALUE;
    private static int lastTargetFrame;
    private static final int TARGET_FRAMES = 20;
    private static final double AIM_REACH = 6.0;
    private static Object lastWorld;
    private static int lastPurge;
    private static final int PURGE_FRAMES = 600;
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    /** projection[1][1] of the last main-pass entity (LodAim, which runs with the GUI projection). */
    static float lastProj5;
    /** Level 3: measuring a model failed once; no more measuring this session, and every mob stays a model (Impostors). */
    static boolean measureBroken;

    /** What stays of an entity from one frame to the next. */
    static final class State {
        int lastSeen;
        int animEvery = 1;
        /** Level 1: hidden parts (hysteresis). */
        final Set<Object> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Level 1: parts that have come back after being hidden (to count blinks); null until the first. */
        Set<Object> revealed;
        /** Level 2: how many times N changed (the hysteresis holds at the limit). */
        int animChanges;
        /** Level 3: its atlas piece (null = model), whether it wants the quad (hysteresis) and the switch (0 = model). */
        Impostors.Slot impostor;
        boolean impostorTarget;
        int impostorFade;
        int impostorSwitches;
        /** Level 3: the model's size measured on its last draw, in blocks (0 = not measured yet; see finishMeasure). */
        float modelBlocks;
        /** Level 3: draws wanting the image before the model was measured (see Impostors.notMeasured). */
        int unmeasuredDraws;
        /** Level 3: the safety lock acting on it now (-1 = none), to count each time it starts acting. */
        int lockReason = -1;
        /** Level 3, for the F3 line (LodAim): the last decision of the main pass. */
        int decFrame = Integer.MIN_VALUE;
        float decPx, decLimit, decDist;
        byte decMode, decReason;
    }

    /** The context of the entity being drawn (one per nesting level; reused). */
    static final class Ctx {
        Entity entity;
        State state;
        boolean parts;
        boolean anim;
        boolean shadow;
        boolean mvRead;
        float proj5;
        float pxPerUnit;
        float entityPx;
        /** Level 3: measure the model on this draw (main pass, impostors on), with the modelview scale and what the parts gave. */
        boolean measure;
        float sx;
        float partUnits;
        float meshMinX, meshMinY, meshMinZ, meshMaxX, meshMaxY, meshMaxZ;
        Ctx next;
    }

    private static final Int2ObjectOpenHashMap<State> states = new Int2ObjectOpenHashMap<>();
    private static Ctx current;
    private static Ctx pool;
    private static final Map<Class<?>, Boolean> listed = new IdentityHashMap<>();

    static long contexts;
    static long partsChecked;
    static long partsHidden;
    static long partsRevealed;
    static long partFlickers;
    static int maxAnimChanges;
    /** Development build only: the entities that had an LOD context (the harness checks the exceptions). */
    private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet devContexts = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    /** Development build only: parts measured and hidden per entity class. */
    private static final Map<Class<?>, long[]> devByType = new IdentityHashMap<>();
    static long notPerspective;
    /** Level 2 on Lycanites (LycanitesLod: the only class using its types). */
    static long lycanitesPoses;
    static long lycanitesReused;
    static final long[] excluded = new long[6];
    static final String[] EXCLUSION = { "player", "boss", "name", "mount", "target", "list" };

    private static MethodHandle shadowPass;
    private static MethodHandle isShaders;
    private static boolean optiFineLooked;

    private Lod() {
    }

    /** Gambiarra.ClientFrames: a new frame (the same for the shadow pass and the main pass). */
    public static void onFrame() {
        frame++;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            states.clear();
            Impostors.clear();
        }
        if (frame - lastPurge >= PURGE_FRAMES) {
            lastPurge = frame;
            int f = frame;
            states.int2ObjectEntrySet().removeIf(e -> {
                boolean old = f - e.getValue().lastSeen > PURGE_FRAMES;
                if (old) {
                    Impostors.release(e.getValue());
                }
                return old;
            });
        }
        Impostors.onFrame();
    }

    public static int frame() {
        return frame;
    }

    /** Development build only (harness): the entity had an LOD context in some frame. */
    public static boolean devHadContext(int entityId) {
        return DevHooks.ON && devContexts.contains(entityId);
    }

    /** RenderManagerLodMixin: around each entity's Render.doRender. */
    public static void render(Render<Entity> render, Entity entity, double x, double y, double z, float yaw, float partialTicks,
            Operation<Void> original) {
        if (!managerOk) {
            original.call(render, entity, x, y, z, yaw, partialTicks);
            return;
        }
        Ctx c = contextFor(entity, x, y, z);
        Ctx outer = current;
        // An entity without LOD inside another (passenger, item with a model) does not inherit the outer context.
        current = c;
        try {
            boolean drawn = false;
            if (c != null) {
                try {
                    drawn = Impostors.draw(c, render, entity, x, y, z, yaw, partialTicks, original);
                } catch (RuntimeException | LinkageError t) {
                    // Level 3 failing never takes the mob away: it goes off for the session and the model is drawn just below.
                    Impostors.fail(t);
                    drawn = false;
                }
            }
            if (!drawn) {
                original.call(render, entity, x, y, z, yaw, partialTicks);
            }
        } finally {
            current = outer;
            if (c != null) {
                if (c.measure) {
                    finishMeasure(c);
                }
                c.entity = null;
                c.state = null;
                c.next = pool;
                pool = c;
            }
        }
    }

    private static Ctx contextFor(Entity entity, double x, double y, double z) {
        if (!(entity instanceof EntityLivingBase)) {
            return null;
        }
        int reason = exclusion(entity, true);
        if (reason >= 0) {
            excluded[reason]++;
            return null;
        }
        boolean shadow = shadowPass();
        float proj5 = 0;
        if (!shadow) {
            MATRIX.clear();
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, MATRIX);
            // Perspective: the last row is (0, 0, -1, 0). In the GUI (orthographic), nothing.
            if (Math.abs(MATRIX.get(11) + 1f) > 1e-3f || Math.abs(MATRIX.get(15)) > 1e-3f) {
                notPerspective++;
                return null;
            }
            proj5 = MATRIX.get(5);
            lastProj5 = proj5;
        }
        State s = states.get(entity.getEntityId());
        if (s == null) {
            s = new State();
            states.put(entity.getEntityId(), s);
        }
        s.lastSeen = frame;
        Ctx c = pool;
        if (c != null) {
            pool = c.next;
        } else {
            c = new Ctx();
        }
        c.next = null;
        c.entity = entity;
        c.state = s;
        c.shadow = shadow;
        c.mvRead = false;
        c.pxPerUnit = 0;
        c.proj5 = proj5;
        c.parts = !shadow && partsWanted && (vanillaPartsOk || llibraryPartsOk || lycanitesOk);
        c.anim = animationWanted;
        c.entityPx = 0;
        c.measure = !shadow && impostorsWanted && !measureBroken;
        c.sx = 0;
        c.partUnits = 0;
        c.meshMinX = c.meshMinY = c.meshMinZ = Float.MAX_VALUE;
        c.meshMaxX = c.meshMaxY = c.meshMaxZ = -Float.MAX_VALUE;
        if (!shadow) {
            double dist = Math.sqrt(x * x + y * y + z * z);
            float size = Math.max(entity.width, entity.height);
            c.entityPx = dist > 0.25 ? (float) (size * proj5 * halfHeight() / dist) : Float.MAX_VALUE;
            if (c.anim) {
                int n = every(s.animEvery, c.entityPx);
                if (n != s.animEvery) {
                    s.animEvery = n;
                    if (++s.animChanges > maxAnimChanges) {
                        maxAnimChanges = s.animChanges;
                    }
                }
            }
        }
        contexts++;
        if (DevHooks.ON) {
            devContexts.add(entity.getEntityId());
        }
        return c;
    }

    /** -1 = may have LOD; otherwise the index into EXCLUSION. record = remember the target (the render; LodAim only asks). */
    static int exclusion(Entity e, boolean record) {
        if (e instanceof EntityPlayer) {
            return 0;
        }
        if (!e.isNonBoss()) {
            return 1;
        }
        if (e.hasCustomName()) {
            return 2;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (e.isBeingRidden() || (mc.player != null && mc.player.getRidingEntity() == e)) {
            return 3;
        }
        // The target: under the crosshair, or attacked in the last 5 seconds; and it stays a target for TARGET_FRAMES frames after leaving the crosshair (in the harness, the game's crosshair lost the entity for a stray frame).
        boolean aimed = mc.pointedEntity == e || (mc.objectMouseOver != null && mc.objectMouseOver.entityHit == e)
                || (mc.player != null && mc.player.getLastAttackedEntity() == e
                        && mc.player.ticksExisted - mc.player.getLastAttackedEntityTime() < 100)
                || onAimLine(mc, e);
        if (aimed) {
            if (record) {
                lastTargetId = e.getEntityId();
                lastTargetFrame = frame;
            }
            return 4;
        }
        if (e.getEntityId() == lastTargetId && frame - lastTargetFrame < TARGET_FRAMES) {
            return 4;
        }
        Boolean l = listed.get(e.getClass());
        if (l == null) {
            l = listedInConfig(e);
            listed.put(e.getClass(), l);
        }
        return l ? 5 : -1;
    }

    /**
     * Up to AIM_REACH blocks, the entity's box crosses the crosshair line: target, even on a frame where the game has not updated its crosshair yet (in the harness, the first frame after the entity appears).
     */
    private static boolean onAimLine(Minecraft mc, Entity e) {
        Entity camera = mc.getRenderViewEntity();
        if (camera == null || e.getDistanceSq(camera) > AIM_REACH * AIM_REACH) {
            return false;
        }
        net.minecraft.util.math.Vec3d eye = camera.getPositionEyes(1.0F);
        net.minecraft.util.math.Vec3d end = eye.add(camera.getLook(1.0F).scale(AIM_REACH));
        net.minecraft.util.math.AxisAlignedBB box = e.getEntityBoundingBox().grow(e.getCollisionBorderSize());
        return box.contains(eye) || box.calculateIntercept(eye, end) != null;
    }

    private static boolean listedInConfig(Entity e) {
        ResourceLocation id = EntityList.getKey(e);
        if (id == null) {
            return false;
        }
        for (String mod : CompatConfig.lodExcludeMods) {
            if (mod.trim().equalsIgnoreCase(id.getNamespace())) {
                return true;
            }
        }
        String full = id.toString();
        for (String pattern : CompatConfig.lodExcludeEntities) {
            String p = pattern.trim();
            if (p.endsWith("*") ? full.startsWith(p.substring(0, p.length() - 1)) : full.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Level 2: N by on-screen height, with 15% hysteresis so as not to keep switching at the limit. */
    static int every(int previous, float px) {
        int candidate = framesFor(px);
        if (candidate == previous) {
            return previous;
        }
        if (candidate > previous) {
            return framesFor(px * 1.15f) > previous ? candidate : previous;
        }
        return framesFor(px / 1.15f) < previous ? candidate : previous;
    }

    private static int framesFor(float px) {
        int max = Math.max(1, CompatConfig.lodAnimationMaxFrames);
        if (px <= 0) {
            return max;
        }
        int n = (int) Math.ceil(CompatConfig.lodAnimationFullPixels / px);
        return n < 1 ? 1 : n > max ? max : n;
    }

    /**
     * Level 2: every how many frames this entity's pose is recomputed; 0 = no LOD (every frame, without storing the pose). With 1, the pose is still stored: the shadow pass and the main pass of the same frame use the same one.
     */
    public static int animationEvery(Entity entity) {
        Ctx c = current;
        if (c == null || !c.anim || c.entity != entity) {
            return 0;
        }
        return c.state.animEvery;
    }

    /** Level 1, vanilla ModelRenderer (and whoever inherits without changing render). */
    public static boolean skipVanillaPart(ModelRenderer part, float scale) {
        Ctx c = current;
        if (c == null || !(c.parts || c.measure) || !vanillaPartsOk) {
            return false;
        }
        if (c.measure) {
            measure(c, part, scale, null);
        }
        return c.parts && decide(c, part, PartExtent.size(part) * scale);
    }

    /** Level 1, LLibrary's AdvancedModelRenderer (Tabula): with the part's scale. */
    public static boolean skipLlibraryPart(ModelRenderer part, float scale, float ownScale) {
        Ctx c = current;
        if (c == null || !(c.parts || c.measure) || !llibraryPartsOk) {
            return false;
        }
        if (c.measure) {
            measure(c, part, scale * ownScale, null);
        }
        return c.parts && decide(c, part, PartExtent.size(part) * scale * ownScale);
    }

    /** Level 1, a Lycanites .obj group: s = {size (the diagonal of the vertices' box), minX, minY, minZ, maxX, maxY, maxZ}. */
    static boolean skipMeshPart(Object part, float[] s) {
        Ctx c = current;
        if (c == null || !(c.parts || c.measure) || !lycanitesOk) {
            return false;
        }
        if (c.measure) {
            measure(c, null, s[0], s);
        }
        return c.parts && decide(c, part, s[0]);
    }

    /**
     * Level 3: what this part says about the model's size. It only measures: whether the part is drawn is still decided by level 1 alone (the callers return
     * c.parts && decide, the same as before level 3 measured; with level 1 off, every part is drawn). If it fails, the measuring stops for the session and
     * every mob stays a model, drawn as the game draws it.
     *
     * part != null: units = scale, times its reach (PartExtent.reach: the part with its children, in any pose). part == null: an .obj group, units = its own
     * size and mesh = its box ({size, minX, minY, minZ, maxX, maxY, maxZ}: the groups of an .obj share the model's space, the box of all of them is the model).
     */
    static void measure(Ctx c, ModelRenderer part, float units, float[] mesh) {
        try {
            if (!c.mvRead) {
                readModelView(c);
            }
            if (part != null) {
                units *= PartExtent.reach(part);
            }
            if (units > c.partUnits) {
                c.partUnits = units;
            }
            if (mesh != null && mesh.length >= 7) {
                c.meshMinX = Math.min(c.meshMinX, mesh[1]);
                c.meshMinY = Math.min(c.meshMinY, mesh[2]);
                c.meshMinZ = Math.min(c.meshMinZ, mesh[3]);
                c.meshMaxX = Math.max(c.meshMaxX, mesh[4]);
                c.meshMaxY = Math.max(c.meshMaxY, mesh[5]);
                c.meshMaxZ = Math.max(c.meshMaxZ, mesh[6]);
            }
        } catch (Throwable t) {
            c.measure = false;
            measureFailed(t);
        }
    }

    static void measureFailed(Throwable t) {
        if (!measureBroken) {
            measureBroken = true;
            LOG.warn("[gambiarra] LOD: measuring a model failed ({}); level 3 stops measuring and every mob stays a model", t.toString());
            gambiarra.common.StartupReport.failed("lod.impostors (model measure)", t.toString());
        }
    }

    /** units = the part's size in the units it is drawn in. */
    private static boolean decide(Ctx c, Object part, float units) {
        if (!c.mvRead) {
            readModelView(c);
        }
        if (c.pxPerUnit <= 0) {
            return false;
        }
        float px = units * c.pxPerUnit;
        float t = CompatConfig.lodPartPixels;
        Set<Object> hidden = c.state.hidden;
        boolean hide;
        if (px < t) {
            hide = true;
            // Vanished again after having come back: a blink (with the camera going back and forth at the limit, the hysteresis avoids it).
            if (hidden.add(part) && c.state.revealed != null && c.state.revealed.contains(part)) {
                partFlickers++;
            }
        } else if (px >= t * 1.3f) {
            hide = false;
            if (!hidden.isEmpty() && hidden.remove(part)) {
                if (c.state.revealed == null) {
                    c.state.revealed = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                c.state.revealed.add(part);
                partsRevealed++;
            }
        } else {
            hide = hidden.contains(part);
        }
        partsChecked++;
        if (hide) {
            partsHidden++;
        }
        if (DevHooks.ON) {
            long[] byType = devByType.computeIfAbsent(c.entity.getClass(), k -> new long[2]);
            byType[0]++;
            if (hide) {
                byType[1]++;
            }
        }
        return hide;
    }

    /**
     * Once per entity, at the first part: the modelview at this point already has the entity's position, rotation and scale (preRenderCallback). Pixels per unit = scale x (window height / 2) x projection[1][1] / depth.
     */
    private static void readModelView(Ctx c) {
        c.mvRead = true;
        MATRIX.clear();
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        float sx = (float) Math.sqrt(MATRIX.get(0) * MATRIX.get(0) + MATRIX.get(1) * MATRIX.get(1) + MATRIX.get(2) * MATRIX.get(2));
        float depth = -MATRIX.get(14);
        c.sx = sx;
        c.pxPerUnit = depth > 0.1f ? sx * c.proj5 * halfHeight() / depth : 0;
    }

    /**
     * Level 3: the model's size in blocks, from the parts this draw went through (a capture also draws the model): the largest reach of a part with its
     * children (PartExtent.reach) or, on .obj models, the box of all groups. Times the modelview scale, which has the entity's own scale (the age of an
     * Ice and Fire dragon, for instance). Stays as it was when no part was drawn (a frame with the image only).
     */
    static void finishMeasure(Ctx c) {
        try {
            if (!c.mvRead || c.sx <= 0) {
                return;
            }
            float units = c.partUnits;
            if (c.meshMinX <= c.meshMaxX) {
                float dx = c.meshMaxX - c.meshMinX, dy = c.meshMaxY - c.meshMinY, dz = c.meshMaxZ - c.meshMinZ;
                units = Math.max(units, 1.25f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            if (units > 0) {
                c.state.modelBlocks = units * c.sx;
            }
        } catch (Throwable t) {
            measureFailed(t);
        }
    }

    /** LodAim: what stays of an entity (null = never drawn with LOD, or forgotten). */
    static State stateOf(int entityId) {
        return states.get(entityId);
    }

    static float halfHeight() {
        return Minecraft.getMinecraft().displayHeight * 0.5f;
    }

    /** The current context (Impostors). */
    static Ctx current() {
        return current;
    }

    static boolean shadowPass() {
        lookOptiFine();
        try {
            return shadowPass != null && (boolean) shadowPass.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shader pack active (OptiFine). Without OptiFine: false. */
    static boolean shaderPackActive() {
        lookOptiFine();
        try {
            return isShaders != null && (boolean) isShaders.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void lookOptiFine() {
        if (optiFineLooked) {
            return;
        }
        optiFineLooked = true;
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders", false, Lod.class.getClassLoader());
            shadowPass = MethodHandles.publicLookup().findStaticGetter(shaders, "isShadowPass", boolean.class);
        } catch (Throwable t) {
            shadowPass = null;
        }
        try {
            Class<?> config = Class.forName("Config", false, Lod.class.getClassLoader());
            isShaders = MethodHandles.publicLookup().findStatic(config, "isShaders", java.lang.invoke.MethodType.methodType(boolean.class));
        } catch (Throwable t) {
            try {
                Class<?> config = Class.forName("net.optifine.Config", false, Lod.class.getClassLoader());
                isShaders = MethodHandles.publicLookup().findStatic(config, "isShaders", java.lang.invoke.MethodType.methodType(boolean.class));
            } catch (Throwable t2) {
                isShaders = null;
            }
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level1", !partsWanted ? "off" : "vanilla " + (vanillaPartsOk ? "active" : "not checked") + ", Tabula "
                + (llibraryPartsOk ? "active" : "not checked") + ", Lycanites " + (lycanitesOk ? "active" : "not checked"));
        m.put("level2", !animationWanted ? "off" : "Tabula (see iceAndFire), Lycanites " + (lycanitesOk ? "active" : "not checked"));
        m.put("level3", !impostorsWanted ? "off" : Impostors.describe());
        m.put("hook", managerOk ? "active" : "not checked");
        m.put("entitiesWithLod", contexts);
        m.put("partsMeasured", partsChecked);
        m.put("partsHidden", partsHidden);
        m.put("partsThatCameBack", partsRevealed);
        m.put("level1Blinks", partFlickers);
        m.put("mostNChangesPerEntity", maxAnimChanges);
        Map<String, Long> ex = new LinkedHashMap<>();
        for (int i = 0; i < EXCLUSION.length; i++) {
            ex.put(EXCLUSION[i], excluded[i]);
        }
        m.put("exclusions", ex);
        m.put("notPerspective", notPerspective);
        m.put("injections", LodMixinPlugin.injections());
        if (DevHooks.ON) {
            Map<String, String> byType = new java.util.TreeMap<>();
            for (Map.Entry<Class<?>, long[]> e : devByType.entrySet()) {
                if (e.getValue()[1] > 0) {
                    byType.put(e.getKey().getSimpleName(), e.getValue()[1] + "/" + e.getValue()[0]);
                }
            }
            m.put("hiddenByType", byType);
        }
        Map<String, Long> lyc = new LinkedHashMap<>();
        lyc.put("posesComputed", lycanitesPoses);
        lyc.put("posesRepeated", lycanitesReused);
        m.put("lycanites", lyc);
        return m;
    }

}
