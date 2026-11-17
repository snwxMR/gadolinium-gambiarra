// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;

import meldexun.matrixutil.Matrix4f;
import meldexun.nothirium.api.renderer.IVBOPart;
import meldexun.nothirium.api.renderer.chunk.ChunkRenderPass;
import meldexun.nothirium.api.renderer.chunk.IChunkRenderer;
import meldexun.nothirium.mc.renderer.ChunkRenderManager;
import meldexun.nothirium.mc.renderer.chunk.RenderChunk;
import meldexun.nothirium.mc.util.BlockRenderLayerUtil;
import meldexun.nothirium.mc.util.FogUtil;
import meldexun.nothirium.opengl.DynamicVBO;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import meldexun.nothirium.util.SectionPos;
import meldexun.nothirium.util.collection.Enum2ObjMap;
import meldexun.nothirium.util.math.MathUtil;
import meldexun.renderlib.util.Frustum;
import meldexun.renderlib.util.GLShader;
import meldexun.renderlib.util.GLUtil;
import meldexun.renderlib.util.RenderUtil;
import meldexun.renderlib.util.Vec4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.optifine.SmartAnimations;
import net.optifine.shaders.Shaders;
import net.optifine.shaders.ShadersRender;
import nthshaders.common.DevHooks;
import nthshaders.core.NthConfig;
import nthshaders.core.Status;
import nthshaders.render.BufferSync;
import nthshaders.render.ShaderPrograms;
import nthshaders.render.ShaderVaos;
import nthshaders.render.ShadowArea;

/**
 * GPU culling for a Nothirium GL43 renderer (see GpuCulling). One per renderer, created in its init.
 *
 * Each pass (main and, with shaders, shadow) has its candidate list (Slot): the sections Nothirium's traversal visited, in
 * its order, stored on the GPU (144 bytes each: position, id, first vertex and count of each layer and, for the opaque layers,
 * the face direction groups; see FaceCulling). The traversal only runs again when something changes; in between, a build and
 * new VBO parts of a section already in the list only update its position in the buffer.
 *
 * Output of each pass, in two sets (alternated per frame, so the GPU never reads what is being written): commands (5 regions:
 * SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT and Hi-Z phase 2's SOLID; with per-direction culling, up to 4 per section in the
 * opaque ones), offsets (one per candidate, read through baseInstance) and counts. The VAOs of each region read the layer's
 * VBO and the offsets.
 */
public final class GpuTerrain {

    static final int REGIONS = 5;
    static final int SECTION_BYTES = 144;
    static final int COUNTS_BYTES = 48;
    static final int GROUP_BYTES = 48;
    /** Commands per section in an opaque region with per-direction culling (4 group runs). */
    static final int FACE_COMMANDS = 4;
    /** After a section opens a new path, the traversal runs again, at most every 2 frames and 8 ms. */
    private static final long REBUILD_DELAY_NS = 8_000_000L;
    private static final int REBUILD_DELAY_FRAMES = 2;
    /** And in any case every 0.5 s (safety against an event that slips through). */
    private static final long REBUILD_MAX_AGE_NS = 500_000_000L;
    /** Fog slack in the traversal: the camera can move up to ~28 blocks inside the section without rebuilding the list. */
    private static final double FOG_PADDING = 32.0;
    private static final double FOG_MOVEMENT = 28.0;
    private static final int READBACK_FRAMES = 20;
    private static final int PARAMETER_BUFFER = 0x80EE;

    /** What triggered a traversal (needsRebuild). */
    static final String[] BUILD_REASONS = { "first", "cameraChangedSection", "fogGrew", "halfSecond", "requested" };
    private static final int BUILD_NONE = -1;
    private static final int BUILD_FIRST = 0;
    private static final int BUILD_CAMERA = 1;
    private static final int BUILD_FOG = 2;
    private static final int BUILD_AGE = 3;
    private static final int BUILD_REQUEST = 4;

    /** Who asked for a new traversal (requestRebuild and onPartChanged). */
    static final String[] REQUEST_REASONS = { "newPartOutsideList", "dirtySectionOutsideList", "visibilityOpened", "otherThread" };
    static final int REQUEST_PART = 0;
    static final int REQUEST_DIRTY = 1;
    static final int REQUEST_VISIBILITY = 2;
    static final int REQUEST_OFF_THREAD = 3;

    /** A pass's frustum, on the CPU: decides the builds (as Nothirium's traversal did). */
    static final class View {
        Frustum frustum;
        double camX;
        double camY;
        double camZ;
        double fogSqr;
        /** Shadow map volume planes (shadow pass only), relative to the camera entity. */
        float[][] light;
        double entityX;
        double entityY;
        double entityZ;

