// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import nthcompat.CompatConfig;

/**
 * LOD level 3 (impostors, EXPERIMENTAL, off by default): an entity smaller than impostorPixels on screen becomes a quad with an image of itself. Only without a shader pack (the pack's programs do not know how to draw the quad) and outside the
 * shadow pass. The size on screen is that of the larger of the collision box and the model measured on its last draw (Lod.finishMeasure; the box alone made a small
 * box with a large model, like a Lycanites flyer or a dragon's wings, look small up close). Until the model is measured, the model.
 *
 * Safety lock (lock), whatever the size says: never within 8 blocks or closer than impostorMinDistance (32), never on a mob attacking the player or attacked by
 * them; each time it acts is counted (safetyLock): with a sound measure and limit it should not have to.
 *
 * The image is drawn into an FBO with a 2048 x 2048 atlas (1024 pieces of 64 x 64), with the game's same camera: the modelview does not change and the projection becomes an off-axis frustum covering only the square around the entity (centre and
 * radius in camera space; the depth goes 4 radii each way). So the image is the same the game would draw there, at lower resolution, with the same light and the same fog. The quad is drawn in that same square, at the depth of the centre, with no
 * light and no fog (they are already in the image), with alpha test at impostorEdgeAlpha (0.5).
 *
 * The image is redone every impostorUpdateFrames frames (the animation), and sooner if the camera's direction to the entity turns more than 6 degrees, the distance changes 20% or the entity turns more than 8 degrees; at most 8 per frame. The
 * switch between model and quad takes impostorFadeFrames frames, dithered (glPolygonStipple, 4 x 4 Bayer pattern: the pixels of one vanish where those of the other appear). Hysteresis: quad below impostorPixels, model again above 1.2 times that.
 * Never with the entity hurt, dying, glowing or invisible. The FBO is created the first time it is needed and deleted when changing worlds.
 */
final class Impostors {

    private Impostors() {
    }

    static final int ATLAS = 2048;
    static final int TILE = 64;
    static final int PER_ROW = ATLAS / TILE;
    private static final int MAX_CAPTURES = 8;
    private static final float COS_TURN = (float) Math.cos(Math.toRadians(6));

    static final class Slot {
        final int index;
        int capturedFrame = Integer.MIN_VALUE;
        float dirX, dirY, dirZ, dist, yaw;
        boolean valid;

        Slot(int index) {
            this.index = index;
        }
    }

    private static int fbo = -1;
    private static int texture = -1;
    private static int depth = -1;
    private static boolean failed;
    private static String failedWhy = "the FBO failed";
    private static final ArrayDeque<Slot> freeSlots = new ArrayDeque<>();
    private static int capturesThisFrame;

    static long atlasCreated;
    static long atlasDeleted;
    static long captures;
    static long quads;
    static long fades;
    static int liveSlots;
    /** The entity that switched the most between model and quad (with the camera moving in one direction only: at most 1). */
    static int maxSwitches;
    /** Switches decided: to the image, and back to the model (for any reason). */
    static long toImage;
    static long toModel;
    /** Each time the safety lock started acting on an entity the measure had put below the limit, by reason (from LOCK_NEAR on). */
    static final long[] LOCKS = new long[5];
    /** ...and of those, the ones that cut an image already on screen. */
    static long lockCutImage;
    /** Entities wanting the image whose model was never measured (no part hook reaches it): they stay models. */
    static long notMeasured;
    private static long lastPlayerHurt;
    private static boolean playerHurt;

