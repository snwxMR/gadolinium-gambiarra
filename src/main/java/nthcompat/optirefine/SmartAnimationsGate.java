// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import nthcompat.CompatConfig;

/**
 * Decision of the Smart Animations package (see SmartAnimationsFix), with no game class: used by the mixin plugin, before the targets
 * are loaded.
 */
public final class SmartAnimationsGate {

    public enum Marking {
        SPRITE, COORDINATES
    }

    private static volatile boolean allowed;
    /**
     * Mixins of the package in the chosen form and how many injections each leaves in the final class (simple name -> count). The package
     * only turns on if all of them apply (HandlerCallAudit): half of it on would stop animations or break the getter.
     */
    private static final Map<String, Integer> expectedInjections = new LinkedHashMap<>();
    /** Audit result: simple name -> "counted/expected in target". */
    private static final Map<String, String> audited = new LinkedHashMap<>();
    private static final Map<String, Boolean> auditOk = new LinkedHashMap<>();
    private static String reason = "not evaluated";
    private static Marking marking = Marking.SPRITE;
    private static String[] alwaysPatterns = new String[0];
    /** Version lock: OptiRefine already calls SmartAnimations.update() / already marks renderModelBrightnessColorQuads. */
    private static volatile boolean optiRefineCallsUpdate;
    private static volatile boolean optiRefineMarksBrightnessQuads;

    private SmartAnimationsGate() {
    }

    /** CompatStatus.evaluate (before the mixins): decides whether the package applies. */
    public static void decide(boolean nothirium, boolean naughthirium, boolean optiRefine) {
        marking = "coordinates".equalsIgnoreCase(CompatConfig.smartAnimationsMarking.trim()) ? Marking.COORDINATES : Marking.SPRITE;
        List<String> patterns = new ArrayList<>();
        for (String p : CompatConfig.smartAnimationsAlwaysAnimate) {
            if (p != null && !p.trim().isEmpty()) {
                patterns.add(p.trim());
            }
        }
        alwaysPatterns = patterns.toArray(new String[0]);
        if (!CompatConfig.fixSmartAnimations) {
            reason = "disabled in the config";
        } else if (!optiRefine) {
            reason = "no OptiRefine (classic OptiFine already does this)";
        } else if (nothirium && !naughthirium) {
            reason = "Nothirium without Naughthirium: no one would mark the textures of terrain sections and water, lava and "
                    + "animated blocks would stop";
        } else {
            allowed = true;
            expectedInjections.put("RenderGlobalSmartAnimationsMixin", 1);
            expectedInjections.put("TextureAtlasSpriteSmartAnimationsMixin", 1);
            if (marking == Marking.SPRITE) {
                expectedInjections.put("RenderItemSpriteMarkingMixin", 1);
                expectedInjections.put("BlockModelRendererSpriteMarkingMixin", 1);
                // getMinU, getMaxU, getMinV, getMaxV, getInterpolatedU, getInterpolatedV
                expectedInjections.put("TextureAtlasSpriteGetterMarkingMixin", 6);
            } else {
                expectedInjections.put("WorldVertexBufferUploaderCoordinateMarkingMixin", 1);
            }
            reason = "enabled (marking: " + (marking == Marking.SPRITE ? "sprite" : "coordinates") + ", alwaysAnimate: "
                    + alwaysPatterns.length + ")";
        }
    }

    public static boolean allowed() {
        return allowed;
    }

    /** The plugin applies this mixin (simple name): the package applied and the mixin belongs to the chosen form. */
    public static boolean applies(String mixinSimpleName) {
        return allowed && expectedInjections.containsKey(mixinSimpleName);
    }

    /** Injections the mixin must leave in the final class; 0 if it is not part of the package. */
    public static int expectedInjections(String mixinSimpleName) {
        Integer n = expectedInjections.get(mixinSimpleName);
        return n != null ? n : 0;
    }

    /** Without the injection audit, the package does not apply (the plugin calls this before any mixin is applied). */
    public static void auditUnavailable(String why) {
        allowed = false;
        reason = "disabled: the injection check is not available (" + why + ")";
    }

    /** HandlerCallAudit, at the end of the transformation of each target class. */
    public static synchronized void recordInjection(String targetClassName, String mixinSimpleName, int found, int expected) {
        audited.put(mixinSimpleName, found + "/" + expected + " in " + targetClassName);
        auditOk.put(mixinSimpleName, found == expected);
    }

    /** null if all injections of the chosen form applied; otherwise, what is missing (for the log and the diagnostics). */
    public static synchronized String missingInjections() {
        StringBuilder sb = new StringBuilder();
        for (String m : expectedInjections.keySet()) {
            if (!Boolean.TRUE.equals(auditOk.get(m))) {
                sb.append(sb.length() > 0 ? "; " : "").append(m).append(": ")
                        .append(audited.containsKey(m) ? audited.get(m) : "not applied");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Diagnostics: what the audit counted in each mixin. */
    public static synchronized Map<String, String> auditResults() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String name : expectedInjections.keySet()) {
            m.put(name, audited.containsKey(name) ? audited.get(name) : "not applied");
        }
        return m;
    }

    public static Marking marking() {
        return marking;
    }

    public static String reason() {
        return reason;
    }

    static String[] alwaysPatterns() {
        return alwaysPatterns;
    }

    public static void setOptiRefineCallsUpdate() {
        optiRefineCallsUpdate = true;
    }

    public static boolean optiRefineCallsUpdate() {
        return optiRefineCallsUpdate;
    }

    public static void setOptiRefineMarksBrightnessQuads() {
        optiRefineMarksBrightnessQuads = true;
    }

    public static boolean optiRefineMarksBrightnessQuads() {
        return optiRefineMarksBrightnessQuads;
    }

}