        boolean contains(AbstractRenderChunk c) {
            if (c.isFrustumCulled(frustum) || c.isFogCulled(camX, camY, camZ, fogSqr)) {
                return false;
            }
            if (light != null) {
                float mnx = (float) (c.getX() - entityX);
                float mny = (float) (c.getY() - entityY);
                float mnz = (float) (c.getZ() - entityZ);
                for (float[] p : light) {
                    float vx = p[0] >= 0 ? mnx + 16 : mnx;
                    float vy = p[1] >= 0 ? mny + 16 : mny;
                    float vz = p[2] >= 0 ? mnz + 16 : mnz;
                    if (p[0] * vx + p[1] * vy + p[2] * vz + p[3] < 0) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** A pass's candidate list and its buffers. */
    final class Slot {
        final int index;
        int generation = -1;
        final ArrayList<AbstractRenderChunk> list = new ArrayList<>();
        final ByteBuffer staging;
        final int sections;
        final int[] commands = new int[2];
        final int[] offsets = new int[2];
        final int[] counts = new int[2];
        final int[][] vaos = new int[2][REGIONS];
        final int readback;
        final View view = new View();
        final CullParams params = new CullParams();
        int planeCount;
        int parity;
        int dirtyMin = Integer.MAX_VALUE;
        int dirtyMax = -1;
        int keyX;
        int keyY;
        int keyZ;
        boolean keySpectator;
        double pvsFogEnd;
        long builtAt;
        boolean rebuildRequested;
        boolean activeThisFrame;
        long fence;
        /** measureBufferSync: fence of the last draw with each buffer set. */
        final long[] drawFence = new long[2];
        int readbackIn = READBACK_FRAMES;
        final int[] counted = new int[COUNTS_BYTES / 4];
        /** Per-direction culling mode in the last dispatch and the layers it applied to (bits 0..2). */
        int faceMode;
        int faceLayers;
        long builds;
        int framesSinceBuild;
        long frames;
        /** Traversal diagnostics: time (total and longest) and what triggered each one (BUILD_*). */
        long buildNanos;
        long buildMaxNanos;
        final long[] buildsBy = new long[BUILD_REASONS.length];
        long firstFrameNanos;

        Slot(int index) {
            this.index = index;
            staging = ByteBuffer.allocateDirect(maxSections * SECTION_BYTES).order(ByteOrder.nativeOrder());
            sections = buffer((long) maxSections * SECTION_BYTES, null, GL15.GL_DYNAMIC_DRAW);
            for (int p = 0; p < 2; p++) {
                // Written and read only by the GPU: COPY. With DRAW, the driver may prefer memory the CPU can see, slower for the GPU.
                commands[p] = buffer((long) maxCommands * REGIONS * 16, null, GL15.GL_DYNAMIC_COPY);
                offsets[p] = buffer((long) maxSections * 12, null, GL15.GL_DYNAMIC_COPY);
                counts[p] = buffer(COUNTS_BYTES, zeros(COUNTS_BYTES), GL15.GL_DYNAMIC_COPY);
            }
            readback = buffer(COUNTS_BYTES, zeros(COUNTS_BYTES), GL15.GL_STREAM_READ);
        }

        void dispose() {
            for (int p = 0; p < 2; p++) {
                for (int r = 0; r < REGIONS; r++) {
                    deleteVao(vaos[p][r]);
                    vaos[p][r] = 0;
                }
                GL15.glDeleteBuffers(commands[p]);
                GL15.glDeleteBuffers(offsets[p]);
                GL15.glDeleteBuffers(counts[p]);
                BufferSync.deleteFence(drawFence[p]);
                drawFence[p] = 0L;
            }
            GL15.glDeleteBuffers(sections);
            GL15.glDeleteBuffers(readback);
            if (fence != 0) {
                GL32.glDeleteSync(fence);
                fence = 0;
            }
        }
    }

    private final boolean shaderMode;
    private final IChunkRenderer<?> renderer;
    private final GLShader nothiriumShader;
    private final Enum2ObjMap<ChunkRenderPass, DynamicVBO> vbos;
    private final Enum2ObjMap<ChunkRenderPass, ? extends List<RenderChunk>> lists;
    final int maxSections;
    /** Commands per region: maxSections, or 4 times that with per-direction culling. */
    final int maxCommands;
    private final boolean faceCommands;
    private final int idCapacity;
    private int nextId;
    private final Slot[] slots = new Slot[2];
    private final int history;
    private final int groups;
    private final int flags;
    private final int params;
    private final HiZ hiz;
    private final ArrayList<AbstractRenderChunk> deferred = new ArrayList<>();
    private final GlStateSave state = new GlStateSave();
    private final IntBuffer readbackInts = ByteBuffer.allocateDirect(COUNTS_BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final int[] faceWords = new int[FaceLayout.WORDS];
    private boolean hizFrame;
    private boolean hizPhase2Done;
    private int markedTick = Integer.MIN_VALUE;
    private long hizFallbacks;
    private String hizLastProblem;
    /** Diagnostics: traversal requests by reason (REQUEST_*), and the cost of the deferred builds per frame. */
    private final long[] requests = new long[REQUEST_REASONS.length];
    /** gpuCullingSkipNoOpDirtyRebuilds: dirty marks outside the lists that did not ask for a traversal (not visited / empty with no blocks). */
    private long dirtySkippedUnvisited;
    private long dirtySkippedEmpty;
    /**
     * Dev (-Dnthshaders.staleProbe=true): sections of the main pass list, in the frustum, dirty or building, with the neighbouring
     * columns loaded (Nothirium could build them) for a long time. One that stays like this with the scene still is an
     * out-of-date section that does not correct itself.
     */
    private static final boolean STALE_PROBE = DevHooks.ON && Boolean.getBoolean("nthshaders.staleProbe");
    private final java.util.IdentityHashMap<AbstractRenderChunk, Long> dirtySeen = new java.util.IdentityHashMap<>();
    private final java.util.Set<AbstractRenderChunk> staleCounted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long staleMaxNanos;
    private int staleOver2s;
    private long deferredNanos;
    private long deferredFrames;
    private int deferredMax;

    @SuppressWarnings("unchecked")
    public GpuTerrain(int renderDistance, boolean shaderMode, IChunkRenderer<?> renderer, GLShader nothiriumShader,
            Enum2ObjMap<ChunkRenderPass, DynamicVBO> vbos, Enum2ObjMap<ChunkRenderPass, ? extends List<?>> lists) {
        this.shaderMode = shaderMode;
        this.renderer = renderer;
        this.nothiriumShader = nothiriumShader;
        this.vbos = vbos;
        this.lists = (Enum2ObjMap<ChunkRenderPass, ? extends List<RenderChunk>>) (Object) lists;
        int d = renderDistance * 2 + 1;
        maxSections = d * d * 16;
        // The audit of the build injections only arrives on the first build: the space comes from the config.
        faceCommands = NthConfig.gpuFaceCulling;
        maxCommands = maxSections * (faceCommands ? FACE_COMMANDS : 1);
        idCapacity = d * d * d;
        if ((long) maxCommands * REGIONS * 16 > GpuCulling.maxStorageBlock()) {
            throw new IllegalStateException("the command buffer for render distance " + renderDistance + " does not fit in a storage block on this GPU");
        }
        slots[0] = new Slot(0);
        slots[1] = shaderMode ? new Slot(1) : null;
        history = buffer((long) idCapacity * 4, zeros(idCapacity * 4), GL15.GL_DYNAMIC_COPY);
        groups = buffer((long) ((maxSections + 255) / 256 + 1) * GROUP_BYTES, null, GL15.GL_DYNAMIC_COPY);
        flags = buffer((long) maxSections * 4, null, GL15.GL_DYNAMIC_COPY);
        params = buffer(CullParams.SIZE, null, GL15.GL_DYNAMIC_DRAW);
        hiz = new HiZ();
        for (ChunkRenderPass pass : ChunkRenderPass.ALL) {
            rebuildVaos(pass);
        }
        GpuCulling.onRendererCreated(this);
        FaceCulling.onTerrain(true);
        if (faceCommands) {
            int passes = shaderMode ? 2 : 1;
            double mb = 1024.0 * 1024.0;
            Status.LOG.info("[gadolinium] Per-direction face culling: up to {} commands per section on opaque layers; GPU buffers: "
                    + "commands {} MB (without it {} MB), sections {} MB (without it {} MB), offsets {} MB (without it {} MB)", FACE_COMMANDS,
                    String.format("%.1f", passes * 2.0 * REGIONS * maxCommands * 16 / mb), String.format("%.1f", passes * 2.0 * REGIONS * maxSections * 16 / mb),
                    String.format("%.1f", passes * (double) maxSections * SECTION_BYTES / mb), String.format("%.1f", passes * (double) maxSections * 48 / mb),
                    String.format("%.1f", passes * 2.0 * maxSections * 12 / mb), String.format("%.1f", passes * 2.0 * REGIONS * maxSections * 12 / mb));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Buffers and VAOs

    private static ByteBuffer zeros(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /** Creates a buffer through the GL_COPY_WRITE_BUFFER target (nothing in the game depends on it) and restores the previous binding. */
    private static int buffer(long size, ByteBuffer data, int usage) {
        int previous = GL11.glGetInteger(GL42.GL_COPY_WRITE_BUFFER_BINDING);
        int b = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, b);
        if (data != null) {
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, data, usage);
        } else {
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, size, usage);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous);
        return b;
    }

    private static void deleteVao(int vao) {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
    }

    private static ChunkRenderPass passOf(int region) {
        return region == 4 ? ChunkRenderPass.SOLID : ChunkRenderPass.ALL[region];
    }

    /** A layer's VBO changed (it grew: new buffer): rebuilds the VAOs that read from it. */
    public void rebuildVaos(ChunkRenderPass pass) {
        int vbo = vbos.get(pass).getVbo();
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }
            for (int p = 0; p < 2; p++) {
                for (int r = 0; r < REGIONS; r++) {
                    if (passOf(r) != pass) {
                        continue;
                    }
                    deleteVao(slot.vaos[p][r]);
                    // One offset per candidate, the same for all regions (baseInstance = position of the candidate).
                    slot.vaos[p][r] = shaderMode ? ShaderVaos.create(vbo, slot.offsets[p], 0L) : nothiriumVao(vbo, slot.offsets[p], 0L);
                }
            }
        }
    }

    /** The same VAO ChunkRendererGL43.initVAOs builds (28-byte format), with the offset coming from the region. */
    private int nothiriumVao(int vbo, int offsetBuffer, long offset) {
        int aPos = nothiriumShader.getAttribute("a_Pos");
        int aColor = nothiriumShader.getAttribute("a_Color");
        int aTexCoord = nothiriumShader.getAttribute("a_TexCoord");
        int aLightCoord = nothiriumShader.getAttribute("a_LightCoord");
        int aOffset = nothiriumShader.getAttribute("a_Offset");
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glVertexAttribPointer(aPos, 3, GL11.GL_FLOAT, false, 28, 0L);
        GL20.glVertexAttribPointer(aColor, 4, GL11.GL_UNSIGNED_BYTE, true, 28, 12L);
        GL20.glVertexAttribPointer(aTexCoord, 2, GL11.GL_FLOAT, false, 28, 16L);
        GL20.glVertexAttribPointer(aLightCoord, 2, GL11.GL_SHORT, false, 28, 24L);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, offsetBuffer);
        GL20.glVertexAttribPointer(aOffset, 3, GL11.GL_FLOAT, false, 0, offset);
        GL20.glEnableVertexAttribArray(aPos);
        GL20.glEnableVertexAttribArray(aColor);
        GL20.glEnableVertexAttribArray(aTexCoord);
        GL20.glEnableVertexAttribArray(aLightCoord);
        GL20.glEnableVertexAttribArray(aOffset);
        GL33.glVertexAttribDivisor(aOffset, 1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        return vao;
    }

    public void dispose() {
        GpuCulling.onRendererDisposed(this);
        FaceCulling.onTerrain(false);
        for (Slot slot : slots) {
            if (slot != null) {
                slot.dispose();
            }
        }
        GL15.glDeleteBuffers(history);
        GL15.glDeleteBuffers(groups);
        GL15.glDeleteBuffers(flags);
        GL15.glDeleteBuffers(params);
        hiz.dispose();
        for (AbstractRenderChunk c : deferred) {
            ((GpuSection) c).nthgpu$setDeferred(false);
        }
        deferred.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Candidate list

    private Slot slotForPass() {
        return slots[shaderMode && Shaders.isShadowPass ? 1 : 0];
    }

    /** The pass in progress was done on the GPU (MinecraftChunkRendererGpuMixin: the layer is always drawn). */
    public boolean drawsThisPass() {
        Slot slot = slotForPass();
        return slot != null && slot.activeThisFrame;
    }

    /**
     * gpuCullingEntities: the section was reached by the main pass's last traversal (empty or not). With no traversal yet, or no
     * RenderChunk at the position, answers yes (when in doubt, draw).
     */
    boolean sectionReached(int sx, int sy, int sz) {
        Slot slot = slots[0];
        if (slot == null || slot.generation < 0) {
            return true;
        }
        Object chunk = ChunkRenderManager.getProvider().getRenderChunkAt(sx, sy, sz);
        return !(chunk instanceof GpuSection) || ((GpuSection) chunk).nthgpu$visited(0) == slot.generation;
    }

    private boolean inSlot(Slot slot, GpuSection s) {
        return slot != null && slot.generation >= 0 && s.nthgpu$slotGeneration(slot.index) == slot.generation;
    }

    /** Traversal in capture mode: keeps the visited section (empty ones already built stay out: nothing to draw). */
    void addCandidate(Slot slot, AbstractRenderChunk chunk) {
        int sy = chunk.getSectionY();
        GpuSection s = (GpuSection) chunk;
        // Visited by this traversal, empty or not (gpuCullingEntities in the main pass, gpuCullingSkipNoOpDirtyRebuilds).
        s.nthgpu$setVisited(slot.index, slot.generation);
        if (sy < 0 || sy >= 16 || chunk.isEmpty() && !chunk.isDirty() && !s.nthgpu$compiling()) {
            return;
        }
        int i = slot.list.size();
        if (i >= maxSections) {
            return;
        }
        if (s.nthgpu$slotGeneration(slot.index) == slot.generation) {
            // Already visited in this traversal (should not happen).
            return;
        }
        if (s.nthgpu$id() < 0 || s.nthgpu$id() >= idCapacity) {
            s.nthgpu$setId(nextId < idCapacity ? nextId++ : 0);
        }
        slot.list.add(chunk);
        s.nthgpu$setSlot(slot.index, slot.generation, i);
    }

    private void writeEntry(Slot slot, int i, AbstractRenderChunk chunk) {
        ByteBuffer b = slot.staging;
        int o = i * SECTION_BYTES;
        b.putInt(o, chunk.getX());
        b.putInt(o + 4, chunk.getY());
        b.putInt(o + 8, chunk.getZ());
        b.putInt(o + 12, ((GpuSection) chunk).nthgpu$id());
        for (int p = 0; p < 4; p++) {
            IVBOPart part = chunk.getVBOPart(ChunkRenderPass.ALL[p]);
            b.putInt(o + 16 + p * 4, part != null ? part.getFirst() : 0);
            b.putInt(o + 32 + p * 4, part != null ? part.getCount() : 0);
            if (p < 3) {
                // Face direction groups (no layout: everything in ANY, a single run).
                FaceLayout layout = part != null ? FaceCulling.layoutOf(part) : null;
                int[] words = layout != null ? layout.words : faceWords;
                if (layout == null) {
                    FaceLayout.writeAllAny(faceWords, 0, part != null ? part.getCount() : 0);
                }
                for (int w = 0; w < FaceLayout.WORDS; w++) {
                    b.putInt(o + 48 + p * 32 + w * 4, words[w]);
                }
            }
        }
        slot.dirtyMin = Math.min(slot.dirtyMin, i);
        slot.dirtyMax = Math.max(slot.dirtyMax, i);
    }

    private void upload(Slot slot) {
        if (slot.dirtyMax < slot.dirtyMin) {
            return;
        }
        ByteBuffer range = slot.staging.duplicate().order(ByteOrder.nativeOrder());
        range.position(slot.dirtyMin * SECTION_BYTES);
        range.limit((slot.dirtyMax + 1) * SECTION_BYTES);
        int previous = GL11.glGetInteger(GL42.GL_COPY_WRITE_BUFFER_BINDING);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, slot.sections);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, (long) slot.dirtyMin * SECTION_BYTES, range);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous);
        slot.dirtyMin = Integer.MAX_VALUE;
        slot.dirtyMax = -1;
    }

