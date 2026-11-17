// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

/**
 * State kept in each TextureAtlasSprite by TextureAtlasSpriteSmartAnimationsMixin (see SmartAnimationsFix).
 */
public interface AnimatedSpriteState {

    /** Not read from OptiRefine yet. */
    int UNKNOWN = -2;

    /** Animation index in Smart Animations (-1 = not animated; UNKNOWN = not read yet). */
    int nthcompat$animIndex();

    void nthcompat$setAnimIndex(int index);

    /** It is in the config's alwaysAnimate list. */
    boolean nthcompat$alwaysAnimate();

    void nthcompat$setAlwaysAnimate(boolean always);

}