    /** What the F3 line shows (LodAim). */
    static final byte MODEL = 0, IMAGE = 1, TO_IMAGE = 2, TO_MODEL = 3;
    static final String[] MODES = { "model", "image", "switching to the image", "switching to the model" };
    static final int INACTIVE = 0, HURT = 1, NOT_MEASURED = 2, LARGE = 3, SMALL = 4, LOCK_NEAR = 5, LOCK_ATTACKED = 6, LOCK_ATTACKING = 7,
            LOCK_DISTANCE = 8, LOCK_PLAYER_HURT = 9, CAMERA_INSIDE = 10, NOT_READY = 11, DYING = 12, GLOWING = 13, INVISIBLE = 14;
    static final String[] REASONS = { "level 3 not active here", "hurt (the red flash)", "model not measured yet", "above the limit",
            "below the limit", "safety lock: within 8 blocks", "safety lock: attacked by the player", "safety lock: attacking the player",
            "safety lock: closer than impostorMinDistance", "safety lock: player hurt, twice impostorMinDistance", "camera inside the image",
            "image not ready", "dying", "glowing (outline)", "invisible for the game: it draws only its layers (eyes, armour), not the body" };
    private static final String[] LOCK_KEYS = { "within8Blocks", "attackedByPlayer", "attackingPlayer", "closerThanMinDistance",
            "playerHurtRecently" };

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final ByteBuffer[] STIPPLE = new ByteBuffer[17];
    private static final ByteBuffer[] STIPPLE_INVERSE = new ByteBuffer[17];
    private static final int[][] BAYER = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    static void onFrame() {
        capturesThisFrame = 0;
        if (Lod.impostorsWanted) {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            long now = System.nanoTime();
            if (player != null && player.hurtTime > 0) {
                lastPlayerHurt = now;
            }
            playerHurt = lastPlayerHurt != 0 && now - lastPlayerHurt < 5_000_000_000L;
        }
    }

    private static boolean active(Lod.Ctx c) {
        return Lod.impostorsWanted && !failed && !c.shadow && OpenGlHelper.isFramebufferEnabled() && !Lod.shaderPackActive();
    }

    /** The game draws these its own way (red flash, death, outline, only the layers): never the image. -1 = none; the order is the F3 line's. */
    static int special(EntityLivingBase e) {
        if (e.isInvisible()) {
            return INVISIBLE;
        }
        if (e.deathTime > 0) {
            return DYING;
        }
        if (e.hurtTime > 0) {
            return HURT;
        }
        return e.isGlowing() ? GLOWING : -1;
    }

    /** An error in level 3 (Lod.render): off for the session; the mob it was drawing is drawn as a model. */
    static void fail(Throwable t) {
        if (!failed) {
            failed = true;
            failedWhy = t.toString();
            Lod.LOG.warn("[gambiarra] LOD: level 3 failed ({}); off for this session, every mob is drawn as a model", t.toString(), t);
            gambiarra.common.StartupReport.failed("lod.impostors", t.toString());
        }
    }

    /** LodAim: why level 3 cannot act now (null = it can). */
    static String inactiveWhy() {
        if (!Lod.impostorsWanted) {
            return "level 3 off (impostors=false)";
        }
        if (failed) {
            return "level 3 off (" + failedWhy + ")";
        }
        if (Lod.measureBroken) {
            return "level 3 off (measuring a model failed)";
        }
        if (!OpenGlHelper.isFramebufferEnabled()) {
            return "level 3 off (no framebuffers)";
        }
        if (Lod.shaderPackActive()) {
            return "level 3 off (shader pack)";
        }
        return null;
    }

