// SPDX-License-Identifier: MIT
package nthcompat;

import java.io.File;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import gambiarra.common.ConfigMigration;
import gambiarra.common.DevHooks;

/**
 * Config of Gambiarra's fixes (config/gambiarra.cfg): OptiRefine, OptiFine, Ice and Fire, dynamic lights. Read early, by the mixin
 * plugin.
 *
 * Migration from old versions (when it was Nothirium Shaders' nthcompat.cfg): on first start, with no gambiarra.cfg and an
 * nthcompat.cfg present, the old file is copied (same keys, values and sections) and the keys that moved to Gadolinium leave
 * the copy (MOVED_TO_SHADERS). nthcompat.cfg stays untouched; the log says where it came from.
 */
public final class CompatConfig {

    public static final String FILE_NAME = "gambiarra.cfg";
    /** The old config (before the split into Nothirium Shaders and Gambiarra). */
    public static final String OLD_FILE_NAME = "nthcompat.cfg";
    /** nthcompat.cfg keys that moved to Gadolinium (compat section of gadolinium.cfg). */
    static final String[] MOVED_TO_SHADERS = { "fixUpdateClouds", "fixEntityRendering", "limitChunkCompileBuffers",
            "chunkCompileBuffersPerThread", "chunkCompileThreads", "chunkCompileThreadPriority", "chunkCompileOrder" };

    public static boolean fixRenderLibOptiRefine = true;
    /** TEMPORARY: until OptiRefine fixes rawFloatBuffer (see BufferBuilderFloatViewMixin). */
    public static boolean fixOptiRefineFloatBuffer = true;
    /** With fixOptiRefineFloatBuffer: the anchored view is reused, with no new objects per vertex. */
    public static boolean fixOptiRefineFloatBufferReuseView = true;
    /** TEMPORARY: until OptiRefine translates the acc$ placeholders (see optirefine.AccPlaceholderFix). */
    public static boolean fixOptiRefineAccPlaceholders = true;
    /** OptiRefine v0.1.0-alpha x VintageFix: the game does not start (see optirefine.ResourceProbePriorityFix). */
    public static boolean fixOptiRefineVintageFix = true;
    /** OptiRefine 78ff8ce: the lightmap goes black after turning a shader pack on and off (see mixin.DynamicTextureDataMixin). */
    public static boolean fixOptiRefineDynamicTextureData = true;
    /** General fix, not tied to Nothirium: SmartLeaves leaves with LoliASM (see SmartLeavesDoubleFaceMixin). */
    public static boolean fixSmartLeavesVertexData = true;
    /** OptiFine's Smart Animations working on OptiRefine (see optirefine.SmartAnimationsFix). */
    public static boolean fixSmartAnimations = true;
    /** How to mark the animated textures in use that OptiRefine does not mark: "sprite" (default) or "coordinates". */
    public static String smartAnimationsMarking = "sprite";
    /** Textures always updated (sprite name; "*" at the end = prefix). */
    public static String[] smartAnimationsAlwaysAnimate = new String[0];
    /** Ice and Fire (nthcompat.iceandfire.TabulaPoseCache): Tabula animation without looking parts up by name every frame. */
    public static boolean iceAndFireTabulaPoseCache = true;
    /** Ice and Fire (nthcompat.iceandfire.DistantDragonAnimation): distant dragons animated every N frames. */
    public static boolean iceAndFireDistantDragonAnimation = false;
    public static int iceAndFireDistantDragonDistance = 64;
    public static int iceAndFireDistantDragonEveryFrames = 2;
    /** OptiFine dynamic lights (nthcompat.oflights.OfLightsSnapshot): the per-block lookup without the global lock. */
    public static boolean optifineDynamicLightsSnapshot = true;
    /** OptiFine dynamic lights (nthcompat.oflights.OfLightsGate): each source only updates when it changes block or level. Off by default (it changes how the light moves). */
    public static boolean optifineDynamicLightsBlockStep = false;
    /** OptiFine dynamic lights with Nothirium (nthcompat.oflights.OfLightsGate): only the sections the light reaches and that have blocks. */
    public static boolean optifineDynamicLightsReachedOnly = true;
    /** Pulsar 0.6.1+ (nthcompat.pulsar.SmoothLightScratch): the smooth lighting crash with OptiRefine. */
    public static boolean pulsarFixSmoothLightCrash = true;

