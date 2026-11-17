// SPDX-License-Identifier: MIT
package nthcompat.optirefine;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexFormat;
import nthcompat.CompatStatus;

/**
 * "coordinates" form of marking animated textures in use (SmartAnimationsFix; config smartAnimationsMarking = coordinates): before each
 * immediate draw (WorldVertexBufferUploader.draw) with the block atlas bound on texture unit 0, reads the centre of each quad (or
 * triangle) and looks up in an atlas grid which animated texture is there. Also catches whoever draws a piece of the atlas without
 * asking for the sprite's coordinates (stored coordinates). Measured in Phase 2: ~0.002 ms per frame with the HUD and 9 animated items.
 * Render thread only.
 */
public final class AtlasCoordinateMarking {

    private static final int GRID = 1024;

    private static short[] grid;
    private static int gridAtlasId = -1;
    private static int gridSprites = -1;
    /** The first and last animated sprite when the grid was made: an F3+T creates new sprites, even with the same count. */
    private static Object gridFirst, gridLast;
    /** And their indices (OptiRefine renumbers the animations when building the atlas again). */
    private static int gridFirstIndex, gridLastIndex;
    private static Object[] textureState;
    private static Field textureName;
    private static Field animatedList;
    private static boolean broken;

    private AtlasCoordinateMarking() {
    }

    public static void beforeDraw(BufferBuilder buffer) {
        if (broken || buffer.getVertexCount() == 0 || !SmartAnimationsFix.markingNowForCoordinates()) {
            return;
        }
        try {
            if (textureState == null) {
                Field ts = GlStateManager.class.getDeclaredField("field_179174_p");
                ts.setAccessible(true);
                textureState = (Object[]) ts.get(null);
                textureName = textureState[0].getClass().getDeclaredField("field_179059_b");
                textureName.setAccessible(true);
                animatedList = TextureMap.class.getDeclaredField("field_94258_i");
                animatedList.setAccessible(true);
            }
            TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
            if (atlas == null || textureName.getInt(textureState[0]) != atlas.getGlTextureId()) {
                return;
            }
            VertexFormat format = buffer.getVertexFormat();
            if (!format.hasUvOffset(0)) {
                return;
            }
            short[] g = grid(atlas);
            int uv = format.getUvOffsetById(0), stride = format.getSize(), count = buffer.getVertexCount();
            int mode = buffer.getDrawMode();
            int per = mode == 7 ? 4 : mode == 4 ? 3 : 1;
            ByteBuffer bb = buffer.getByteBuffer();
            for (int v = 0; v + per <= count; v += per) {
                float u = 0, w = 0;
                for (int k = 0; k < per; k++) {
                    int base = (v + k) * stride + uv;
                    u += bb.getFloat(base);
                    w += bb.getFloat(base + 4);
                }
                int x = (int) (u / per * GRID), y = (int) (w / per * GRID);
                if (x >= 0 && y >= 0 && x < GRID && y < GRID) {
                    int idx = g[y * GRID + x] - 1;
                    if (idx >= 0) {
                        SmartAnimationsFix.markFromCoordinates(idx);
                    }
                }
            }
        } catch (Throwable t) {
            broken = true;
            CompatStatus.LOG.warn("[gambiarra] Smart Animations: marking by coordinates failed and was turned off ({})", t.toString());
            gambiarra.common.StartupReport.failed("smartAnimationsMarking", t.toString());
        }
    }

    /** 1024 x 1024 grid over the atlas UV: animation index + 1 where there is an animated texture; rebuilt when the atlas changes. */
    @SuppressWarnings("unchecked")
    private static short[] grid(TextureMap atlas) throws IllegalAccessException {
        List<TextureAtlasSprite> animated = (List<TextureAtlasSprite>) animatedList.get(atlas);
        Object first = animated.isEmpty() ? null : animated.get(0), last = animated.isEmpty() ? null : animated.get(animated.size() - 1);
        int firstIndex = first != null ? SmartAnimationsFix.index((TextureAtlasSprite) first) : -1;
        int lastIndex = last != null ? SmartAnimationsFix.index((TextureAtlasSprite) last) : -1;
        if (grid != null && gridAtlasId == atlas.getGlTextureId() && gridSprites == animated.size() && gridFirst == first && gridLast == last
                && gridFirstIndex == firstIndex && gridLastIndex == lastIndex) {
            return grid;
        }
        short[] g = new short[GRID * GRID];
        for (TextureAtlasSprite s : animated) {
            int idx = SmartAnimationsFix.index(s);
            if (idx < 0) {
                continue;
            }
            int x0 = (int) Math.ceil(s.getMinU() * GRID), x1 = Math.max(x0, (int) Math.floor(s.getMaxU() * GRID) - 1);
            int y0 = (int) Math.ceil(s.getMinV() * GRID), y1 = Math.max(y0, (int) Math.floor(s.getMaxV() * GRID) - 1);
            for (int y = Math.max(0, y0); y <= Math.min(GRID - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(GRID - 1, x1); x++) {
                    g[y * GRID + x] = (short) (idx + 1);
                }
            }
        }
        grid = g;
        gridAtlasId = atlas.getGlTextureId();
        gridSprites = animated.size();
        gridFirst = first;
        gridLast = last;
        gridFirstIndex = firstIndex;
        gridLastIndex = lastIndex;
        return g;
    }

}