    /** true = drew (the quad, or the model and the quad in the switch); false = the caller draws the model. */
    static boolean draw(Lod.Ctx c, Render<Entity> render, Entity entity, double x, double y, double z, float yaw, float partialTicks,
            Operation<Void> original) {
        if (!Lod.impostorsWanted || c.shadow) {
            return false;
        }
        Lod.State s = c.state;
        // Centre, distance and size: the larger of the collision box and the model measured on its last draw.
        double cy = y + entity.height * 0.5;
        float dist = (float) Math.sqrt(x * x + cy * cy + z * z);
        float box = Math.max(entity.width, entity.height);
        float size = Math.max(box, s.modelBlocks);
        float px = dist > 0.25f ? size * c.proj5 * Lod.halfHeight() / dist : Float.MAX_VALUE;
        float limit = s.impostorTarget ? CompatConfig.lodImpostorPixels * 1.2f : CompatConfig.lodImpostorPixels;
        if (!active(c)) {
            release(s);
            record(s, px, limit, dist, MODEL, INACTIVE);
            return false;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        int reason = special(living);
        if (reason >= 0) {
            // As it is: the model, drawn by the game in its own way (an invisible mob: only its layers, such as the eyes).
        } else if (s.modelBlocks <= 0 || Lod.measureBroken) {
            reason = NOT_MEASURED;
        } else if (!(px > 0) || px >= limit) {
            reason = LARGE;
        } else {
            reason = lock(entity, dist - size * 0.5f);
        }
        boolean want = reason == SMALL;
        if (reason >= LOCK_NEAR && reason <= LOCK_PLAYER_HURT) {
            // The measure wanted the image and the lock said no: counted once each time it starts acting on the entity.
            if (s.lockReason != reason) {
                LOCKS[reason - LOCK_NEAR]++;
                if (s.impostorFade > 0) {
                    lockCutImage++;
                }
            }
            s.lockReason = reason;
        } else {
            s.lockReason = -1;
        }
        if (reason == NOT_MEASURED && px < limit && ++s.unmeasuredDraws == 2) {
            // Drawn as a model and still not measured: no part hook reaches its model.
            notMeasured++;
        }
        if (want != s.impostorTarget) {
            s.impostorTarget = want;
            if (want) {
                toImage++;
            } else {
                toModel++;
            }
            if (++s.impostorSwitches > maxSwitches) {
                maxSwitches = s.impostorSwitches;
            }
        }
        int fadeFrames = Math.max(1, CompatConfig.lodImpostorFadeFrames);
        if (want) {
            s.impostorFade = Math.min(fadeFrames, s.impostorFade + 1);
        } else {
            // Above the limit, the dithered switch back; for everything else (the locks included), the model at once.
            s.impostorFade = reason == LARGE ? Math.max(0, s.impostorFade - 1) : 0;
        }
        if (s.impostorFade == 0) {
            release(s);
            record(s, px, limit, dist, MODEL, reason);
            return false;
        }
        Slot slot = s.impostor;
        if (slot == null) {
            if (!ensureAtlas() || freeSlots.isEmpty()) {
                s.impostorFade = 0;
                record(s, px, limit, dist, MODEL, NOT_READY);
                return false;
            }
            slot = freeSlots.pop();
            slot.valid = false;
            s.impostor = slot;
            liveSlots++;
        }
        // Centre and radius in camera space (the modelview here is the camera's; x, y, z are relative to it).
        MATRIX.clear();
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        float ex = (float) (MATRIX.get(0) * x + MATRIX.get(4) * cy + MATRIX.get(8) * z + MATRIX.get(12));
        float ey = (float) (MATRIX.get(1) * x + MATRIX.get(5) * cy + MATRIX.get(9) * z + MATRIX.get(13));
        float ez = (float) (MATRIX.get(2) * x + MATRIX.get(6) * cy + MATRIX.get(10) * z + MATRIX.get(14));
        float radius = (float) Math.max(0.6 * Math.sqrt(entity.width * entity.width + entity.height * entity.height) + 0.3,
                0.6 * s.modelBlocks + 0.3);
        if (-ez - radius < 0.1f) {
            release(s);
            record(s, px, limit, dist, MODEL, CAMERA_INSIDE);
            return false;
        }
        float dx = (float) (x / dist), dy = (float) (cy / dist), dz = (float) (z / dist);
        float bodyYaw = living.renderYawOffset;
        boolean stale = !slot.valid || Lod.frame() - slot.capturedFrame >= CompatConfig.lodImpostorUpdateFrames
                || dx * slot.dirX + dy * slot.dirY + dz * slot.dirZ < COS_TURN || Math.abs(dist / slot.dist - 1f) > 0.2f
                || Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(bodyYaw - slot.yaw)) > 8f;
        if (stale && capturesThisFrame < MAX_CAPTURES) {
            capturesThisFrame++;
            capture(slot, ex, ey, ez, radius, render, entity, x, y, z, yaw, partialTicks, original);
            slot.dirX = dx;
            slot.dirY = dy;
            slot.dirZ = dz;
            slot.dist = dist;
            slot.yaw = bodyYaw;
        }
        if (!slot.valid) {
            record(s, px, limit, dist, MODEL, NOT_READY);
            return false;
        }
        if (s.impostorFade >= fadeFrames) {
            record(s, px, limit, dist, IMAGE, reason);
            quad(slot, ex, ey, ez, radius, null);
            return true;
        }
        // Dithered switch: the model on the pattern's pixels, the quad on the rest.
        record(s, px, limit, dist, want ? TO_IMAGE : TO_MODEL, reason);
        fades++;
        int level = Math.round(16f * s.impostorFade / fadeFrames);
        stipple(level, true);
        GL11.glEnable(GL11.GL_POLYGON_STIPPLE);
        try {
            original.call(render, entity, x, y, z, yaw, partialTicks);
        } finally {
            GL11.glDisable(GL11.GL_POLYGON_STIPPLE);
        }
        quad(slot, ex, ey, ez, radius, Integer.valueOf(level));
        return true;
    }

