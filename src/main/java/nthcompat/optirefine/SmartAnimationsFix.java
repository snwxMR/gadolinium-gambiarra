// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import nthcompat.CompatStatus;

/**
 * OptiFine's Smart Animations working on OptiRefine (config fixSmartAnimations).
 *
 * Smart Animations only uploads to the GPU, every tick, the animated textures drawn since the previous tick. OptiRefine copied all of
 * it (TextureAtlasSprite.updateAnimation queries SmartAnimations.isSpriteRendered; BufferBuilder and Tessellator note the textures of
 * quads), but:
 *  1. it never calls SmartAnimations.update(), which OptiFine calls in RenderGlobal.loadRenderers: Smart Animations stays always off and
 *     the ~676 animated textures of the atlas are uploaded every tick;
 *  2. it does not note the textures of items in the GUI (and without shaders) in RenderItem.renderQuads, nor those of
 *     BlockModelRenderer.renderModelBrightnessColorQuads (blocks in minecarts, in the enderman's hand, TESR models).
 * And OptiFine itself has a race: SmartAnimations.isActive() = active &amp;&amp; !Shaders.isShadowPass, and isShadowPass belongs to the render
 * thread; sections built on other threads during the shadow pass do not note their textures (in the addon's shader mode, 70-94% of
 * rebuilds with the camera still).
 *
 * What this package does:
 *  - calls SmartAnimations.update() at the start of loadRenderers (if OptiRefine does not do it yet);
 *  - off the render thread, isActive() only goes by "active" (fixes the race);
 *  - notes the missing textures: "sprite" form (default) = the sprite of the quads of renderQuads and of
 *    renderModelBrightnessColorQuads, and the sprite whose coordinates are requested on the render thread (getMinU/MaxU/MinV/MaxV,
 *    getInterpolatedU/V: TESRs, particles); "coordinates" form = the texture coordinates of each immediate draw with the atlas bound
 *    (AtlasCoordinateMarking);
 *  - a texture that went without uploads and is marked again receives its current frame on the next tick (without waiting for the
 *    animation's next frame change); the animation time never stops (OptiRefine already always counts the ticks);
 *  - the config's alwaysAnimate list is always uploaded.
 * Nothirium's sections are marked by Naughthirium; without it (and with Nothirium), nothing is turned on.
 * Nothing changes with OptiFine's Smart Animations option off in the game (update() reads that option).
 */
public final class SmartAnimationsFix {

    private static boolean capabilityChecked;
    private static volatile boolean capable;
    private static String capabilityProblem = "not checked yet";
    private static MethodHandle update, isActive, spriteRendered;
    private static MethodHandle getAnimationIndex;
    private static Field animationActive, isSpriteSingle;

    private static final AtomicLong updateCalls = new AtomicLong(), catchUps = new AtomicLong(), raceCorrections = new AtomicLong(),
            spriteMarks = new AtomicLong(), getterMarks = new AtomicLong(), coordinateMarks = new AtomicLong();

    private SmartAnimationsFix() {
    }

    /** Before RenderGlobal.loadRenderers (as OptiFine does): turns Smart Animations on according to the game's option. */
    public static void beforeLoadRenderers() {
        if (!SmartAnimationsGate.allowed() || !checkCapability() || SmartAnimationsGate.optiRefineCallsUpdate()) {
            return;
        }
        try {
            update.invokeExact();
            updateCalls.incrementAndGet();
            CompatStatus.markRan("Smart Animations", "SmartAnimations.update() in loadRenderers; active=" + (boolean) isActive.invokeExact());
        } catch (Throwable t) {
            capable = false;
            capabilityProblem = "update() failed: " + t;
        }
    }

