// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import gambiarra.common.JarUtil;
import nthcompat.CompatStatus;

/**
 * Gambiarra 1.0.0-rc11 (option fixOptiRefineDynamicTextureData), for OptiRefine 78ff8ce. After a shader pack is turned on and then off
 * in the same session, the terrain is black (with holes) and the item in the hand is invisible until the game restarts. Gadolinium
 * 1.0.0-rc10's probe showed why: the lightmap on the GPU is all zeros while the game's copy is right, with no OpenGL error.
 *
 * The cause is in OptiRefine's MixinDynamicTexture. A DynamicTexture made with shaders off has the vanilla array (width x height). The
 * first time shaders upload it, OptiRefine replaces dynamicTextureData with a new array 3 times the size (OptiFine puts the shader
 * texture's normal and specular parts after the colours). Whoever kept the old array keeps writing into it: EntityRenderer.lightmapColors
 * (vanilla takes it once, in the constructor) and the maps (MapItemRenderer). From then on the texture uploads the new array, whose
 * colours stay zero: the lightmap is black, alpha included, with shaders and after turning them off. Started with shaders on, the
 * lightmap is made with the larger array already, so it does not happen.
 *
 * OptiRefine v0.1.0-alpha (commit 2cab6ef) fixed it by giving every DynamicTexture the larger array in the constructor, as OptiFine
 * always did. mixin.DynamicTextureDataMixin does the same with 78ff8ce: at the end of DynamicTexture(int, int), an array shorter than
 * width x height x 3 becomes one of that size with the same content. The texture still uploads only the first width x height values. The
 * cost is OptiFine's: 2 x width x height ints more per dynamic texture made while shaders are off.
 *
 * Code lock: only with OptiRefine's MixinDynamicTexture byte for byte 78ff8ce's (CLASS). With v0.1.0-alpha it is not needed; with any
 * other build it stays off.
 */
public final class DynamicTextureDataFix {

    public static final String OPTION = "fixOptiRefineDynamicTextureData";

    /** OptiRefine's mixin class with the defect: {resource, SHA-256 of 78ff8ce's}. */
    static final String[] CLASS = { "mods/Hileb/optirefine/mixin/defaults/minecraft/client/renderer/texture/MixinDynamicTexture.class",
            "1d9453c0064e82eed06c85b4b10ffc0e50fe6608f70a2f91c8d43db242ba0c03" };

    private static String state = "not evaluated";
    private static volatile int expanded;

    private DynamicTextureDataFix() {
    }

    /** null if the installed OptiRefine has 78ff8ce's MixinDynamicTexture; otherwise, why the fix stays off. */
    public static String lockProblem() {
        byte[] bytes = ResourceProbePriorityFix.classBytes(CLASS[0]);
        if (bytes == null) {
            return "OptiRefine's MixinDynamicTexture not found";
        }
        return CLASS[1].equals(JarUtil.sha256Hex(bytes)) ? null : "OptiRefine's MixinDynamicTexture is not 78ff8ce's";
    }

    public static void setState(String s) {
        state = s;
    }

    /** DynamicTextureDataMixin: an array became the larger one. */
    public static void expanded(int width, int height, int size) {
        expanded++;
        CompatStatus.markRan(OPTION, "DynamicTexture " + width + " x " + height + " made with an array of " + size
                + " (OptiRefine 78ff8ce would swap it when shaders turn on)");
    }

    public static String describe() {
        return "active".equals(state) ? "active, " + expanded + " dynamic texture(s) made with the larger array" : state;
    }

}