    /**
     * The safety lock, whatever the size on screen says: never the image within 8 blocks, on a mob attacking the player or attacked by
     * them in the last 5 seconds (the target exclusion in Lod already keeps that one out), nor closer than impostorMinDistance (twice that
     * while the player has been hurt in the last 5 seconds: an attacker from afar). near = distance to the nearest edge of the entity.
     */
    private static int lock(Entity entity, float near) {
        if (near < 8f) {
            return LOCK_NEAR;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null) {
            if (player.getLastAttackedEntity() == entity && player.ticksExisted - player.getLastAttackedEntityTime() < 100) {
                return LOCK_ATTACKED;
            }
            try {
                // On the client the attack target is only known where the mod sends it; without it, the distance locks are what is left.
                if ((entity instanceof EntityLiving && ((EntityLiving) entity).getAttackTarget() == player)
                        || ((EntityLivingBase) entity).getRevengeTarget() == player) {
                    return LOCK_ATTACKING;
                }
            } catch (Throwable t) {
                // A mod's getter failing on the client says nothing: the other locks still apply.
            }
        }
        float min = CompatConfig.lodImpostorMinDistance;
        if (near < min) {
            return LOCK_DISTANCE;
        }
        if (playerHurt && near < 2f * min) {
            return LOCK_PLAYER_HURT;
        }
        return SMALL;
    }

    private static void record(Lod.State s, float px, float limit, float dist, byte mode, int reason) {
        s.decFrame = Lod.frame();
        s.decPx = px;
        s.decLimit = limit;
        s.decDist = dist;
        s.decMode = mode;
        s.decReason = (byte) reason;
    }