    void onPartChanged(AbstractRenderChunk chunk, IVBOPart part) {
        GpuSection s = (GpuSection) chunk;
        // The build arrived (or the section was emptied/cancelled).
        s.nthgpu$setCompiling(false);
        for (Slot slot : slots) {
            if (slot == null || slot.generation < 0) {
                continue;
            }
            if (inSlot(slot, s)) {
                writeEntry(slot, s.nthgpu$slotIndex(slot.index), chunk);
            } else if (part != null) {
                // A section outside the list got something to draw: the traversal must go through it.
                slot.rebuildRequested = true;
                requests[REQUEST_PART]++;
            }
        }
    }

    void onMarkDirty(AbstractRenderChunk chunk) {
        GpuSection s = (GpuSection) chunk;
        boolean listed = false;
        for (Slot slot : slots) {
            listed |= inSlot(slot, s);
        }
        if (listed) {
            defer(chunk);
        } else {
            int sy = chunk.getSectionY();
            if (sy >= 0 && sy < 16 && !(NthConfig.gpuCullingSkipNoOpDirtyRebuilds && noOpDirty(chunk, s))) {
                // Has something to draw and is outside the lists: the traversal decides whether it is visible.
                requestRebuild(REQUEST_DIRTY);
            }
        }
    }

    /**
     * gpuCullingSkipNoOpDirtyRebuilds: a section outside the lists marked dirty only asks for a traversal if a new traversal could
     * give another list. It does not ask:
     *  - if none of the last traversals visited it: marking dirty does not change the graph (section visibility only changes
     *    with a build, which asks for the traversal itself, in onVisibilityChanged/onPartChanged), so the new traversal would not
     *    reach it either;
     *  - if it was visited, already built empty and "all visible", with no build in progress, and still has no blocks: the build
     *    would give the same.
     * It stays dirty, and the next traversal that visits it builds it. The safety traversal (half a second) stays the same.
     */
    private boolean noOpDirty(AbstractRenderChunk chunk, GpuSection s) {
        // A pass that has not traversed yet (shadow without shaders, or before its first frame) traverses anyway.
        boolean built = false;
        boolean visited = false;
        for (Slot slot : slots) {
            if (slot == null || slot.generation < 0) {
                continue;
            }
            built = true;
            visited |= s.nthgpu$visited(slot.index) == slot.generation;
        }
        if (!built) {
            return false;
        }
        if (!visited) {
            dirtySkippedUnvisited++;
            return true;
        }
        if (chunk.isEmpty() && !s.nthgpu$compiling() && chunk.getVisibility().allVisible() && hasNoBlocks(chunk)) {
            dirtySkippedEmpty++;
            return true;
        }
        return false;
    }