    /** Entity LOD (gambiarra.lod): parts smaller than lodPartPixels on screen are not drawn. */
    public static boolean lodSubPixelParts = true;
    public static float lodPartPixels = 1.0f;
    /** Entity LOD: the pose is only recomputed every N frames when the entity is small on screen. */
    public static boolean lodAnimationBySize = true;
    public static int lodAnimationFullPixels = 64;
    public static int lodAnimationMaxFrames = 4;
    /** Entity LOD: impostors (an image of the entity instead of the model), without a shader pack. Off by default. */
    public static boolean lodImpostors = false;
    public static int lodImpostorPixels = 40;
    public static int lodImpostorUpdateFrames = 6;
    public static int lodImpostorFadeFrames = 8;
    public static int lodImpostorMinDistance = 32;
    public static float lodImpostorEdgeAlpha = 0.5f;
    /** LOD exceptions: mod ids ("lycanitesmobs") and entity ids ("minecraft:zombie"; "*" at the end = prefix). */
    public static String[] lodExcludeMods = new String[0];
    public static String[] lodExcludeEntities = new String[0];

    static final String ICE_AND_FIRE = "iceandfire";
    static final String OPTIFINE_LIGHTS = "optifinedynamiclights";
    static final String PULSAR = "pulsar";
    static final String LOD = "lod";

    private static boolean loaded;