    private static void capture(Slot slot, float ex, float ey, float ez, float radius, Render<Entity> render, Entity entity, double x,
            double y, double z, float yaw, float partialTicks, Operation<Void> original) {
        int previousFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        VIEWPORT.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int vx = VIEWPORT.get(0), vy = VIEWPORT.get(1), vw = VIEWPORT.get(2), vh = VIEWPORT.get(3);
        int tx = (slot.index % PER_ROW) * TILE, ty = (slot.index / PER_ROW) * TILE;
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        // 1 pixel of border around each piece: the linear filter does not catch the neighbour.
        GL11.glViewport(tx + 1, ty + 1, TILE - 2, TILE - 2);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(tx, ty, TILE, TILE);
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        // Through GlStateManager: it keeps the clear colour, and the game puts its own back before clearing the screen.
        GlStateManager.clearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glScissor(tx + 1, ty + 1, TILE - 2, TILE - 2);
        // Depth with room to spare: the image keeps no depth, so precision does not matter here, and parts beyond the radius along the line
        // of sight (a dragon seen head on) are no longer cut by the near and far planes.
        float near = Math.max(0.05f, -ez - 4f * radius);
        float far = -ez + 4f * radius;
        float k = near / -ez;
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GL11.glFrustum((ex - radius) * k, (ex + radius) * k, (ey - radius) * k, (ey + radius) * k, near, far);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        try {
            original.call(render, entity, x, y, z, yaw, partialTicks);
            slot.valid = true;
            slot.capturedFrame = Lod.frame();
            captures++;
        } finally {
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
            GL11.glViewport(vx, vy, vw, vh);
        }
    }

