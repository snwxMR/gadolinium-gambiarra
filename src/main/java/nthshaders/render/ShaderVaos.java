// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import java.util.List;

import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.mc.renderer.chunk.RenderChunk;
import meldexun.nothirium.mc.util.BlockRenderLayerUtil;
import meldexun.nothirium.util.ListUtil;
import meldexun.renderlib.util.RenderUtil;
import net.minecraft.util.BlockRenderLayer;
import net.optifine.shaders.Shaders;
import net.optifine.shaders.ShadersRender;

/**
 * VAO and draw for Nothirium's GL43 in shader mode.
 *
 * A VAO (Vertex Array Object) is the OpenGL object that holds "where and how to read each piece of vertex data". Nothirium's reads
 * the 28-byte format with its own shader; this one reads OptiFine's format (56 bytes) the way the shader pack expects, the same as
 * the GL15 path:
 *  - conventional arrays (position, colour, texture, lightmap, normal), as in ShadersRender.setupArrayPointersVbo;
 *  - OptiFine's generic attributes: mc_Entity, mc_midTexCoord, at_tangent (Shaders.entityAttrib/midTexCoordAttrib/tangentAttrib =
 *    10/11/12);
 *  - each chunk's offset at location 6, one per draw (divisor 1), read from Nothirium's offset buffer; the rewritten shader
 *    (ShaderRewriter) adds it to the vertex position.
 */
public final class ShaderVaos {

    /**
     * Location of the per-chunk offset. On NVIDIA generic attributes share numbers with conventional ones (0 = position, 2 = normal,
     * 3 = colour, 8 to 15 = textures), and 6 is free. On AMD and Intel the two spaces are separate: 6 works the same. OptiFine uses 10
     * to 12.
     */
    public static final int OFFSET_LOCATION = 6;
    /** OptiFine's vertex size with shaders (SVertexFormat.makeDefVertexFormatBlock). */
    public static final int STRIDE = 56;

    private ShaderVaos() {
    }

    public static int create(int vbo, int offsetBuffer) {
        return create(vbo, offsetBuffer, 0L);
    }

    /** Same, with each chunk's offset read from offsetBytes in the buffer (the GPU culling regions). */
    public static int create(int vbo, int offsetBuffer, long offsetBytes) {
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 16L);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, STRIDE, 24L);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glNormalPointer(GL11.GL_BYTE, STRIDE, 28L);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        // The normal array and the generics 10/11/12 are turned on/off by ShadersRender.pre/postRenderChunkLayer on each draw, as in GL15.
        GL20.glVertexAttribPointer(Shaders.midTexCoordAttrib, 2, GL11.GL_FLOAT, false, STRIDE, 32L);
        GL20.glVertexAttribPointer(Shaders.tangentAttrib, 4, GL11.GL_SHORT, false, STRIDE, 40L);
        GL20.glVertexAttribPointer(Shaders.entityAttrib, 3, GL11.GL_SHORT, false, STRIDE, 48L);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, offsetBuffer);
        GL20.glVertexAttribPointer(OFFSET_LOCATION, 3, GL11.GL_FLOAT, false, 0, offsetBytes);
        GL20.glEnableVertexAttribArray(OFFSET_LOCATION);
        GL33.glVertexAttribDivisor(OFFSET_LOCATION, 1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        return vao;
    }

    /**
     * Draws a layer with the program OptiFine left active (beginTerrainSolid etc. and beginWater), with no program of its own. Same
     * order as GL15: preRenderChunkLayer, draw, postRenderChunkLayer. Instead of a glDrawArrays per chunk, an indirect multi-draw with
     * the commands Nothirium already builds (each chunk is a command; baseInstance = offset index).
     */
    public static void draw(int vao, int commandBuffer, int chunkCount, ChunkRenderPass pass) {
        BlockRenderLayer layer = BlockRenderLayerUtil.getBlockRenderLayer(pass);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        ShadersRender.preRenderChunkLayer(layer);
        GL43.glMultiDrawArraysIndirect(GL11.GL_QUADS, 0L, chunkCount, 0);
        ShadersRender.postRenderChunkLayer(layer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
        // Zeroes the offset right after the terrain: an attribute's current value is context state (not VAO state), and what comes next
        // (entities, sky...) with the same program must not inherit the chunk offset.
        GL20.glVertexAttrib3f(OFFSET_LOCATION, 0.0F, 0.0F, 0.0F);
    }

    /**
     * Fallback for a program without the offset (rewrite failed, or no program): chunk by chunk with glTranslated, exactly like
     * Nothirium's GL15, with the same data and the same VAO. The offset attribute stays off and at zero during the draw, so it is never
     * added twice.
     */
    public static void drawPerChunk(int vao, List<RenderChunk> chunks, ChunkRenderPass pass) {
        BlockRenderLayer layer = BlockRenderLayerUtil.getBlockRenderLayer(pass);
        double cameraX = RenderUtil.getCameraEntityX();
        double cameraY = RenderUtil.getCameraEntityY();
        double cameraZ = RenderUtil.getCameraEntityZ();
        GL30.glBindVertexArray(vao);
        GL20.glDisableVertexAttribArray(OFFSET_LOCATION);
        GL20.glVertexAttrib3f(OFFSET_LOCATION, 0.0F, 0.0F, 0.0F);
        ShadersRender.preRenderChunkLayer(layer);
        ListUtil.forEach(chunks, pass == ChunkRenderPass.TRANSLUCENT, (renderChunk, i) -> {
            IVBOPart part = renderChunk.getVBOPart(pass);
            GL11.glPushMatrix();
            GL11.glTranslated(renderChunk.getX() - cameraX, renderChunk.getY() - cameraY, renderChunk.getZ() - cameraZ);
            GL11.glDrawArrays(GL11.GL_QUADS, part.getFirst(), part.getCount());
            GL11.glPopMatrix();
        });
        ShadersRender.postRenderChunkLayer(layer);
        GL20.glEnableVertexAttribArray(OFFSET_LOCATION);
        GL30.glBindVertexArray(0);
    }

    public static void delete(int vao) {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
    }

}
