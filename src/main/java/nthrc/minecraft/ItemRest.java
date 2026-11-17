// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gambiarra.common.ValidationLog;
import gambiarra.common.JarUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.border.EnumBorderStatus;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * restingItemsSkipCollision (see ItemRestMixin). Every tick, EntityItem.onUpdate calls pushOutOfBlocks and move (the collision with blocks,
 * suffocation, fire and landing) and, twice, handleWaterMovement, even with the item still.
 *
 * The pushOutOfBlocks + move stretch is deterministic math: it only reads the item's state (position, box, velocity, ground, web, border, fall,
 * fire, step height), the blocks around it, hard-box entities nearby, the GetCollisionBoxesEvent listeners and the world border. Here the
 * last tick's result is kept (Memo) and repeated when:
 *  - the item's input is the same, bit for bit (State.sameInput);
 *  - no block changed in the sections up to 3 blocks around (each section's write count, SectionVersion; the same chunk and section object);
 *  - there is no tile entity in that region (the shape of some blocks depends on it: a moving piston), no entity with a collision box (on
 *    either side) nearby, no GetCollisionBoxesEvent listener outside the known list, and the world border is still and far away;
 *  - the stored tick was "quiet": nothing left the item for the world. The item was not inside a block nor on fire (fire depends on rain,
 *    which is not covered here), did not lose health, and no block touched has an effect on contact or on landing (onEntityCollision, onLanded
 *    overridden).
 * Only for items on the ground with horizontal velocity exactly zero (the item still sliding stays out, as its input changes every tick), only
 * on the server, and only for item classes that do not override those methods.
 *
 * The same goes for handleWaterMovement when the last result was "no water": same box and same region = again no water, with no effect at all.
 *
 * Only the server thread touches server items; the counters do not need a lock.
 */
public final class ItemRest {

    /** Test (development build only): on the tick that would be repeated, runs the original and checks the result. */
    public static final boolean VALIDATE = validateRequested();

    /** Up to 3 blocks around the item's box: collision reads 1 block beyond the box, and the shape of blocks reads the neighbours. */
    private static final int MARGIN = 3;
    private static final int MAX_SECTIONS = 27;
    private static final Object NO_CHUNK = new Object();

    private static long ticks, moveReplays, moveRecords, waterReplays, validated, mismatches, waterValidated, waterMismatches;
    private static long notQuiet, blockedByEntities, blockedByTiles;

    private ItemRest() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    /** Input (before pushOutOfBlocks) or output (after move) of the stretch. */
    public static final class State {
        public double posX, posY, posZ, motionX, motionY, motionZ;
        public double minX, minY, minZ, maxX, maxY, maxZ;
        public boolean onGround, collidedHorizontally, collidedVertically, collided, isInWeb, outsideBorder, isDead;
        public float fallDistance, stepHeight;
        public int fire, health;
        /** Output: what pushOutOfBlocks returned. */
        public boolean pushed;

        void copyFrom(State o) {
            posX = o.posX;
            posY = o.posY;
            posZ = o.posZ;
            motionX = o.motionX;
            motionY = o.motionY;
            motionZ = o.motionZ;
            minX = o.minX;
            minY = o.minY;
            minZ = o.minZ;
            maxX = o.maxX;
            maxY = o.maxY;
            maxZ = o.maxZ;
            onGround = o.onGround;
            collidedHorizontally = o.collidedHorizontally;
            collidedVertically = o.collidedVertically;
            collided = o.collided;
            isInWeb = o.isInWeb;
            outsideBorder = o.outsideBorder;
            isDead = o.isDead;
            fallDistance = o.fallDistance;
            stepHeight = o.stepHeight;
            fire = o.fire;
            health = o.health;
            pushed = o.pushed;
        }

        /** What pushOutOfBlocks and move read from the item. */
        boolean sameInput(State o) {
            return same(posX, o.posX) && same(posY, o.posY) && same(posZ, o.posZ) && same(motionX, o.motionX)
                    && same(motionY, o.motionY) && same(motionZ, o.motionZ) && same(minX, o.minX) && same(minY, o.minY)
                    && same(minZ, o.minZ) && same(maxX, o.maxX) && same(maxY, o.maxY) && same(maxZ, o.maxZ)
                    && onGround == o.onGround && isInWeb == o.isInWeb && outsideBorder == o.outsideBorder
                    && Float.floatToRawIntBits(fallDistance) == Float.floatToRawIntBits(o.fallDistance)
                    && Float.floatToRawIntBits(stepHeight) == Float.floatToRawIntBits(o.stepHeight) && fire == o.fire;
        }

        /** What pushOutOfBlocks and move write to the item (for validation). */
        boolean sameOutput(State o) {
            return sameInput(o) && collidedHorizontally == o.collidedHorizontally && collidedVertically == o.collidedVertically
                    && collided == o.collided && health == o.health && isDead == o.isDead && pushed == o.pushed;
        }

        public boolean sameBox(AxisAlignedBB bb) {
            return same(minX, bb.minX) && same(minY, bb.minY) && same(minZ, bb.minZ) && same(maxX, bb.maxX)
                    && same(maxY, bb.maxY) && same(maxZ, bb.maxZ);
        }

        private static boolean same(double a, double b) {
            return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
        }
    }

    /** The region's sections: the chunk object (or NO_CHUNK), the section's (or null) and the write count. */
    public static final class Env {
        int count;
        final Object[] chunks = new Object[MAX_SECTIONS];
        final Object[] storages = new Object[MAX_SECTIONS];
        final int[] versions = new int[MAX_SECTIONS];

        void copyFrom(Env o) {
            count = o.count;
            System.arraycopy(o.chunks, 0, chunks, 0, count);
            System.arraycopy(o.storages, 0, storages, 0, count);
            System.arraycopy(o.versions, 0, versions, 0, count);
        }

        boolean sameAs(Env o) {
            if (count != o.count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (chunks[i] != o.chunks[i] || storages[i] != o.storages[i] || versions[i] != o.versions[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** What an item remembers (only items that have already come to rest allocate one). */
    public static final class Memo {
        /** This tick: the outside conditions (region, tile entities, entities, listeners, border) were checked and hold. */
        public boolean clearNow;
        final Env envNow = new Env();

        /** This tick's stretch: repeat (replay), record (record) or check in validation. */
        public boolean replay, record, validate;
        public final State in = new State(), out = new State();

        boolean valid;
        final State recIn = new State(), recOut = new State();
        final Env recEnv = new Env();

        boolean waterValid;
        /** Validation: this handleWaterMovement would be repeated; its end checks the original's result. */
        public boolean waterCheck;
        final State waterBox = new State();
        final Env waterEnv = new Env();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Start of tick (ItemRestMixin, head of onUpdate)

    private static long activeTick = Long.MIN_VALUE;
    private static Object activeWorld;
    private static boolean activeCached;

    /**
     * The three mixins of the optimization are active (the item's without the section counter cannot act). Checked once per tick of each world.
     */
    public static boolean on(World world) {
        long t = world.getTotalWorldTime();
        if (t != activeTick || world != activeWorld) {
            activeTick = t;
            activeWorld = world;
            activeCached = nthrc.Hook.allActive(nthrc.Opt.ITEM_REST);
        }
        return activeCached;
    }

    /** Items that may have a memo: on the server, a class that does not override the stretch's methods, still on the ground. */
    public static boolean candidate(EntityItem item) {
        return !item.world.isRemote && item.onGround && item.motionX == 0.0D && item.motionZ == 0.0D && !item.isRiding()
                && !item.isBeingRidden() && CLASS_OK.get(item.getClass());
    }

    /** Checks the outside conditions for this tick and records the region in memo.envNow. */
    public static void beginTick(EntityItem item, Memo memo) {
        ticks++;
        memo.replay = false;
        memo.record = false;
        memo.validate = false;
        memo.clearNow = false;
        World world = item.world;
        AxisAlignedBB bb = item.getEntityBoundingBox();
        if (!listenersOk() || !borderOk(world, item)) {
            return;
        }
        if (!region(world, bb, memo.envNow)) {
            return;
        }
        if (tileEntityNear(world, bb)) {
            blockedByTiles++;
            return;
        }
        if (hardEntityNear(world, item, bb)) {
            blockedByEntities++;
            return;
        }
        memo.clearNow = true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pushOutOfBlocks + move stretch

    /** At pushOutOfBlocks: decides to repeat, record or nothing. memo.in already has the input read by the mixin. */
    public static void decide(EntityItem item, Memo memo) {
        if (!memo.clearNow || !item.onGround || item.motionX != 0.0D || item.motionZ != 0.0D) {
            return;
        }
        if (memo.valid && memo.recIn.sameInput(memo.in) && memo.recEnv.sameAs(memo.envNow)) {
            if (VALIDATE) {
                memo.validate = true;
                memo.record = true;
                return;
            }
            memo.replay = true;
            return;
        }
        memo.record = true;
    }

    /** What pushOutOfBlocks would return (on the repeated tick). */
    public static boolean replayPush(Memo memo) {
        return memo.recOut.pushed;
    }

    /** On the repeated tick, after the move that did not run: the output to apply to the item. */
    public static State replayOut(Memo memo) {
        moveReplays++;
        return memo.recOut;
    }

    /** After the move that ran (recording): memo.out already has the output. Stores whether the tick was quiet. */
    public static void recorded(EntityItem item, Memo memo) {
        if (memo.validate) {
            validated++;
            ValidationLog.check("minecraft.restingItemsSkipCollision.move", memo.recOut.sameOutput(memo.out), "item " + item.getEntityId() + " at (" + item.posX + ", " + item.posY + ", " + item.posZ + ") came out different from the repeated tick");
            if (!memo.recOut.sameOutput(memo.out)) {
                mismatches++;
                if (mismatches <= 10) {
                    WorldgenStatus.LOG.error("[gambiarra] VALIDATION minecraft.restingItemsSkipCollision: item {} at ({}, {}, {}) came out different from "
                            + "the repeated tick", item.getEntityId(), item.posX, item.posY, item.posZ);
                }
            }
        }
        boolean quiet = !memo.out.pushed && memo.in.fire <= 0 && memo.out.health == memo.in.health && !memo.out.isDead
                && !memo.in.isDead && memo.in.fallDistance == 0.0F && noBlockEffects(item.world, item);
        if (!quiet) {
            notQuiet++;
            memo.valid = false;
            return;
        }
        moveRecords++;
        memo.recIn.copyFrom(memo.in);
        memo.recOut.copyFrom(memo.out);
        memo.recEnv.copyFrom(memo.envNow);
        memo.valid = true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // handleWaterMovement

    /** true = the last query said "no water" with the same box and the same region: again no water. */
    public static boolean waterReplay(EntityItem item, Memo memo) {
        if (!memo.clearNow || !memo.waterValid || !memo.waterBox.sameBox(item.getEntityBoundingBox())
                || !memo.waterEnv.sameAs(memo.envNow)) {
            return false;
        }
        waterReplays++;
        return true;
    }

    /** After a query that ran (with this tick's region checked). */
    public static void waterResult(EntityItem item, Memo memo, boolean inWater) {
        if (!memo.clearNow) {
            memo.waterValid = false;
            return;
        }
        memo.waterValid = !inWater;
        if (!inWater) {
            AxisAlignedBB bb = item.getEntityBoundingBox();
            State b = memo.waterBox;
            b.minX = bb.minX;
            b.minY = bb.minY;
            b.minZ = bb.minZ;
            b.maxX = bb.maxX;
            b.maxY = bb.maxY;
            b.maxZ = bb.maxZ;
            memo.waterEnv.copyFrom(memo.envNow);
        }
    }

    /** Validation of the repeated handleWaterMovement: did the original give the same "no water"? */
    public static void waterChecked(boolean original) {
        waterValidated++;
        ValidationLog.check("minecraft.restingItemsSkipCollision.water", !original, "handleWaterMovement found water where the repeated one would say there is none");
        if (original) {
            waterMismatches++;
            if (waterMismatches <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION minecraft.restingItemsSkipCollision: handleWaterMovement found water where the "
                        + "repeated one would say there is none");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The conditions

    /** The sections up to MARGIN blocks around the box; false if the region leaves the world height in an odd way. */
    private static boolean region(World world, AxisAlignedBB bb, Env env) {
        int x0 = MathHelper.floor(bb.minX) - MARGIN >> 4;
        int x1 = MathHelper.floor(bb.maxX) + MARGIN >> 4;
        int y0 = Math.max(0, MathHelper.floor(bb.minY) - MARGIN >> 4);
        int y1 = Math.min(15, MathHelper.floor(bb.maxY) + MARGIN >> 4);
        int z0 = MathHelper.floor(bb.minZ) - MARGIN >> 4;
        int z1 = MathHelper.floor(bb.maxZ) + MARGIN >> 4;
        if (y0 > y1 || (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_SECTIONS) {
            return false;
        }
        int n = 0;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                ExtendedBlockStorage[] sections = chunk == null ? null : chunk.getBlockStorageArray();
                for (int cy = y0; cy <= y1; cy++) {
                    ExtendedBlockStorage s = sections == null ? null : sections[cy];
                    env.chunks[n] = chunk == null ? NO_CHUNK : chunk;
                    env.storages[n] = s;
                    env.versions[n] = s == null ? 0 : ((SectionVersion) s.getData()).nthrc$version();
                    n++;
                }
            }
        }
        env.count = n;
        return true;
    }

    private static boolean tileEntityNear(World world, AxisAlignedBB bb) {
        int x0 = MathHelper.floor(bb.minX) - MARGIN;
        int x1 = MathHelper.floor(bb.maxX) + MARGIN;
        int y0 = MathHelper.floor(bb.minY) - MARGIN;
        int y1 = MathHelper.floor(bb.maxY) + MARGIN;
        int z0 = MathHelper.floor(bb.minZ) - MARGIN;
        int z1 = MathHelper.floor(bb.maxZ) + MARGIN;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos p : chunk.getTileEntityMap().keySet()) {
                    if (p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1 && p.getZ() >= z0
                            && p.getZ() <= z1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A nearby entity with a collision box, on either side (its own, or the one the item has against it): the move's World.getCollisionBoxes adds them
     * to the blocks. The lookup is the same as the game's, in a box larger than its own.
     */
    private static boolean hardEntityNear(World world, EntityItem item, AxisAlignedBB bb) {
        List<Entity> near = world.getEntitiesWithinAABBExcludingEntity(item, bb.grow(1.0D));
        for (int i = 0; i < near.size(); i++) {
            Entity e = near.get(i);
            if (e.getCollisionBoundingBox() != null || item.getCollisionBox(e) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean borderOk(World world, EntityItem item) {
        WorldBorder border = world.getWorldBorder();
        return border.getStatus() == EnumBorderStatus.STATIONARY && border.getClosestDistance(item.posX, item.posZ) > MARGIN + 2;
    }

    /**
     * No block touched by the item has an effect on contact (the blocks inside the box, as in doBlockCollisions) or on landing (the block below, and
     * the one under it if it is air, as in move). Overriding those methods = may have an effect.
     */
    private static boolean noBlockEffects(World world, EntityItem item) {
        AxisAlignedBB bb = item.getEntityBoundingBox();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = MathHelper.floor(bb.minX + 0.001D); x <= MathHelper.floor(bb.maxX - 0.001D); x++) {
            for (int y = MathHelper.floor(bb.minY + 0.001D); y <= MathHelper.floor(bb.maxY - 0.001D); y++) {
                for (int z = MathHelper.floor(bb.minZ + 0.001D); z <= MathHelper.floor(bb.maxZ - 0.001D); z++) {
                    if (BLOCK_HAS_EFFECTS.get(world.getBlockState(p.setPos(x, y, z)).getBlock().getClass())) {
                        return false;
                    }
                }
            }
        }
        int bx = MathHelper.floor(item.posX);
        int by = MathHelper.floor(item.posY - 0.20000000298023224D);
        int bz = MathHelper.floor(item.posZ);
        IBlockState under = world.getBlockState(p.setPos(bx, by, bz));
        IBlockState below = world.getBlockState(p.setPos(bx, by - 1, bz));
        return !BLOCK_HAS_EFFECTS.get(under.getBlock().getClass()) && !BLOCK_HAS_EFFECTS.get(below.getBlock().getClass());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Classes

    /** The item's class does not override any method of the stretch (EntityItem and Entity may; an EntityItem subclass, no). */
    private static final ClassValue<Boolean> CLASS_OK = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != EntityItem.class; c = c.getSuperclass()) {
                if (declaresAny(c, "func_70091_d", "move", "func_145771_j", "pushOutOfBlocks", "func_70072_I",
                        "handleWaterMovement", "func_70041_e_", "canTriggerWalking", "func_70114_g", "getCollisionBox",
                        "func_191955_a", "onInsideBlock")) {
                    return false;
                }
            }
            return true;
        }
    };

    /** The block has an effect on contact or on landing (overrides onEntityCollision or onLanded). */
    private static final ClassValue<Boolean> BLOCK_HAS_EFFECTS = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Block.class; c = c.getSuperclass()) {
                if (declaresAny(c, "func_180634_a", "onEntityCollision", "func_176216_a", "onLanded")) {
                    return true;
                }
            }
            return false;
        }
    };

    private static boolean declaresAny(Class<?> c, String... names) {
        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            return true;
        }
        for (Method m : methods) {
            for (String n : names) {
                if (m.getName().equals(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GetCollisionBoxesEvent listeners

    private static int busId = Integer.MIN_VALUE;
    private static GetCollisionBoxesEvent probe;
    private static IEventListener[] lastListeners;
    private static boolean lastListenersOk;

    /**
     * The GetCollisionBoxesEvent listeners on the main bus: only the known ones that do not touch items. Today, Ice and Fire's
     * (ServerEvents.onGatherCollisionBoxes), which only acts on IPhasesThroughBlock entities, with its fingerprint checked. Any other listener = the
     * result may depend on something we do not see: nothing is repeated.
     */
    private static boolean listenersOk() {
        try {
            if (busId == Integer.MIN_VALUE) {
                Field f = EventBus.class.getDeclaredField("busID");
                f.setAccessible(true);
                busId = f.getInt(MinecraftForge.EVENT_BUS);
                probe = new GetCollisionBoxesEvent(null, null, new AxisAlignedBB(0, 0, 0, 0, 0, 0), new ArrayList<>());
            }
            IEventListener[] listeners = probe.getListenerList().getListeners(busId);
            if (listeners != lastListeners) {
                lastListeners = listeners;
                lastListenersOk = knownListeners(listeners);
            }
            return lastListenersOk;
        } catch (Throwable t) {
            busId = Integer.MIN_VALUE + 1;
            return false;
        }
    }

    private static boolean knownListeners(IEventListener[] listeners) {
        for (IEventListener l : listeners) {
            if (l instanceof EventPriority) {
                continue;
            }
            String s = String.valueOf(l);
            if (!(s.contains("com.github.alexthe666.iceandfire.event.ServerEvents") && s.contains("onGatherCollisionBoxes(")
                    && iceAndFireListenerKnown())) {
                WorldgenStatus.LOG.info("[gambiarra] minecraft.restingItemsSkipCollision: unknown GetCollisionBoxesEvent listener ({}); items run the "
                        + "whole tick", s);
                return false;
            }
        }
        return true;
    }

    private static Boolean iafKnown;

    /** The Ice and Fire 1.9.1 listener and the fingerprint of its method (it only looks at IPhasesThroughBlock entities); the build's LockCheck reads these. */
    static final String IAF_LISTENER = "com/github/alexthe666/iceandfire/event/ServerEvents.class";
    static final String IAF_LISTENER_METHOD = "onGatherCollisionBoxes";
    static final String IAF_LISTENER_PRINT = "870c30a1c35091e9c2e12fe99624869f416fc3b328eb0006d60c05bc25e1fd46";

    private static boolean iceAndFireListenerKnown() {
        if (iafKnown == null) {
            String resource = IAF_LISTENER;
            boolean ok = false;
            try {
                java.io.File jar = JarUtil.jarOf(resource);
                byte[] bytes = jar != null ? JarUtil.readEntry(jar, resource) : null;
                ok = bytes != null && IAF_LISTENER_PRINT.equals(gambiarra.lock.CodeLock.methodPrint(bytes, IAF_LISTENER_METHOD));
            } catch (Exception e) {
                ok = false;
            }
            iafKnown = ok;
        }
        return iafKnown;
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("restingItemTicks", ticks);
        m.put("movesRepeated", moveReplays);
        m.put("movesRecorded", moveRecords);
        m.put("waterRepeated", waterReplays);
        m.put("notQuiet", notQuiet);
        m.put("withEntityNearby", blockedByEntities);
        m.put("withTileEntityNearby", blockedByTiles);
        if (VALIDATE) {
            m.put("checked", validated);
            m.put("differences", mismatches);
            m.put("waterChecked", waterValidated);
            m.put("waterDifferent", waterMismatches);
        }
        return m;
    }

}