    /** The quad, in camera space; stippleLevel != null: only the pixels outside that level's pattern. */
    private static void quad(Slot slot, float ex, float ey, float ez, float radius, Integer stippleLevel) {
        quads++;
        float u0 = ((slot.index % PER_ROW) * TILE + 1f) / ATLAS, v0 = ((slot.index / PER_ROW) * TILE + 1f) / ATLAS;
        float u1 = u0 + (TILE - 2f) / ATLAS, v1 = v0 + (TILE - 2f) / ATLAS;
        // The previous state (GL answers without waiting for the GPU): restored as it was at the end, without assuming what was on.
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean fog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        int alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        // Cut at impostorEdgeAlpha (0.5): with the linear filter, the edge falls on the model's outline, not half a pixel outside it.
        GlStateManager.alphaFunc(GL11.GL_GREATER, CompatConfig.lodImpostorEdgeAlpha);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture);
        if (stippleLevel != null) {
            stipple(stippleLevel, false);
            GL11.glEnable(GL11.GL_POLYGON_STIPPLE);
        }
        try {
            Tessellator t = Tessellator.getInstance();
            BufferBuilder b = t.getBuffer();
            b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            b.pos(ex - radius, ey - radius, ez).tex(u0, v0).endVertex();
            b.pos(ex + radius, ey - radius, ez).tex(u1, v0).endVertex();
            b.pos(ex + radius, ey + radius, ez).tex(u1, v1).endVertex();
            b.pos(ex - radius, ey + radius, ez).tex(u0, v1).endVertex();
            t.draw();
        } finally {
            if (stippleLevel != null) {
                GL11.glDisable(GL11.GL_POLYGON_STIPPLE);
            }
            if (lightmap) {
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GlStateManager.enableTexture2D();
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            }
            if (lighting) {
                GlStateManager.enableLighting();
            }
            if (fog) {
                GlStateManager.enableFog();
            }
            if (blend) {
                GlStateManager.enableBlend();
            }
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
            if (!alpha) {
                GlStateManager.disableAlpha();
            }
            GlStateManager.popMatrix();
        }
    }

    /** 4 x 4 Bayer pattern in 32 x 32: level 0..16 pixels on out of every 16; inverse = the exact complement. */
    private static void stipple(int level, boolean modelPart) {
        int l = Math.max(0, Math.min(16, level));
        // The model keeps the pixels that have not become the quad yet (16 - level); the quad, the rest.
        ByteBuffer mask = modelPart ? pattern(16 - l, false) : pattern(16 - l, true);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST, GL11.GL_FALSE);
            GL11.glPolygonStipple(mask);
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    private static ByteBuffer pattern(int on, boolean inverse) {
        ByteBuffer[] cache = inverse ? STIPPLE_INVERSE : STIPPLE;
        if (cache[on] == null) {
            ByteBuffer b = BufferUtils.createByteBuffer(128);
            for (int row = 0; row < 32; row++) {
                for (int byteInRow = 0; byteInRow < 4; byteInRow++) {
                    int bits = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int col = byteInRow * 8 + bit;
                        boolean set = BAYER[row & 3][col & 3] < on;
                        if (set != inverse) {
                            bits |= 0x80 >>> bit;
                        }
                    }
                    b.put((byte) bits);
                }
            }
            b.flip();
            cache[on] = b;
        }
        cache[on].rewind();
        return cache[on];
    }

    private static boolean ensureAtlas() {
        if (fbo >= 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            int previousFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, ATLAS, ATLAS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            depth = OpenGlHelper.glGenRenderbuffers();
            OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, depth);
            OpenGlHelper.glRenderbufferStorage(OpenGlHelper.GL_RENDERBUFFER, 0x81A6 /* GL_DEPTH_COMPONENT24 */, ATLAS, ATLAS);
            fbo = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT, OpenGlHelper.GL_RENDERBUFFER, depth);
            int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
            if (status != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                Lod.LOG.warn("[gambiarra] LOD: the impostors' FBO did not complete (status {}); level 3 off for this session", status);
            gambiarra.common.StartupReport.failed("lod.impostors", "FBO status " + status);
                deleteAtlas();
                failed = true;
                return false;
            }
            freeSlots.clear();
            for (int i = PER_ROW * PER_ROW - 1; i >= 0; i--) {
                freeSlots.push(new Slot(i));
            }
            atlasCreated++;
            Lod.LOG.info("[gambiarra] LOD: impostor atlas created ({} x {}, {} pieces of {} x {})", ATLAS, ATLAS, PER_ROW * PER_ROW, TILE, TILE);
            return true;
        } catch (Throwable t) {
            Lod.LOG.warn("[gambiarra] LOD: could not create the impostors' FBO ({}); level 3 off for this session", t.toString());
            gambiarra.common.StartupReport.failed("lod.impostors", t.toString());
            deleteAtlas();
            failed = true;
            return false;
        }
    }

    /** The entity's piece goes back to the list (it became a model, vanished or left the LOD). */
    static void release(Lod.State s) {
        if (s.impostor != null) {
            freeSlots.push(s.impostor);
            s.impostor = null;
            liveSlots--;
        }
        if (!s.impostorTarget) {
            s.impostorFade = 0;
        }
    }

    /** World change: deletes the FBO, the texture and the depth (Lod has already forgotten the entities). */
    static void clear() {
        freeSlots.clear();
        liveSlots = 0;
        deleteAtlas();
    }

    private static void deleteAtlas() {
        boolean had = fbo >= 0 || texture >= 0 || depth >= 0;
        if (fbo >= 0) {
            OpenGlHelper.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depth >= 0) {
            OpenGlHelper.glDeleteRenderbuffers(depth);
            depth = -1;
        }
        if (texture >= 0) {
            GL11.glDeleteTextures(texture);
            texture = -1;
        }
        if (had) {
            atlasDeleted++;
        }
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", failed ? "failed (" + failedWhy + ")" : Lod.measureBroken ? "measuring failed" : fbo >= 0 ? "atlas created" : "no atlas");
        m.put("atlasesCreated", atlasCreated);
        m.put("atlasesDeleted", atlasDeleted);
        m.put("piecesInUse", liveSlots);
        m.put("captures", captures);
        m.put("quads", quads);
        m.put("toImage", toImage);
        m.put("toModel", toModel);
        m.put("ditheredFrames", fades);
        m.put("mostSwitchesPerEntity", maxSwitches);
        Map<String, Long> locks = new LinkedHashMap<>();
        for (int i = 0; i < LOCK_KEYS.length; i++) {
            locks.put(LOCK_KEYS[i], LOCKS[i]);
        }
        m.put("safetyLock", locks);
        m.put("safetyLockCutAnImage", lockCutImage);
        m.put("wantedImageNotMeasured", notMeasured);
        return m;
    }

}