    /**
     * A build requested by GPU culling (outside the traversal). The mark comes first: a section with nothing to build gets the empty
     * parts right there (setVBOPart clears the mark); with a real build, it stays until the result arrives.
     */
    private void compile(AbstractRenderChunk chunk) {
        ((GpuSection) chunk).nthgpu$setCompiling(true);
        chunk.compileAsync(renderer, ChunkRenderManager.getTaskDispatcher());
    }

    void defer(AbstractRenderChunk chunk) {
        GpuSection s = (GpuSection) chunk;
        if (!s.nthgpu$deferred()) {
            s.nthgpu$setDeferred(true);
            deferred.add(chunk);
        }
    }

    void requestRebuild(int reason) {
        requests[reason]++;
        for (Slot slot : slots) {
            if (slot != null) {
                slot.rebuildRequested = true;
            }
        }
    }

    /** The section has only air in the client world (no ExtendedBlockStorage, or 0 blocks): its build is empty. */
    private static boolean hasNoBlocks(AbstractRenderChunk chunk) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null) {
            return false;
        }
        SectionPos pos = chunk.getPos();
        Chunk column = world.getChunkProvider().getLoadedChunk(pos.getX(), pos.getZ());
        int sy = pos.getY();
        if (column == null || sy < 0 || sy >= 16) {
            return false;
        }
        ExtendedBlockStorage storage = column.getBlockStorageArray()[sy];
        return storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty();
    }

    /** STALE_PROBE: one pass over the main pass list on each setup. */
    private void probeStale(Slot slot, View view) {
        long now = System.nanoTime();
        java.util.IdentityHashMap<AbstractRenderChunk, Long> next = new java.util.IdentityHashMap<>();
        for (int i = 0, n = slot.list.size(); i < n; i++) {
            AbstractRenderChunk c = slot.list.get(i);
            // Dirty (build not requested) or with the build in progress (requested and no result: a stalled queue shows up here).
            if (!(c.isDirty() || ((GpuSection) c).nthgpu$compiling()) || !view.contains(c) || !columnsLoaded(c)) {
                continue;
            }
            Long since = dirtySeen.get(c);
            long first = since != null ? since : now;
            next.put(c, first);
            long age = now - first;
            if (age > staleMaxNanos) {
                staleMaxNanos = age;
            }
            if (age > 2_000_000_000L && staleCounted.add(c)) {
                staleOver2s++;
            }
        }
        staleCounted.retainAll(next.keySet());
        dirtySeen.clear();
        dirtySeen.putAll(next);
    }

    /** The 3 x 3 surrounding columns loaded on the client (the same math as Nothirium 0.4.9's canCompile). */
    private static boolean columnsLoaded(AbstractRenderChunk c) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null) {
            return false;
        }
        SectionPos pos = c.getPos();
        for (int x = pos.getX() - 1; x <= pos.getX() + 1; x++) {
            for (int z = pos.getZ() - 1; z <= pos.getZ() + 1; z++) {
                if (world.getChunkProvider().getLoadedChunk(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private String staleDescribe() {
        long now = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        int over = 0;
        for (Map.Entry<AbstractRenderChunk, Long> e : dirtySeen.entrySet()) {
            long age = now - e.getValue();
            if (age > 2_000_000_000L) {
                over++;
                if (over <= 5) {
                    SectionPos p = e.getKey().getPos();
                    sb.append(String.format(" (%d %d %d, %.1f s ago)", p.getX(), p.getY(), p.getZ(), age / 1e9));
                }
            }
        }
        return "now" + dirtySeen.size() + ", now over 2 s " + over + sb + "; longest wait in the session "
                + String.format("%.1f s", staleMaxNanos / 1e9) + ", went over 2 s in the session " + staleOver2s;
    }

    /** Deferred builds: the dirty section is built when it enters this pass's frustum (as in Nothirium). */
    private void processDeferred(View view) {
        int w = 0;
        int n = deferred.size();
        for (int r = 0; r < n; r++) {
            AbstractRenderChunk c = deferred.get(r);
            GpuSection s = (GpuSection) c;
            boolean keep = c.isDirty() && (inSlot(slots[0], s) || inSlot(slots[1], s));
            if (keep && view.contains(c)) {
                compile(c);
                keep = c.isDirty();
            }
            if (keep) {
                deferred.set(w++, c);
            } else {
                s.nthgpu$setDeferred(false);
            }
        }
        for (int r = n - 1; r >= w; r--) {
            deferred.remove(r);
        }
    }

    /** The reason to rebuild the list now (BUILD_*), or BUILD_NONE. */
    private int needsRebuild(Slot slot, View view, boolean spectator) {
        if (slot.generation < 0) {
            return BUILD_FIRST;
        }
        if ((MathUtil.floor(view.camX) >> 4) != slot.keyX || (MathUtil.floor(view.camY) >> 4) != slot.keyY
                || (MathUtil.floor(view.camZ) >> 4) != slot.keyZ || spectator != slot.keySpectator) {
            return BUILD_CAMERA;
        }
        if (fogEnd(view.fogSqr) > slot.pvsFogEnd - FOG_MOVEMENT) {
            return BUILD_FOG;
        }
        long age = System.nanoTime() - slot.builtAt;
        if (slot.rebuildRequested && age >= REBUILD_DELAY_NS && slot.framesSinceBuild >= REBUILD_DELAY_FRAMES) {
            return BUILD_REQUEST;
        }
        return age >= REBUILD_MAX_AGE_NS ? BUILD_AGE : BUILD_NONE;
    }

    private static double fogEnd(double fogSqr) {
        return fogSqr >= 1.0e30 ? Double.POSITIVE_INFINITY : Math.sqrt(fogSqr);
    }

    /** Nothirium's traversal (super.setup), without the frustum, with fog slack; becomes the candidate list. */
    private void capture(Slot slot, View view, boolean spectator, Runnable bfs) {
        slot.generation++;
        slot.list.clear();
        // Before the traversal: a request made during it (an empty section that becomes "all visible") counts for the next one.
        slot.rebuildRequested = false;
        slot.framesSinceBuild = 0;
        double fog = fogEnd(view.fogSqr);
        double padded = fog == Double.POSITIVE_INFINITY ? Double.MAX_VALUE : (fog + FOG_PADDING) * (fog + FOG_PADDING);
        GpuCulling.beginCapture(this, slot, view, padded);
        try {
            bfs.run();
        } finally {
            GpuCulling.endCapture();
        }
        for (int i = 0, n = slot.list.size(); i < n; i++) {
            writeEntry(slot, i, slot.list.get(i));
        }
        slot.keyX = MathUtil.floor(view.camX) >> 4;
        slot.keyY = MathUtil.floor(view.camY) >> 4;
        slot.keyZ = MathUtil.floor(view.camZ) >> 4;
        slot.keySpectator = spectator;
        slot.pvsFogEnd = fog == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : fog + FOG_PADDING;
        slot.builtAt = System.nanoTime();
        slot.builds++;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Frame: setup and draw

    /**
     * Setup of a pass (in place of ChunkRendererGL43.setup). true = done on the GPU; false = the caller runs Nothirium's original
     * setup (GPU culling off, shadow pass without gpuShadowLightCulling, or failure).
     */
    public boolean setup(double camX, double camY, double camZ, Frustum frustum, boolean spectator, Runnable bfs) {
        boolean shadow = shaderMode && Shaders.isShadowPass;
        Slot slot = slots[shadow ? 1 : 0];
        if (slot == null) {
            return false;
        }
        slot.activeThisFrame = false;
        if (!GpuCulling.usable() || shadow && !NthConfig.gpuShadowLightCulling) {
            return false;
        }
        if (!GpuCulling.reflection()) {
            GpuCulling.fail("reflection", "RenderLib Frustum fields or Nothirium's resortTransparency not found");
            return false;
        }
        boolean check = GpuCulling.beginCheck();
        try {
            GpuCulling.drainOffThread(this);
            pollReadback(slot);
            // The shadow pass volume (limitShadowPass) is replaced by the light's, on the GPU.
            ShadowArea.beginSetup(false);
            GpuCulling.resortTransparency(renderer, camX, camY, camZ, frustum);
            View view = slot.view;
            view.frustum = frustum;
            view.camX = camX;
            view.camY = camY;
            view.camZ = camZ;
            view.fogSqr = FogUtil.calculateFogEndSqr();
            view.entityX = GpuCulling.frustumX(frustum);
            view.entityY = GpuCulling.frustumY(frustum);
            view.entityZ = GpuCulling.frustumZ(frustum);
            view.light = shadow ? planesOf(RenderUtil.getProjectionModelViewMatrix()) : null;
            if (slot.frames == 0) {
                slot.firstFrameNanos = System.nanoTime();
            }
            int reason = needsRebuild(slot, view, spectator);
            if (reason != BUILD_NONE) {
                long t0 = System.nanoTime();
                capture(slot, view, spectator, bfs);
                long took = System.nanoTime() - t0;
                slot.buildNanos += took;
                slot.buildMaxNanos = Math.max(slot.buildMaxNanos, took);
                slot.buildsBy[reason]++;
            }
            long d0 = System.nanoTime();
            processDeferred(view);
            if (STALE_PROBE && !shadow) {
                probeStale(slot, view);
            }
            deferredNanos += System.nanoTime() - d0;
            deferredFrames++;
            deferredMax = Math.max(deferredMax, deferred.size());
            if (!shadow) {
                markAnimatedSprites(slot);
            }
            upload(slot);
            slot.parity ^= 1;
            if (BufferSync.measuring()) {
                slot.drawFence[slot.parity] = BufferSync.checkGpuReuse(slot.drawFence[slot.parity]);
            }
            hizFrameFor(shadow);
            fillParams(slot, view, frustum, shadow);
            dispatch(slot, !shadow && hizFrame ? 1 : 0);
            if (shadow || !hizFrame) {
                scheduleReadback(slot);
            }
            slot.activeThisFrame = true;
            slot.frames++;
            slot.framesSinceBuild++;
            return true;
        } catch (Throwable t) {
            GpuCulling.fail("setup", t);
            return false;
        } finally {
            if (check) {
                GpuCulling.endCheck("setup");
            }
        }
    }

    private void hizFrameFor(boolean shadow) {
        if (!shadow) {
            hizFrame = (NthConfig.gpuOcclusionCulling || HizAuto.on()) && !hiz.failed() && GpuTime.hizAllowed();
            hizPhase2Done = !hizFrame;
        }
    }

    /**
     * Draws a layer (in place of ChunkRendererGL43.renderChunks). false = this pass is not on the GPU (the caller draws as before).
     * original = the previous renderChunks (for a pack program without the offset).
     */
    public boolean render(ChunkRenderPass pass, Runnable original) {
        Slot slot = slotForPass();
        if (slot == null || !slot.activeThisFrame) {
            return false;
        }
        boolean check = GpuCulling.beginCheck();
        try {
            if (shaderMode) {
                renderShaderMode(slot, pass, original);
            } else {
                renderNothirium(slot, pass);
            }
        } catch (Throwable t) {
            // The layer goes undrawn this frame; on the next, Nothirium draws as before.
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            GL15.glBindBuffer(PARAMETER_BUFFER, 0);
            GL30.glBindVertexArray(0);
            GpuCulling.fail("draw", t);
        } finally {
            if (check) {
                GpuCulling.endCheck("draw");
            }
        }
        return true;
    }

    /** Without shaders: Nothirium's own shader, with the same uniforms as its renderChunks. */
    private void renderNothirium(Slot slot, ChunkRenderPass pass) {
        GLShader.push();
        try {
            nothiriumShader.use();
            Matrix4f matrix = RenderUtil.getProjectionModelViewMatrix().copy();
            matrix.translate((float) RenderUtil.getCameraOffsetX(), (float) RenderUtil.getCameraOffsetY(), (float) RenderUtil.getCameraOffsetZ());
            GLUtil.setMatrix(nothiriumShader.getUniform("u_ModelViewProjectionMatrix"), matrix);
            FogUtil.setupFogFromGL(nothiriumShader);
            drawLayer(slot, pass);
        } finally {
            GLShader.pop();
        }
    }

    /** With shaders: the pack program OptiFine left active, like nthshaders' ShaderVaos.draw. */
    private void renderShaderMode(Slot slot, ChunkRenderPass pass, Runnable original) {
        if (!ShaderPrograms.isPatched(Shaders.activeProgramID)) {
            // Program without the per-chunk offset: nthshaders draws chunk by chunk, with this pass's candidate list (no GPU culling on
            // this layer).
            fillNothiriumList(slot, pass);
            original.run();
            return;
        }
        BlockRenderLayer layer = BlockRenderLayerUtil.getBlockRenderLayer(pass);
        ShadersRender.preRenderChunkLayer(layer);
        try {
            drawLayer(slot, pass);
        } finally {
            ShadersRender.postRenderChunkLayer(layer);
            // The offset is context state: whatever comes next with the same program must not inherit it.
            GL20.glVertexAttrib3f(ShaderVaos.OFFSET_LOCATION, 0.0F, 0.0F, 0.0F);
        }
    }

    private void fillNothiriumList(Slot slot, ChunkRenderPass pass) {
        List<RenderChunk> list = lists.get(pass);
        list.clear();
        for (AbstractRenderChunk c : slot.list) {
            if (c.getVBOPart(pass) != null) {
                list.add((RenderChunk) c);
            }
        }
    }

    private void drawLayer(Slot slot, ChunkRenderPass pass) {
        if (slot.index == 0 && slot.faceMode == CullParams.FACES_CAMERA && pass != ChunkRenderPass.TRANSLUCENT
                && (slot.faceLayers >> pass.ordinal() & 1) != 0) {
            // Skipping back faces only matches the previous image with OpenGL face culling on.
            FaceCulling.checkCullOnDraw(pass.ordinal());
        }
        int[] vaos = slot.vaos[slot.parity];
        if (hizFrame && slot.index == 0) {
            if (pass == ChunkRenderPass.SOLID) {
                if (hizBegin()) {
                    // Phase 1: what was visible before; the pyramid; phase 2: the rest that Hi-Z lets through.
                    draw(slot, 0, vaos[0]);
                    if (hizBuild()) {
                        slot.params.hiz(hiz.width(), hiz.height(), hiz.levels(), 0);
                        dispatch(slot, 2);
                        hizPhase2Done = true;
                        scheduleReadback(slot);
                        draw(slot, 4, vaos[4]);
                        return;
                    }
                    // Phase 1 already drew a part: drawing everything again on top does not change the image.
                }
                noteHizFallback();
                dispatch(slot, 0);
                hizPhase2Done = true;
                scheduleReadback(slot);
                draw(slot, 0, vaos[0]);
                return;
            }
            if (!hizPhase2Done) {
                // A layer before SOLID (or SOLID not drawn): no Hi-Z this frame.
                noteHizFallback();
                dispatch(slot, 0);
                hizPhase2Done = true;
                scheduleReadback(slot);
            }
        }
        int region = pass.ordinal();
        draw(slot, region, vaos[region]);
    }

    /** Hi-Z's depth copy before phase 1, timed for measureGpuTime once Hi-Z's first checked uses are over. */
    private boolean hizBegin() {
        boolean timed = hiz.validated() && GpuTime.begin(GpuTime.HIZ);
        try {
            return hiz.begin(GpuCulling.programs());
        } finally {
            if (timed) {
                GpuTime.end();
            }
        }
    }

    /** The depth copy after phase 1 and the pyramid, timed like hizBegin. */
    private boolean hizBuild() {
        boolean timed = hiz.validated() && GpuTime.begin(GpuTime.HIZ);
        try {
            return hiz.build(GpuCulling.programs());
        } finally {
            if (timed) {
                GpuTime.end();
            }
        }
    }

    /** End of frame (GpuCulling.onFrameEnd): measureGpuTime's frame. */
    void onFrameEnd() {
        GpuTime.onFrameEnd(shaderMode, hizFrame, hiz.validated());
    }

    private void noteHizFallback() {
        hizFallbacks++;
        if (hiz.problem != null && !hiz.problem.equals(hizLastProblem)) {
            hizLastProblem = hiz.problem;
            nthshaders.core.Status.LOG.warn("[gadolinium] Hi-Z has no effect this frame: {}", hiz.problem);
        }
    }

    /** The indirect draw of a region; the main pass's is timed for measureGpuTime. */
    private void draw(Slot slot, int region, int vao) {
        boolean timed = slot.index == 0 && GpuTime.begin(GpuTime.DRAW);
        try {
            drawUntimed(slot, region, vao);
        } finally {
            if (timed) {
                GpuTime.end();
            }
        }
    }

    private void drawUntimed(Slot slot, int region, int vao) {
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, slot.commands[slot.parity]);
        GL15.glBindBuffer(PARAMETER_BUFFER, slot.counts[slot.parity]);
        long indirect = (long) region * maxCommands * 16;
        long count = region * 4L;
        // This frame's real limit, not the region size: GPUs without native counts (Intel) emit a conditional draw for each possible
        // command, even those that do not exist.
        int perSection = region == 3 || !faceCommands ? 1 : FACE_COMMANDS;
        int maxDraws = Math.min(maxCommands, slot.list.size() * perSection);
        if (GpuCulling.gl46()) {
            GL46.glMultiDrawArraysIndirectCount(GL11.GL_QUADS, indirect, count, maxDraws, 16);
        } else {
            ARBIndirectParameters.glMultiDrawArraysIndirectCountARB(GL11.GL_QUADS, indirect, count, maxDraws, 16);
        }
        GL15.glBindBuffer(PARAMETER_BUFFER, 0);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
        if (BufferSync.measuring()) {
            slot.drawFence[slot.parity] = BufferSync.replaceFence(slot.drawFence[slot.parity]);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Culling dispatch

    /** The 6 frustum planes of a matrix (same math as RenderLib's Frustum). */
    private static float[][] planesOf(Matrix4f m) {
        return new float[][] {
                { m.m30 + m.m00, m.m31 + m.m01, m.m32 + m.m02, m.m33 + m.m03 },
                { m.m30 - m.m00, m.m31 - m.m01, m.m32 - m.m02, m.m33 - m.m03 },
                { m.m30 + m.m10, m.m31 + m.m11, m.m32 + m.m12, m.m33 + m.m13 },
                { m.m30 - m.m10, m.m31 - m.m11, m.m32 - m.m12, m.m33 - m.m13 },
                { m.m30 + m.m20, m.m31 + m.m21, m.m32 + m.m22, m.m33 + m.m23 },
                { m.m30 - m.m20, m.m31 - m.m21, m.m32 - m.m22, m.m33 - m.m23 } };
    }

    private void fillParams(Slot slot, View view, Frustum frustum, boolean shadow) throws Throwable {
        CullParams p = slot.params;
        if (shaderMode) {
            p.origin(CullParams.OFFSET_ORIGIN, RenderUtil.getCameraEntityX(), RenderUtil.getCameraEntityY(), RenderUtil.getCameraEntityZ());
        } else {
            p.origin(CullParams.OFFSET_ORIGIN, view.camX, view.camY, view.camZ);
        }
        p.origin(CullParams.PLANE_ORIGIN, view.entityX, view.entityY, view.entityZ);
        p.origin(CullParams.FOG_ORIGIN, view.camX, view.camY, view.camZ);
        p.fogEndSqr(view.fogSqr >= 1.0e30 ? -1.0F : (float) view.fogSqr);
        int k = 0;
        if (view.light != null) {
            // The shadow map volume first: if space runs out, RenderLib's planes are left over (less culling, never more).
            for (float[] l : view.light) {
                p.plane(k++, l[0], l[1], l[2], l[3]);
            }
        }
        for (Vec4 v : GpuCulling.planes(frustum)) {
            if (k >= CullParams.MAX_PLANES) {
                break;
            }
            p.plane(k++, v.x, v.y, v.z, v.w);
        }
        slot.planeCount = k;
        if (!shadow && hizFrame) {
            p.viewProj(RenderUtil.getProjectionModelViewMatrix());
        }
        fillFaceParams(slot, p, shadow);
    }

    /**
     * Per-direction face culling: main pass by the real camera position (RenderLib: entity + ActiveRenderInfo offset, with third
     * person and view bobbing); shadow pass by the light direction.
     */
    private void fillFaceParams(Slot slot, CullParams p, boolean shadow) {
        int mode = CullParams.FACES_OFF;
        int layers = 0;
        if (faceCommands && FaceCulling.drawing()) {
            if (shadow) {
                float[] d = NthConfig.gpuFaceCullingShadow ? lightDirection(RenderUtil.getProjectionModelViewMatrix()) : null;
                if (d != null) {
                    mode = CullParams.FACES_LIGHT;
                    layers = 7;
                    p.lightDirection(d[0], d[1], d[2]);
                }
            } else {
                double cx = RenderUtil.getCameraX();
                double cy = RenderUtil.getCameraY();
                double cz = RenderUtil.getCameraZ();
                // A camera far from the entity is not the one we expect (another mod's matrix): nothing is skipped.
                boolean near = Math.abs(cx - RenderUtil.getCameraEntityX()) < 64.0 && Math.abs(cy - RenderUtil.getCameraEntityY()) < 64.0
                        && Math.abs(cz - RenderUtil.getCameraEntityZ()) < 64.0;
                if (near && !Double.isNaN(cx + cy + cz)) {
                    mode = CullParams.FACES_CAMERA;
                    layers = FaceCulling.mainPassLayers(shaderMode);
                    p.origin(CullParams.FACE_ORIGIN, cx, cy, cz);
                }
            }
        }
        p.faces(mode, layers);
        slot.faceMode = mode;
        slot.faceLayers = layers;
    }

    /**
     * The direction to the light from the shadow pass matrix (OptiFine's orthographic projection x light view): row 2 of the matrix
     * is -k times the view's z axis, and the light is on the +z side. null = not orthographic (nothing is skipped).
     */
    private static float[] lightDirection(Matrix4f m) {
        if (Math.abs(m.m30) + Math.abs(m.m31) + Math.abs(m.m32) > 1.0e-4F || Math.abs(m.m33 - 1.0F) > 1.0e-3F) {
            return null;
        }
        float x = -m.m20;
        float y = -m.m21;
        float z = -m.m22;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(len > 1.0e-6F)) {
            return null;
        }
        return new float[] { x / len, y / len, z / len };
    }

    /** The culling compute shaders of a pass, timed for measureGpuTime. */
    private void dispatch(Slot slot, int mode) {
        boolean timed = GpuTime.begin(slot.index == 0 ? GpuTime.CULL : GpuTime.CULL_SHADOW);
        try {
            dispatchUntimed(slot, mode);
        } finally {
            if (timed) {
                GpuTime.end();
            }
        }
    }

    private void dispatchUntimed(Slot slot, int mode) {
        GpuPrograms programs = GpuCulling.programs();
        int n = slot.list.size();
        int groupCount = (n + 255) / 256;
        CullParams p = slot.params;
        p.info(n, maxCommands, mode, slot.planeCount);
        p.groups(groupCount);
        int previousUbo = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, params);
        p.buf.position(0).limit(CullParams.SIZE);
        GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, p.buf);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, params);
        int parity = slot.parity;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, slot.sections);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, slot.commands[parity]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, slot.offsets[parity]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, slot.counts[parity]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, history);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, groups);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, flags);
        state.saveProgram();
        if (mode == 2) {
            state.saveTextures();
            state.bind(GpuPrograms.UNIT_B, hiz.pyramid(), hiz.sampler());
        }
        try {
            for (int stage = 0; stage < 3; stage++) {
                if (stage != 1 && groupCount == 0) {
                    continue;
                }
                GL20.glUseProgram(programs.cull[stage]);
                GL43.glDispatchCompute(stage == 1 ? 1 : groupCount, 1, 1);
                GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            }
            // BUFFER_UPDATE: the glCopyBufferSubData of the counts (F3) reads what the compute wrote.
            GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT
                    | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        } finally {
            if (mode == 2) {
                state.restoreTextures();
            }
            state.restoreProgram();
            for (int b = 0; b <= 6; b++) {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, b, 0);
            }
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, 0);
            // After glBindBufferBase, which also changes the target's general binding.
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, previousUbo);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Animated textures (Smart Animations with Naughthirium) and diagnostics

    /**
     * Naughthirium marks each section's animated textures when the traversal visits it (addToRenderLists). With the traversal
     * only running now and then, the marking is done here, once per tick (Smart Animations clears the marks every tick), for all
     * candidates of the main pass. Without Naughthirium or with Smart Animations off, nothing.
     */
    private void markAnimatedSprites(Slot slot) throws Throwable {
        if (!GpuCulling.hasNaughthirium() || markedTick == GpuCulling.clientTicks) {
            return;
        }
        markedTick = GpuCulling.clientTicks;
        if (!SmartAnimations.isActive()) {
            return;
        }
        for (int i = 0, n = slot.list.size(); i < n; i++) {
            GpuCulling.naughthiriumLoad(slot.list.get(i));
        }
    }

    /** Every READBACK_FRAMES frames, copies the counts to read later, without waiting for the GPU (F3 only). */
    private void scheduleReadback(Slot slot) {
        if (slot.fence != 0 || --slot.readbackIn > 0) {
            return;
        }
        int read = GL11.glGetInteger(GL42.GL_COPY_READ_BUFFER_BINDING);
        int write = GL11.glGetInteger(GL42.GL_COPY_WRITE_BUFFER_BINDING);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, slot.counts[slot.parity]);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, slot.readback);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, COUNTS_BYTES);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private void pollReadback(Slot slot) {
        if (slot.fence == 0) {
            return;
        }
        int status = GL32.glClientWaitSync(slot.fence, 0, 0L);
        if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
            return;
        }
        GL32.glDeleteSync(slot.fence);
        slot.fence = 0;
        slot.readbackIn = READBACK_FRAMES;
        int read = GL11.glGetInteger(GL42.GL_COPY_READ_BUFFER_BINDING);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, slot.readback);
        readbackInts.clear();
        GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, readbackInts);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
        for (int i = 0; i < slot.counted.length; i++) {
            slot.counted[i] = readbackInts.get(i);
        }
    }

    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shaderMode", shaderMode);
        m.put("capacity", maxSections + " sections");
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("candidates", slot.list.size());
            s.put("traversals", slot.builds);
            s.put("frames", slot.frames);
            s.put("lastCounts", java.util.Arrays.toString(slot.counted));
            s.put("traversalsPerSecond", String.format("%.2f", buildsPerSecond(slot)));
            s.put("traversalMs", String.format("avg %.3f, max %.3f", slot.builds == 0 ? 0.0 : slot.buildNanos / 1e6 / slot.builds,
                    slot.buildMaxNanos / 1e6));
            Map<String, Object> by = new LinkedHashMap<>();
            for (int r = 0; r < BUILD_REASONS.length; r++) {
                by.put(BUILD_REASONS[r], slot.buildsBy[r]);
            }
            s.put("traversalsByReason", by);
            m.put(slot.index == 0 ? "mainPass" : "shadowPass", s);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        for (int r = 0; r < REQUEST_REASONS.length; r++) {
            req.put(REQUEST_REASONS[r], requests[r]);
        }
        m.put("traversalRequests", req);
        if (STALE_PROBE) {
            m.put("dirtyOnScreen", staleDescribe());
        }
        m.put("dirtyWithoutTraversal", NthConfig.gpuCullingSkipNoOpDirtyRebuilds
                ? "not visited " + dirtySkippedUnvisited + ", empty with no blocks " + dirtySkippedEmpty : "disabled in the config");
        m.put("deferredBuilds", deferred.size());
        m.put("deferredBuildsMax", deferredMax);
        m.put("deferredBuildsMsPerSetup", String.format("%.4f", deferredFrames == 0 ? 0.0 : deferredNanos / 1e6 / deferredFrames));
        m.put("commandsPerRegion", maxCommands + (faceCommands ? " (per-direction culling: up to " + FACE_COMMANDS + " per section)" : ""));
        m.put("perDirectionCulling", FaceCulling.describe());
        m.put("hiz", NthConfig.gpuOcclusionCulling || HizAuto.on() ? (hiz.problem == null ? "ok" : hiz.problem) + ", no effect in " + hizFallbacks + " frames"
                : NthConfig.gpuOcclusionAuto ? "automatic: " + HizAuto.describe() : "disabled in the config");
        return m;
    }

    /** On, off and why, for the first F3 line. */
    private String hizState() {
        if (hiz.failed()) {
            return "failed";
        }
        if (NthConfig.gpuOcclusionCulling) {
            return hizFrame || GpuTime.hizAllowed() ? "on" : "on (measureGpuTime: off in this 2 s window)";
        }
        return NthConfig.gpuOcclusionAuto ? (HizAuto.on() ? "auto, on now" : "auto, off now") : "off";
    }

    private String faceState() {
        if (FaceCulling.failure() != null) {
            return "failed";
        }
        return faceCommands && FaceCulling.drawing() ? "on" : "off";
    }

    private static String percent(long part, long whole) {
        return whole > 0 ? Math.round(part * 100.0 / whole) + "%" : "-";
    }

    void debugLines(List<String> lines) {
        Slot main = slots[0];
        Slot shadow = slots[1];
        int[] c = main.counted;
        int candidates = main.list.size();
        lines.add("[gadolinium] GPU culling: on | Hi-Z: " + hizState() + " | Faces: " + faceState() + " | Shadow: "
                + (shadow == null ? "no shaders" : NthConfig.gpuShadowLightCulling ? "on" : "off") + " | Entities: "
                + EntityGraphCulling.state());
        String faces = main.faceMode != CullParams.FACES_CAMERA ? "off in this pass"
                : c[9] > 0 ? percent(c[9] - c[8], c[9]) + " of quads" : "not measured with Hi-Z";
        lines.add(String.format("[gadolinium] Culled: frustum and fog %s (%d of %d sections), faces %s%s", percent(candidates - c[5], candidates),
                candidates - c[5], candidates, faces, hizFrame ? String.format(", Hi-Z %s (%d sections)", percent(c[6], c[5]), c[6]) : ""));
        lines.add("[gadolinium] " + EntityGraphCulling.frameLine());
        lines.add(String.format("[gadolinium] Commands: S %d, C %d, CM %d, T %d", c[0] + c[4], c[1], c[2], c[3]));
        if (shadow != null && shadow.frames > 0 && NthConfig.gpuShadowLightCulling) {
            int[] s = shadow.counted;
            lines.add(String.format("[gadolinium] Shadow: commands S %d, C %d, CM %d; %d candidates, frustum and light culled %s%s", s[0], s[1], s[2],
                    shadow.list.size(), percent(shadow.list.size() - s[5], shadow.list.size()),
                    shadow.faceMode == CullParams.FACES_LIGHT && s[9] > 0 ? ", faces " + percent(s[9] - s[8], s[9]) : ""));
        }
        GpuTime.debugLines(lines, shaderMode);
        lines.add(String.format("[gadolinium] Traversals: %d in total (shadow %d), %.1f per second, %.2f ms each; deferred builds: %d",
                main.builds, shadow != null ? shadow.builds : 0, buildsPerSecond(main), main.builds == 0 ? 0.0 : main.buildNanos / 1e6 / main.builds,
                deferred.size()));
    }

    private static double buildsPerSecond(Slot slot) {
        double seconds = (System.nanoTime() - slot.firstFrameNanos) / 1e9;
        return slot.frames == 0 || seconds <= 0 ? 0.0 : slot.builds / seconds;
    }

}
