// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;

/**
 * OpenGL state that GPU culling changes around a compute dispatch, read from OpenGL itself before and restored after,
 * exactly as it was. No private copy of the state: the game's and OptiFine's GlStateManager stay correct (a parallel cache
 * goes out of sync).
 *
 * The reads (glGetInteger) are client state, without waiting for the GPU.
 */
final class GlStateSave {

    private int program;
    private int activeTexture;
    private final int[] textures = new int[2];
    private final int[] samplers = new int[2];
    private boolean textures2Saved;

    /** Current program (OptiFine's with shaders, Nothirium's without). */
    void saveProgram() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    }

    void restoreProgram() {
        GL20.glUseProgram(program);
    }

    /** Active unit and what is bound (2D texture and sampler) on the two units the compute shaders use. */
    void saveTextures() {
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] units = { GpuPrograms.UNIT_A, GpuPrograms.UNIT_B };
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + units[i]);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(activeTexture);
        textures2Saved = true;
    }

    void restoreTextures() {
        if (!textures2Saved) {
            return;
        }
        int[] units = { GpuPrograms.UNIT_A, GpuPrograms.UNIT_B };
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + units[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL33.glBindSampler(units[i], samplers[i]);
        }
        GL13.glActiveTexture(activeTexture);
        textures2Saved = false;
    }

    /** Binds a 2D texture (and its own sampler) on one of the units, without touching the active unit. */
    void bind(int unit, int texture, int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, sampler);
        GL13.glActiveTexture(activeTexture);
    }

}