    private CompatConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File home = Launch.minecraftHome != null ? Launch.minecraftHome : new File(".");
        File file = new File(new File(home, "config"), FILE_NAME);
        boolean migrated = migrateFromNthcompat(file);
        Configuration cfg = new Configuration(file);
        cfg.load();
        if (migrated) {
            ConfigCategory moved = cfg.getCategory(Configuration.CATEGORY_GENERAL);
            for (String key : MOVED_TO_SHADERS) {
                moved.remove(key);
            }
        }
        ConfigMigration migration = new ConfigMigration(cfg, CompatStatus.LOG, "[gambiarra]", FILE_NAME);
        migration.renameValue(Configuration.CATEGORY_GENERAL, "smartAnimationsMarking", "coordenadas", "coordinates");
        migration.removeKey(Configuration.CATEGORY_GENERAL, OBSOLETE_FORCE_FINGERPRINT, "the fixes no longer depend on OptiRefine's fingerprint");
        fixRenderLibOptiRefine = cfg.getBoolean("fixRenderLibOptiRefine", Configuration.CATEGORY_GENERAL, true,
                "With OptiRefine present, removes RenderLib's patch on RenderGlobal#renderEntities, which looks for pieces "
                        + "of classic OptiFine that OptiRefine does not have (without this the game does not start). The patch would "
                        + "be ignored with OptiRefine anyway, since it reimplements renderEntities.");
        fixOptiRefineFloatBuffer = cfg.getBoolean("fixOptiRefineFloatBuffer", Configuration.CATEGORY_GENERAL, true,
                "TEMPORARY, until OptiRefine fixes it: with shaders, OptiRefine rebuilds BufferBuilder's float view from "
                        + "the current buffer position, and OptiFine's normal computation writes over model vertices (bent chest "
                        + "lids, stretched strips coming out of mobs). This fix anchors the view at the start of the buffer.");
        fixOptiRefineFloatBufferReuseView = cfg.getBoolean("fixOptiRefineFloatBufferReuseView", Configuration.CATEGORY_GENERAL, true,
                "With fixOptiRefineFloatBuffer: each BufferBuilder's anchored view is kept and reused, and only rebuilt "
                        + "when the buffer grows. Off: two new views per vertex (more garbage for the collector; same image).");
        fixOptiRefineAccPlaceholders = cfg.getBoolean("fixOptiRefineAccPlaceholders", Configuration.CATEGORY_GENERAL, true,
                "TEMPORARY, until OptiRefine fixes it: OptiRefine deletes the 'placeholder' fields (acc$...) of its mixins "
                        + "without replacing the references to them with the real name, and the game crashes (NoSuchFieldError) when "
                        + "releasing zoom and in water or lava with custom resource pack colours. This fix replaces those references "
                        + "with the real name OptiRefine itself declares, only when the real field exists with the same type; each "
                        + "replacement goes to the log and to logs/gadolinium-diagnostics.txt.");
        fixOptiRefineVintageFix = cfg.getBoolean("fixOptiRefineVintageFix", Configuration.CATEGORY_GENERAL, true,
                "Crash fix, until OptiRefine fixes it: OptiRefine v0.1.0-alpha and VintageFix's resource pack optimization "
                        + "(mixin.resourcepacks) both change DefaultResourcePack.resourceExists, and the game does not start. With "
                        + "this on, OptiRefine's mixin counts as one priority above VintageFix's on that class only, so both apply as "
                        + "they are written. Only acts with the exact OptiRefine v0.1.0-alpha and VintageFix 0.7.2 code; the log "
                        + "says what it did.");
        fixOptiRefineDynamicTextureData = cfg.getBoolean("fixOptiRefineDynamicTextureData", Configuration.CATEGORY_GENERAL, true,
                "Fix for OptiRefine 78ff8ce (v0.1.0-alpha already fixes it): after a shader pack is turned on and off in the "
                        + "same session, the terrain stays black and the item in the hand disappears, until the game restarts. "
                        + "The first time shaders upload a dynamic texture made before them (the lightmap, maps), OptiRefine swaps "
                        + "its data array for a new one, and the game keeps writing the old one. With this on, each dynamic texture "
                        + "gets the larger array when it is made, as OptiFine and OptiRefine v0.1.0-alpha do, so it never changes. "
                        + "Only acts with the exact OptiRefine 78ff8ce code.");
        fixSmartLeavesVertexData = cfg.getBoolean("fixSmartLeavesVertexData", Configuration.CATEGORY_GENERAL, true,
                "General fix, not tied to Nothirium: OptiFine's SmartLeaves builds the back of leaf faces by reversing, in "
                        + "place, a copy of the vertices. With LoliASM (vertexDataCanonicalization=true, the default) that copy is "
                        + "the leaf model itself, which ends up inside out: the face towards the camera disappears and the back one "
                        + "shows. This fix gives the back its own array and reverses nothing in place. Without LoliASM, the result "
                        + "is the same as OptiFine's.");
        fixSmartAnimations = cfg.getBoolean("fixSmartAnimations", Configuration.CATEGORY_GENERAL, true,
                "Makes OptiFine's Smart Animations option (Video > Performance) work on OptiRefine: only the animated "
                        + "textures drawn since the last tick are uploaded to the GPU, instead of all of them every tick. OptiRefine "
                        + "never turns Smart Animations on and does not mark items in the GUI or renderModelBrightnessColorQuads "
                        + "models; this fix turns it on, marks what is missing, fixes the race with the shadow pass (which would "
                        + "stop animations with shaders) and, when a texture is drawn again, uploads its current frame on the next "
                        + "tick. With Nothirium, it needs Naughthirium (which marks the terrain sections). With the game's Smart "
                        + "Animations option off, nothing changes.");
        smartAnimationsMarking = cfg.getString("smartAnimationsMarking", Configuration.CATEGORY_GENERAL, "sprite",
                "With fixSmartAnimations: how to mark the animated textures OptiRefine does not mark. 'sprite' = by the "
                        + "texture of each item and model quad, and when something asks for its coordinates (TESRs, particles). "
                        + "'coordinates' = by the texture coordinates of each immediate draw with the atlas bound (also catches "
                        + "whoever keeps the coordinates and draws without asking again). Both cost less than 0.003 ms per frame.",
                new String[] { "sprite", "coordinates" });
        smartAnimationsAlwaysAnimate = cfg.getStringList("smartAnimationsAlwaysAnimate", Configuration.CATEGORY_GENERAL, new String[0],
                "With fixSmartAnimations: animated textures that are always updated, for what no marking sees (a mod that "
                        + "records the draw in a display list or its own buffer). Texture name (e.g. minecraft:blocks/lava_still); "
                        + "'*' at the end works as a prefix (e.g. rustic:blocks/fluids/*).");
        cfg.setCategoryComment(ICE_AND_FIRE, "Ice and Fire 1.9.1 (with LLibrary 1.7.20). With another version, the options in this section turn "
                + "themselves off and the log says so.");
        iceAndFireTabulaPoseCache = cfg.getBoolean("cacheTabulaPoses", ICE_AND_FIRE, true,
                "Ice and Fire's fire and ice dragons and sea serpent go, every frame, through all possible animations "
                        + "(bite, roar, breathe...), even those not playing, and in each one look up every model part (~100) by "
                        + "name, several times: tens of thousands of lookups per frame with a few dragons around. This option "
                        + "skips the animations not playing (LLibrary does nothing in them) and stores, once per model, the parts "
                        + "the animators look up by name. The animation comes out identical.");
        iceAndFireDistantDragonAnimation = cfg.getBoolean("distantDragonAnimation", ICE_AND_FIRE, false,
                "Dragons farther than distantDragonAnimationDistance blocks from the camera only recompute their pose "
                        + "every distantDragonAnimationEveryFrames frames (on the others, they repeat the last one). Client only: "
                        + "AI, collision and hitbox are not touched. From afar, the animation is less smooth (with 2, the pose "
                        + "changes on half of the frames).");
        iceAndFireDistantDragonDistance = cfg.getInt("distantDragonAnimationDistance", ICE_AND_FIRE, 64, 8, 512,
                "With distantDragonAnimation: distance (blocks) from which the dragon is animated every N frames.");
        iceAndFireDistantDragonEveryFrames = cfg.getInt("distantDragonAnimationEveryFrames", ICE_AND_FIRE, 2, 2, 10,
                "With distantDragonAnimation: a distant dragon's pose is recomputed every N frames.");
        cfg.setCategoryComment(OPTIFINE_LIGHTS, "OptiFine dynamic lights (Video > Quality > Dynamic Lights), OptiFine 1.12.2 HD U G5. With another "
                + "version, the options in this section turn themselves off and the log says so.");
        optifineDynamicLightsSnapshot = cfg.getBoolean("lockFreeLookup", OPTIFINE_LIGHTS, true,
                "When each terrain section is built, each block asks OptiFine how much dynamic light it receives, and "
                        + "OptiFine walks all sources (torches in hand, burning mobs...) under a lock shared by the build threads, "
                        + "which end up waiting for each other. With this option, after each update of the sources (every 50 ms) a "
                        + "copy of them is kept, and lookups use the copy, without the lock. Same math, same result.");
        optifineDynamicLightsBlockStep = cfg.getBoolean("updatePerBlock", OPTIFINE_LIGHTS, false,
                "Each dynamic light source that moves more than 0.1 block makes the surrounding terrain (8 sections) "
                        + "rebuild, up to 20 times per second in Fancy mode. With this option, the source only updates when it "
                        + "changes block or light level: the light moves block by block instead of smoothly (in Fast mode it already "
                        + "only changes every half second), and terrain is rebuilt far less often with burning mobs running around. "
                        + "Off by default: it changes how the light moves.");
        optifineDynamicLightsReachedOnly = cfg.getBoolean("reachedSectionsOnly", OPTIFINE_LIGHTS, true,
                "With Nothirium: when a dynamic light source moves, it rebuilds 8 sections around it (a 32-block cube), "
                        + "but OptiFine's light only reaches 7.5 blocks. With this option, only the sections the light reaches (at "
                        + "the old or the new position) and that have some block are rebuilt; the others would give the same result. "
                        + "The image does not change.");
        cfg.setCategoryComment(PULSAR, "Pulsar 0.6.1 and later. On a new version, the options in this section stay on while the code they depend "
                + "on is the same; if it changes, they turn themselves off and the log says so.");
        pulsarFixSmoothLightCrash = cfg.getBoolean("fixSmoothLightCrash", PULSAR, true,
                "Crash fix. With smooth lighting on, Pulsar 0.6.1 removes from each face's corners the light hidden behind "
                        + "a block (stairs, slabs), using a scratch position it keeps on each face. With OptiRefine, faces are "
                        + "created by a constructor OptiRefine adds, where that position stays empty: the game crashes building "
                        + "chunks (NullPointerException in SmoothLightOcclusion.hiddenSamples). This fix hands out a thread-local "
                        + "scratch position when Pulsar's is missing; the math is the same, and Pulsar's feature works.");
        cfg.setCategoryComment(LOD, "Entity LOD (image only: AI, collision, hits and sound do not change). Never on players, bosses, named "
                + "mobs, mounts and the entity under the crosshair. Each level has its own option.");
        lodSubPixelParts = cfg.getBoolean("subPixelParts", LOD, true,
                "Level 1: a model part (eye, tooth, claw...) smaller than partPixels on screen is not drawn. The size is "
                        + "measured with the real field of view (including OptiFine's zoom) and the window resolution. Applies to "
                        + "vanilla, Tabula (Ice and Fire) and .obj (Lycanites) models. Not in the shadow pass.");
        lodPartPixels = (float) cfg.getFloat("partPixels", LOD, 1.0f, 0.25f, 4.0f,
                "With subPixelParts: the on-screen size (pixels) below which the part disappears. It only comes back above "
                        + "1.3 times this (hysteresis: no blinking at the limit).");
        lodAnimationBySize = cfg.getBoolean("animationBySize", LOD, true,
                "Level 2: an entity small on screen recomputes its pose (the animation) every N frames and repeats the "
                        + "last one on the others. N = animationFullPixels / height on screen, from 1 to animationMaxFrames. Only on "
                        + "models whose whole pose comes from a single place: Tabula (Ice and Fire) and .obj (Lycanites). On vanilla "
                        + "and other mods' models the pose is computed in several places (the model, the armour, the layers) and a "
                        + "repeated pose would leave the armour out of step with the body.");
        lodAnimationFullPixels = cfg.getInt("animationFullPixels", LOD, 64, 8, 1024,
                "With animationBySize: above this on-screen height (pixels), the pose is recomputed every frame.");
        lodAnimationMaxFrames = cfg.getInt("animationMaxFrames", LOD, 4, 1, 10,
                "With animationBySize: at most every how many frames the pose of a very small entity is recomputed.");
        lodImpostors = cfg.getBoolean("impostors", LOD, false,
                "Level 3, EXPERIMENTAL: an entity smaller than impostorPixels on screen is drawn as an image of itself, "
                        + "redone from time to time, with a dithered transition to the model. Only without a shader pack. Uses ~32 "
                        + "MB of video memory while the world is open.");
        lodImpostorPixels = cfg.getInt("impostorPixels", LOD, 40, 8, 128,
                "With impostors: below this on-screen height (pixels), the image; above 1.2 times this, the model.");
        lodImpostorUpdateFrames = cfg.getInt("impostorUpdateFrames", LOD, 6, 1, 60,
                "With impostors: each entity's image is redone every N frames (the animation), and sooner if the camera "
                        + "turns around it.");
        lodImpostorFadeFrames = cfg.getInt("impostorFadeFrames", LOD, 8, 0, 60,
                "With impostors: frames of the dithered transition between the model and the image (0 = hard switch).");
        lodImpostorMinDistance = cfg.getInt("impostorMinDistance", LOD, 32, 8, 512,
                "With impostors: never an image closer than this to the camera (blocks), whatever the size on screen; "
                        + "twice this while the player has been hurt in the last 5 seconds. Besides this, never within 8 blocks, on a "
                        + "mob attacking the player or on one the player attacked in the last 5 seconds.");
        lodImpostorEdgeAlpha = (float) cfg.getFloat("impostorEdgeAlpha", LOD, 0.5f, 0.05f, 0.95f,
                "With impostors: the image is cut where its opacity is below this. 0.5 = the model's own outline; lower "
                        + "values keep the see-through parts of translucent mobs, with a thicker edge.");
        lodExcludeMods = cfg.getStringList("excludeMods", LOD, new String[0],
                "Whole mods left out of the LOD, by id (e.g. lycanitesmobs, iceandfire).");
        lodExcludeEntities = cfg.getStringList("excludeEntities", LOD, new String[0],
                "Entities left out of the LOD, by id (e.g. minecraft:zombie, lycanitesmobs:grue). With * at the end, it "
                        + "works as a prefix: iceandfire:fire* matches all that start with iceandfire:fire.");
        migration.report();
        migration.save();
        if (DevHooks.ON) {
            // Development build only: -Dnthcompat.<option>=... overrides the file (used by the smoketest).
            fixRenderLibOptiRefine = override("fixRenderLibOptiRefine", fixRenderLibOptiRefine);
            fixOptiRefineFloatBuffer = override("fixOptiRefineFloatBuffer", fixOptiRefineFloatBuffer);
            fixOptiRefineFloatBufferReuseView = override("fixOptiRefineFloatBufferReuseView", fixOptiRefineFloatBufferReuseView);
            fixSmartLeavesVertexData = override("fixSmartLeavesVertexData", fixSmartLeavesVertexData);
            fixOptiRefineAccPlaceholders = override("fixOptiRefineAccPlaceholders", fixOptiRefineAccPlaceholders);
            fixOptiRefineVintageFix = override("fixOptiRefineVintageFix", fixOptiRefineVintageFix);
            fixOptiRefineDynamicTextureData = override("fixOptiRefineDynamicTextureData", fixOptiRefineDynamicTextureData);
            fixSmartAnimations = override("fixSmartAnimations", fixSmartAnimations);
            String marking = System.getProperty("nthcompat.smartAnimationsMarking");
            if (marking != null && !marking.trim().isEmpty()) {
                smartAnimationsMarking = marking.trim();
            }
            String always = System.getProperty("nthcompat.smartAnimationsAlwaysAnimate");
            if (always != null && !always.trim().isEmpty()) {
                smartAnimationsAlwaysAnimate = always.trim().split(";");
            }
            iceAndFireTabulaPoseCache = override("iceandfire.cacheTabulaPoses", iceAndFireTabulaPoseCache);
            iceAndFireDistantDragonAnimation = override("iceandfire.distantDragonAnimation", iceAndFireDistantDragonAnimation);
            iceAndFireDistantDragonDistance = overrideInt("iceandfire.distantDragonAnimationDistance", iceAndFireDistantDragonDistance);
            iceAndFireDistantDragonEveryFrames = overrideInt("iceandfire.distantDragonAnimationEveryFrames", iceAndFireDistantDragonEveryFrames);
            optifineDynamicLightsSnapshot = override("optifinedynamiclights.lockFreeLookup", optifineDynamicLightsSnapshot);
            optifineDynamicLightsBlockStep = override("optifinedynamiclights.updatePerBlock", optifineDynamicLightsBlockStep);
            optifineDynamicLightsReachedOnly = override("optifinedynamiclights.reachedSectionsOnly", optifineDynamicLightsReachedOnly);
            pulsarFixSmoothLightCrash = override("pulsar.fixSmoothLightCrash", pulsarFixSmoothLightCrash);
            lodSubPixelParts = override("lod.subPixelParts", lodSubPixelParts);
            lodAnimationBySize = override("lod.animationBySize", lodAnimationBySize);
            lodAnimationFullPixels = overrideInt("lod.animationFullPixels", lodAnimationFullPixels);
            lodAnimationMaxFrames = overrideInt("lod.animationMaxFrames", lodAnimationMaxFrames);
            lodImpostors = override("lod.impostors", lodImpostors);
            lodImpostorPixels = overrideInt("lod.impostorPixels", lodImpostorPixels);
            lodImpostorUpdateFrames = overrideInt("lod.impostorUpdateFrames", lodImpostorUpdateFrames);
            lodImpostorFadeFrames = overrideInt("lod.impostorFadeFrames", lodImpostorFadeFrames);
            lodImpostorMinDistance = overrideInt("lod.impostorMinDistance", lodImpostorMinDistance);
            String edge = System.getProperty("nthcompat.lod.impostorEdgeAlpha");
            if (edge != null && !edge.trim().isEmpty()) {
                lodImpostorEdgeAlpha = Float.parseFloat(edge.trim());
            }
            String parts = System.getProperty("nthcompat.lod.partPixels");
            if (parts != null && !parts.trim().isEmpty()) {
                lodPartPixels = Float.parseFloat(parts.trim());
            }
            String exMods = System.getProperty("nthcompat.lod.excludeMods");
            if (exMods != null) {
                lodExcludeMods = exMods.trim().isEmpty() ? new String[0] : exMods.trim().split(";");
            }
            String exEntities = System.getProperty("nthcompat.lod.excludeEntities");
            if (exEntities != null) {
                lodExcludeEntities = exEntities.trim().isEmpty() ? new String[0] : exEntities.trim().split(";");
            }
        }
    }

    private static final String OBSOLETE_FORCE_FINGERPRINT = "forceOptiRefineFingerprint";

    /**
     * First start of Gambiarra coming from an old version: with no gambiarra.cfg and an nthcompat.cfg present, copies the old one (load
     * removes the keys that moved to Gadolinium). true = copied.
     */
    private static boolean migrateFromNthcompat(File file) {
        File old = new File(file.getParentFile(), OLD_FILE_NAME);
        if (file.exists() || !old.isFile()) {
            return false;
        }
        try {
            java.nio.file.Files.copy(old.toPath(), file.toPath());
            CompatStatus.LOG.info("[gambiarra] Config migrated: {} copied from {} (from an old Nothirium Shaders); the keys {} stay in "
                    + "gadolinium.cfg, compat section. The old {} was not touched.", FILE_NAME, OLD_FILE_NAME,
                    String.join(", ", MOVED_TO_SHADERS), OLD_FILE_NAME);
            return true;
        } catch (java.io.IOException e) {
            CompatStatus.LOG.warn("[gambiarra] Could not migrate {} to {} ({}); using defaults", OLD_FILE_NAME, FILE_NAME, e.toString());
            return false;
        }
    }

    private static int overrideInt(String key, int value) {
        if (DevHooks.ON) {
            String p = System.getProperty("nthcompat." + key);
            if (p != null && !p.trim().isEmpty()) {
                return Integer.parseInt(p.trim());
            }
        }
        return value;
    }

    private static boolean override(String key, boolean value) {
        if (DevHooks.ON) {
            String p = System.getProperty("nthcompat." + key);
            if (p != null && !p.trim().isEmpty()) {
                return Boolean.parseBoolean(p.trim());
            }
        }
        return value;
    }

}
