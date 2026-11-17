// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import meldexun.renderlib.renderer.EntityRenderManager;
import meldexun.renderlib.renderer.TileEntityRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.optifine.shaders.Shaders;
import net.minecraft.client.renderer.RenderGlobal;
import nthshaders.common.DevHooks;
import nthshaders.compat.ShadersCompat;

/**
 * Decides the entity fix mode by CAPABILITY (not by OptiRefine's fingerprint) and keeps the members OptiRefine's
 * renderEntities uses.
 *
 * The members OptiFine/OptiRefine add to vanilla classes (counters, item frame and sign distance, "off" fog, block damage
 * mark) are not on the compile classpath (the compile-time Minecraft is vanilla), so they are looked up by reflection, ONCE,
 * on the first renderEntities, when RenderGlobal is already transformed.
 *
 *  - Something both modes use is missing (Shaders hooks, RenderLib API): OFF.
 *  - A member only FULL uses is missing: FALLBACK.
 *  - Everything found: FULL, even with an unrecognized OptiRefine (with a warning).
 *
 * entityFixFastAccess: the members used every frame become MethodHandles, found at the same time. Through reflection
 * (Field.setInt, Method.invoke), on Java 25 Cleanroom routes each use through CompatibilityHooks, which recomputes the field
 * position (Unsafe.objectFieldOffset) every time. A field MethodHandle keeps the position. Same values.
 */
public final class EntityFixSupport {

    private static final String SHADERS = "net.optifine.shaders.Shaders";
    private static final boolean VALIDATE = nthshaders.common.ValidationLog.on();
    private static final String ICAMERA = "net.minecraft.client.renderer.culling.ICamera";
    private static final String ENTITY_MANAGER = "meldexun.renderlib.renderer.EntityRenderManager";
    private static final String TILE_MANAGER = "meldexun.renderlib.renderer.TileEntityRenderManager";

    private static volatile ShadersCompat.EntityMode mode;
    private static Field renderEntitiesCounter;
    private static Field countTileEntitiesRendered;
    private static Field renderOverlayDamaged;
    private static Field fogStandard;
    private static Method updateItemRenderDistance;
    private static Method updateTextRenderDistance;
    private static Method isFogOff;
    /** The same members through MethodHandle (entityFixFastAccess); null = use the reflection above. */
    private static MethodHandle getRenderEntitiesCounter, setRenderEntitiesCounter, setCountTileEntitiesRendered,
            setRenderOverlayDamaged, getFogStandard, callIsFogOff, callUpdateItemRenderDistance, callUpdateTextRenderDistance;
    /** Missing members that only hurt FULL. */
    private static final Set<String> missing = Collections.synchronizedSet(new LinkedHashSet<>());

    private EntityFixSupport() {
    }

    /** Entity mode; decided on the first call. */
    public static ShadersCompat.EntityMode mode() {
        ShadersCompat.EntityMode m = mode;
        return m != null ? m : resolveMode();
    }

    private static synchronized ShadersCompat.EntityMode resolveMode() {
        if (mode != null) {
            return mode;
        }
        // Used by both modes.
        Set<String> missingBase = new LinkedHashSet<>();
        requireStaticField(missingBase, SHADERS, "shaderPackLoaded");
        for (String hook : new String[] { "beginEntities", "endEntities", "beginBlockEntities", "endBlockEntities" }) {
            requireMethod(missingBase, SHADERS, hook);
        }
        for (String manager : new String[] { ENTITY_MANAGER, TILE_MANAGER }) {
            requireMethod(missingBase, manager, "setup", ICAMERA, "float");
            requireMethod(missingBase, manager, "reset");
        }
        requireMethod(missingBase, ENTITY_MANAGER, "renderEntities", "float");
        requireMethod(missingBase, TILE_MANAGER, "renderTileEntities", "float");

        // Used only by FULL.
        renderEntitiesCounter = field(RenderGlobal.class, "renderEntitiesCounter");
        countTileEntitiesRendered = field(RenderGlobal.class, "countTileEntitiesRendered");
        renderOverlayDamaged = field(RenderGlobal.class, "renderOverlayDamaged");
        fogStandard = field(EntityRenderer.class, "fogStandard");
        updateItemRenderDistance = method("net.minecraft.client.renderer.entity.RenderItemFrame", "updateItemRenderDistance");
        updateTextRenderDistance = method("net.minecraft.client.renderer.tileentity.TileEntitySignRenderer", "updateTextRenderDistance");
        isFogOff = method("Config", "isFogOff");
        requireMethod(missing, SHADERS, "nextBlockEntity", "net.minecraft.tileentity.TileEntity");
        requireMethod(missing, ENTITY_MANAGER, "renderedEntities");
        requireMethod(missing, ENTITY_MANAGER, "occludedEntities");
        if (nthshaders.core.NthConfig.entityFixFastAccess) {
            handles();
        }
        if (DevHooks.ON) {
            // Development build only: simulates a missing member, to exercise FALLBACK.
            if (Boolean.getBoolean("nthcompat.testEntityFallback")) {
                missing.add("[TEST] simulated missing member (-Dnthcompat.testEntityFallback=true)");
            }
        }

        ShadersCompat.EntityMode chosen;
        String reason;
        if (!missingBase.isEmpty()) {
            chosen = ShadersCompat.EntityMode.OFF;
            reason = "OptiFine hooks or the RenderLib API are missing: " + missingBase;
            ShadersCompat.LOG.error("[gadolinium] Entity fix OFF, {}. With Nothirium + OptiRefine, entities will be invisible until the addon "
                    + "is updated for these versions.", reason);
            nthshaders.common.StartupReport.failed("fixEntityRendering", reason);
        } else if (!missing.isEmpty()) {
            chosen = ShadersCompat.EntityMode.FALLBACK;
            reason = "members used by FULL mode are missing: " + missing;
            ShadersCompat.LOG.warn("[gadolinium] Entities in FALLBACK mode (last resort), {}", reason);
        } else {
            chosen = ShadersCompat.EntityMode.FULL;
            reason = "all members found";
            if (!ShadersCompat.optiRefineRecognized()) {
                reason += " (OptiRefine not recognized by fingerprint)";
                ShadersCompat.LOG.warn("[gadolinium] OptiRefine not recognized by fingerprint, but every member FULL mode uses was found: "
                        + "entities in FULL mode.");
            }
        }
        ShadersCompat.setEntityMode(chosen, reason);
        mode = chosen;
        ShadersCompat.LOG.info("[gadolinium] Entity mode: {} ({})", chosen, reason);
        return chosen;
    }

