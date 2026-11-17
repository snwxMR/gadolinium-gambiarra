// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

/**
 * Implemented on ChunkRendererGL43 by the mixin: marks the renderer RendererSelection created in shader mode (asked for every
 * chunk, so it stays on the object itself instead of in a map).
 */
public interface ShaderModeRenderer {

    boolean nthshaders$isShaderMode();

    void nthshaders$setShaderMode(boolean shaderMode);

    /** How many buffer copies the renderer alternates (for the log). */
    int nthshaders$bufferCopies();

}