    /** Checks, once, everything the package uses from OptiFine/OptiRefine. Without it, nothing is turned on. */
    private static synchronized boolean checkCapability() {
        if (capabilityChecked) {
            return capable;
        }
        capabilityChecked = true;
        try {
            Class<?> sa = Class.forName("net.optifine.SmartAnimations");
            // Loads (without initializing) each target: the injection audit happens when the class is transformed.
            ClassLoader loader = SmartAnimationsFix.class.getClassLoader();
            for (String target : new String[] { "net.minecraft.client.renderer.RenderGlobal", "net.minecraft.client.renderer.RenderItem",
                    "net.minecraft.client.renderer.BlockModelRenderer", "net.minecraft.client.renderer.WorldVertexBufferUploader",
                    "net.minecraft.client.renderer.texture.TextureAtlasSprite" }) {
                Class.forName(target, false, loader);
            }
            String missing = SmartAnimationsGate.missingInjections();
            if (missing != null) {
                throw new IllegalStateException("injections that did not apply: " + missing);
            }
            if (!AnimatedSpriteState.class.isAssignableFrom(TextureAtlasSprite.class)) {
                throw new IllegalStateException("TextureAtlasSprite without AnimatedSpriteState");
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            update = lookup.findStatic(sa, "update", MethodType.methodType(void.class));
            isActive = lookup.findStatic(sa, "isActive", MethodType.methodType(boolean.class));
            spriteRendered = lookup.findStatic(sa, "spriteRendered", MethodType.methodType(void.class, int.class));
            lookup.findStatic(sa, "isSpriteRendered", MethodType.methodType(boolean.class, int.class));
            getAnimationIndex = lookup.findVirtual(TextureAtlasSprite.class, "getAnimationIndex", MethodType.methodType(int.class));
            animationActive = TextureAtlasSprite.class.getDeclaredField("animationActive");
            animationActive.setAccessible(true);
            isSpriteSingle = TextureAtlasSprite.class.getDeclaredField("isSpriteSingle");
            isSpriteSingle.setAccessible(true);
            capable = true;
            capabilityProblem = "none";
        } catch (Throwable t) {
            capable = false;
            capabilityProblem = "missing " + t;
            CompatStatus.LOG.warn("[gambiarra] Smart Animations does not turn on: something the package uses is missing ({}); nothing changes", t.toString());
            gambiarra.common.StartupReport.failed("fixSmartAnimations", "missing " + t);
        }
        return capable;
    }

    /**
     * Everything the package uses was checked (at the first loadRenderers), including the injections of all its mixins. Before that, and
     * if anything is missing, false: the handlers do nothing.
     */
    public static boolean capable() {
        return capable;
    }

    /** Smart Animations is active now, on the render thread and outside the shadow pass (when OptiFine marks). */
    private static boolean markingNow() {
        if (!capable) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) {
            return false;
        }
        try {
            return (boolean) isActive.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void mark(int index, AtomicLong counter) {
        try {
            spriteRendered.invokeExact(index);
            counter.incrementAndGet();
        } catch (Throwable t) {
            // nothing
        }
    }

    /**
     * The sprite's animation index (-1 = not animated), stored in it. Read from OptiRefine the first time and, for animated ones, again
     * every tick (beforeUpdate): OptiRefine renumbers the animations when the atlas is built again (F3+T), and some mods reuse the same
     * sprite object.
     */
    public static int index(TextureAtlasSprite sprite) {
        int idx = ((AnimatedSpriteState) sprite).nthcompat$animIndex();
        return idx != AnimatedSpriteState.UNKNOWN ? idx : refreshIndex(sprite);
    }

    private static int refreshIndex(TextureAtlasSprite sprite) {
        if (!capable) {
            return -1;
        }
        int idx;
        try {
            idx = (int) getAnimationIndex.invokeExact(sprite);
        } catch (Throwable t) {
            idx = -1;
        }
        AnimatedSpriteState s = (AnimatedSpriteState) sprite;
        if (idx != s.nthcompat$animIndex()) {
            s.nthcompat$setAlwaysAnimate(idx >= 0 && matchesAlways(sprite.getIconName()));
            s.nthcompat$setAnimIndex(idx);
        }
        return idx;
    }

    private static boolean matchesAlways(String name) {
        for (String p : SmartAnimationsGate.alwaysPatterns()) {
            if (p.endsWith("*") ? name.startsWith(p.substring(0, p.length() - 1)) : name.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** "sprite" form: the quads of renderQuads / renderModelBrightnessColorQuads. */
    public static void markQuads(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty() || !markingNow()) {
            return;
        }
        for (int i = 0, n = quads.size(); i < n; i++) {
            TextureAtlasSprite sprite = quads.get(i).getSprite();
            if (sprite != null) {
                int idx = index(sprite);
                if (idx >= 0) {
                    mark(idx, spriteMarks);
                }
            }
        }
    }

    /** "sprite" form: someone asked for the coordinates of an animated sprite (the cheap index test has already been done). */
    public static void markFromGetter(int index) {
        if (markingNow()) {
            mark(index, getterMarks);
        }
    }

    /** "coordinates" form (AtlasCoordinateMarking). */
    static void markFromCoordinates(int index) {
        mark(index, coordinateMarks);
    }

    static boolean markingNowForCoordinates() {
        return markingNow();
    }

    /** Before TextureAtlasSprite.updateAnimation (animated ones only, every tick): index up to date; alwaysAnimate counts as drawn. */
    public static void beforeUpdate(TextureAtlasSprite sprite) {
        if (!capable) {
            return;
        }
        int idx = refreshIndex(sprite);
        if (idx >= 0 && ((AnimatedSpriteState) sprite).nthcompat$alwaysAnimate()) {
            mark(idx, spriteMarks);
        }
    }

    /** OptiRefine decided to upload this sprite this tick (Smart Animations off = always). */
    public static boolean animationActive(TextureAtlasSprite sprite) {
        try {
            return !capable || animationActive.getBoolean(sprite);
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    /** The "clamp" OptiRefine passes when uploading this sprite (standalone sprites of multitexture mode). */
    public static boolean spriteSingle(TextureAtlasSprite sprite) {
        try {
            return capable && isSpriteSingle.getBoolean(sprite);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static void caughtUp() {
        catchUps.incrementAndGet();
    }

    /** SmartAnimationsActiveMixin: the answer of isActive() off the render thread was fixed. */
    public static void raceCorrected() {
        raceCorrections.incrementAndGet();
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", SmartAnimationsGate.reason());
        m.put("capability", capabilityProblem);
        m.put("injections", SmartAnimationsGate.auditResults());
        m.put("optiRefineAlreadyCallsUpdate", SmartAnimationsGate.optiRefineCallsUpdate());
        m.put("optiRefineAlreadyMarksBrightnessQuads", SmartAnimationsGate.optiRefineMarksBrightnessQuads());
        m.put("updateCalled", updateCalls.get());
        boolean active = false;
        try {
            active = capable && (boolean) isActive.invokeExact();
        } catch (Throwable t) {
            // nothing
        }
        m.put("activeNow", active);
        m.put("marksSprite", spriteMarks.get());
        m.put("marksGetter", getterMarks.get());
        m.put("marksCoordinates", coordinateMarks.get());
        m.put("currentFrameForced", catchUps.get());
        m.put("raceFixed", raceCorrections.get());
        return m;
    }

}