    /**
     * MethodHandles of the members found (missing ones stay null and go through reflection, which is also null: the method
     * does nothing, as before). With the exact types, for invokeExact.
     */
    private static void handles() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            if (renderEntitiesCounter != null && Modifier.isStatic(renderEntitiesCounter.getModifiers())
                    && renderEntitiesCounter.getType() == int.class) {
                getRenderEntitiesCounter = lookup.unreflectGetter(renderEntitiesCounter);
                setRenderEntitiesCounter = lookup.unreflectSetter(renderEntitiesCounter);
            }
            if (countTileEntitiesRendered != null && countTileEntitiesRendered.getType() == int.class) {
                setCountTileEntitiesRendered = lookup.unreflectSetter(countTileEntitiesRendered);
            }
            if (renderOverlayDamaged != null && renderOverlayDamaged.getType() == boolean.class) {
                setRenderOverlayDamaged = lookup.unreflectSetter(renderOverlayDamaged);
            }
            if (fogStandard != null && fogStandard.getType() == boolean.class) {
                getFogStandard = lookup.unreflectGetter(fogStandard);
            }
            if (isFogOff != null && isFogOff.getReturnType() == boolean.class && Modifier.isStatic(isFogOff.getModifiers())) {
                callIsFogOff = lookup.unreflect(isFogOff);
            }
            if (updateItemRenderDistance != null && Modifier.isStatic(updateItemRenderDistance.getModifiers())) {
                callUpdateItemRenderDistance = lookup.unreflect(updateItemRenderDistance)
                        .asType(java.lang.invoke.MethodType.methodType(void.class));
            }
            if (updateTextRenderDistance != null && Modifier.isStatic(updateTextRenderDistance.getModifiers())) {
                callUpdateTextRenderDistance = lookup.unreflect(updateTextRenderDistance)
                        .asType(java.lang.invoke.MethodType.methodType(void.class));
            }
        } catch (Throwable t) {
            getRenderEntitiesCounter = setRenderEntitiesCounter = setCountTileEntitiesRendered = setRenderOverlayDamaged = null;
            getFogStandard = callIsFogOff = callUpdateItemRenderDistance = callUpdateTextRenderDistance = null;
            ShadersCompat.LOG.warn("[gadolinium] entityFixFastAccess: MethodHandle unavailable ({}); using reflection", t.toString());
            nthshaders.common.StartupReport.failed("entityFixFastAccess", t.toString());
        }
    }

    private static void check(boolean same, String what) {
        nthshaders.common.ValidationLog.check("compat.entityFixFastAccess", same, what + ": the MethodHandle and reflection disagree");
    }

    public static void addRenderEntitiesCounter(int delta) {
        if (getRenderEntitiesCounter != null) {
            try {
                int value = (int) getRenderEntitiesCounter.invokeExact();
                if (VALIDATE) {
                    check(value == renderEntitiesCounter.getInt(null), "renderEntitiesCounter");
                }
                setRenderEntitiesCounter.invokeExact(value + delta);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (renderEntitiesCounter != null) {
            try {
                renderEntitiesCounter.setInt(null, renderEntitiesCounter.getInt(null) + delta);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    public static void resetCountTileEntitiesRendered(RenderGlobal renderGlobal) {
        if (setCountTileEntitiesRendered != null) {
            try {
                setCountTileEntitiesRendered.invokeExact(renderGlobal, 0);
                if (VALIDATE) {
                    check(countTileEntitiesRendered.getInt(renderGlobal) == 0, "countTileEntitiesRendered");
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        if (countTileEntitiesRendered != null) {
            try {
                countTileEntitiesRendered.setInt(renderGlobal, 0);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    public static void setRenderOverlayDamaged(RenderGlobal renderGlobal, boolean value) {
        if (setRenderOverlayDamaged != null) {
            try {
                setRenderOverlayDamaged.invokeExact(renderGlobal, value);
                if (VALIDATE) {
                    check(renderOverlayDamaged.getBoolean(renderGlobal) == value, "renderOverlayDamaged");
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        if (renderOverlayDamaged != null) {
            try {
                renderOverlayDamaged.setBoolean(renderGlobal, value);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /** Config.isFogOff() && entityRenderer.fogStandard, as in OptiFine. */
    public static boolean isFogOffStandard(EntityRenderer entityRenderer) {
        if (callIsFogOff != null && getFogStandard != null) {
            try {
                boolean fast = (boolean) callIsFogOff.invokeExact() && (boolean) getFogStandard.invokeExact(entityRenderer);
                if (VALIDATE) {
                    check(fast == ((Boolean) isFogOff.invoke(null) && fogStandard.getBoolean(entityRenderer)), "isFogOffStandard");
                }
                return fast;
            } catch (Throwable t) {
                return false;
            }
        }
        if (isFogOff == null || fogStandard == null) {
            return false;
        }
        try {
            return (Boolean) isFogOff.invoke(null) && fogStandard.getBoolean(entityRenderer);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static void updateItemRenderDistance() {
        if (callUpdateItemRenderDistance != null) {
            try {
                callUpdateItemRenderDistance.invokeExact();
            } catch (Throwable ignored) {
            }
            return;
        }
        invokeStatic(updateItemRenderDistance);
    }

    public static void updateTextRenderDistance() {
        if (callUpdateTextRenderDistance != null) {
            try {
                callUpdateTextRenderDistance.invokeExact();
            } catch (Throwable ignored) {
            }
            return;
        }
        invokeStatic(updateTextRenderDistance);
    }

    /**
     * FALLBACK mode: after OptiRefine's renderEntities (which already prepared RenderManager and TileEntityRendererDispatcher,
     * and drew global entities and block damage), draws the chunks' entities and tile entities through RenderLib, with the same
     * OptiFine shader hooks.
     */
    public static void renderChunkEntitiesAfterOptiRefine(Minecraft mc, WorldClient world, float partialTicks, int pass) {
        world.profiler.startSection("nthcompat_entities");
        mc.entityRenderer.enableLightmap();
        boolean shaders = Shaders.shaderPackLoaded;
        if (shaders) {
            Shaders.beginEntities();
        }
        EntityRenderManager.renderEntities(partialTicks);
        if (shaders) {
            Shaders.endEntities();
            Shaders.beginBlockEntities();
        }
        RenderHelper.enableStandardItemLighting();
        TileEntityRendererDispatcher.instance.preDrawBatch();
        TileEntityRenderManager.renderTileEntities(partialTicks);
        TileEntityRendererDispatcher.instance.drawBatch(pass);
        if (shaders) {
            Shaders.endBlockEntities();
        }
        mc.entityRenderer.disableLightmap();
        world.profiler.endSection();
    }

    private static void invokeStatic(Method m) {
        if (m != null) {
            try {
                m.invoke(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            missing.add(owner.getSimpleName() + "." + name);
            return null;
        }
    }

    private static Method method(String owner, String name) {
        try {
            Method m = Class.forName(owner, false, EntityFixSupport.class.getClassLoader()).getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            missing.add(owner + "." + name + "()");
            return null;
        }
    }

    private static void requireStaticField(Set<String> absent, String owner, String name) {
        try {
            Field f = Class.forName(owner, false, EntityFixSupport.class.getClassLoader()).getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                absent.add(owner + "." + name + " (not static)");
            }
        } catch (Throwable t) {
            absent.add(owner + "." + name);
        }
    }

    /** Public static method with exactly these parameters (class names or "float"). */
    private static void requireMethod(Set<String> absent, String owner, String name, String... params) {
        String signature = owner + "." + name + "(" + String.join(", ", params) + ")";
        try {
            ClassLoader loader = EntityFixSupport.class.getClassLoader();
            Class<?>[] types = new Class<?>[params.length];
            for (int i = 0; i < params.length; i++) {
                types[i] = "float".equals(params[i]) ? float.class : Class.forName(params[i], false, loader);
            }
            Method m = Class.forName(owner, false, loader).getMethod(name, types);
            if (!Modifier.isStatic(m.getModifiers())) {
                absent.add(signature + " (not static)");
            }
        } catch (Throwable t) {
            absent.add(signature);
        }
    }

}
