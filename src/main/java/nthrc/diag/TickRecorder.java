// SPDX-License-Identifier: MIT
package nthrc.diag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import gambiarra.common.BuildInfo;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * Server tick recorder (option diagnostics.recordTicks of gambiarra-worldgen.cfg, off by default). It only reads the game.
 *
 * On the server thread it only measures and counts, and hands a ready line (numbers, no text) to a queue; formatting and writing to disk
 * stay on the TickWriter thread. If the queue fills up (disk stalled), the line is dropped and counted: the tick never waits.
 *
 * Per tick: duration (from the start of ServerTickEvent, at the highest priority, to its end, at the lowest: catches the whole tick,
 * including other mods acting on the event), time since the start of the previous tick, loaded chunks, entities and tile entities (all
 * worlds), chunks loaded/unloaded/sent in the tick, players, chunks around the players not sent yet, and the cost of the recorder itself
 * on the server thread.
 *
 * Readiness: after joining, teleporting (a jump larger than the view distance in one tick), changing dimension or respawning, the time
 * until all chunks in range (view distance - 1, what the server sends) have been sent to the player. It changes nothing: it only queries
 * PlayerChunkMap.
 */
public final class TickRecorder {

    /** Readiness that does not finish (player always moving) is closed after this. */
    private static final long READY_TIMEOUT_NANOS = 120_000_000_000L;

    private static TickRecorder active;

    private final MinecraftServer server;
    private final TickWriter writer;

    private long tickStartNanos, tickStartMillis, previousStartNanos;
    private int loadedThisTick, unloadedThisTick, sentThisTick;
    private long costNanos;
    private boolean failed;

    private final Map<UUID, Track> tracks = new HashMap<>();

    private TickRecorder(MinecraftServer server, TickWriter writer) {
        this.server = server;
        this.writer = writer;
    }

    /** When opening the world (server started). */
    public static synchronized void start(MinecraftServer server) {
        stop();
        try {
            WorldgenConfig.reloadDiagnostics();
        } catch (Throwable t) {
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: could not read the config; off.", t);
            return;
        }
        if (!WorldgenConfig.recordTicks) {
            return;
        }
        try {
            String header = "nthrc tick recorder | addon " + BuildInfo.VERSION + " (commit " + BuildInfo.COMMIT + ") | "
                    + (server.isDedicatedServer() ? "dedicated server" : "integrated server") + " | view distance "
                    + server.getPlayerList().getViewDistance() + " | started " + System.currentTimeMillis();
            TickWriter writer = TickWriter.open(new java.io.File(WorldgenConfig.home(), "logs"),
                    WorldgenConfig.recordMaxFileMB * 1024L * 1024L, WorldgenConfig.recordOldFiles, header);
            active = new TickRecorder(server, writer);
            MinecraftForge.EVENT_BUS.register(active);
            WorldgenStatus.LOG.info("[gambiarra] Tick recorder: on (logs/{}.csv and logs/{}.csv, up to {} MB per file, {} old files).", TickWriter.TICKS, TickWriter.READY, WorldgenConfig.recordMaxFileMB, WorldgenConfig.recordOldFiles);
        } catch (Throwable t) {
            active = null;
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: could not start; off for this session.", t);
        }
    }

    /** When stopping the server: writes what is left and puts the summary in the log. */
    public static synchronized void stop() {
        TickRecorder r = active;
        active = null;
        if (r != null) {
            MinecraftForge.EVENT_BUS.unregister(r);
            r.writer.close();
        }
    }

    private void fail(Throwable t) {
        if (!failed) {
            failed = true;
            WorldgenStatus.LOG.warn("[gambiarra] Tick recorder: error; stopped recording for this session (the game carries on normally).", t);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || failed) {
            return;
        }
        long now = System.nanoTime();
        previousStartNanos = tickStartNanos;
        tickStartNanos = now;
        tickStartMillis = System.currentTimeMillis();
        costNanos += System.nanoTime() - now;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failed || tickStartNanos == 0) {
            return;
        }
        long end = System.nanoTime();
        try {
            TickWriter.TickRow row = new TickWriter.TickRow();
            row.epochMs = tickStartMillis;
            row.tickNanos = end - tickStartNanos;
            row.sinceLastNanos = previousStartNanos == 0 ? 0 : tickStartNanos - previousStartNanos;
            for (WorldServer w : DimensionManager.getWorlds()) {
                if (w != null) {
                    row.chunks += w.getChunkProvider().getLoadedChunkCount();
                    row.entities += w.loadedEntityList.size();
                    row.tileEntities += w.loadedTileEntityList.size();
                }
            }
            row.loaded = loadedThisTick;
            row.unloaded = unloadedThisTick;
            row.sent = sentThisTick;
            loadedThisTick = unloadedThisTick = sentThisTick = 0;
            row.missing = trackPlayers(end);
            row.players = server.getPlayerList().getCurrentPlayerCount();
            costNanos += System.nanoTime() - end;
            row.costNanos = costNanos;
            costNanos = 0;
            writer.offer(row);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Readiness of each player; returns the sum of the chunks around not sent yet. */
    private int trackPlayers(long now) {
        int radius = Math.max(0, server.getPlayerList().getViewDistance() - 1);
        int totalMissing = 0;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            Track t = tracks.computeIfAbsent(player.getUniqueID(), k -> new Track());
            WorldServer world = player.getServerWorld();
            int dim = world.provider.getDimension();
            int cx = (int) player.managedPosX >> 4;
            int cz = (int) player.managedPosZ >> 4;
            String reason = t.pendingReason;
            long start = t.pendingReason != null ? t.pendingNanos : tickStartNanos;
            if (reason == null && t.seen) {
                if (dim != t.dim) {
                    reason = "dimension";
                } else if (Math.abs(cx - t.cx) > radius || Math.abs(cz - t.cz) > radius) {
                    reason = "teleport";
                }
            }
            int target = (2 * radius + 1) * (2 * radius + 1);
            if (reason != null) {
                if (t.reason != null) {
                    emit(player, t, "interrupted", now, target, -1);
                }
                t.reason = reason;
                t.startNanos = start;
                t.startMillis = System.currentTimeMillis() - (now - start) / 1_000_000L;
                t.ticks = 0;
                t.pendingReason = null;
            }
            t.seen = true;
            t.dim = dim;
            t.cx = cx;
            t.cz = cz;
            int missing = missing(world.getPlayerChunkMap(), player, cx, cz, radius);
            totalMissing += missing;
            if (t.reason != null) {
                t.ticks++;
                if (missing == 0) {
                    emit(player, t, "ready", now, target, 0);
                } else if (now - t.startNanos > READY_TIMEOUT_NANOS) {
                    emit(player, t, "unfinished-120s", now, target, missing);
                }
            }
        }
        return totalMissing;
    }

    private static int missing(PlayerChunkMap map, EntityPlayerMP player, int cx, int cz, int radius) {
        int missing = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                PlayerChunkMapEntry entry = map.getEntry(x, z);
                if (entry == null || !entry.isSentToPlayers() || !entry.containsPlayer(player)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /** missing: chunks not sent yet (-1 = not counted). */
    private void emit(EntityPlayerMP player, Track t, String result, long now, int target, int missing) {
        TickWriter.ReadyRow row = new TickWriter.ReadyRow();
        row.epochMs = t.startMillis;
        row.player = player.getName();
        row.reason = t.reason;
        row.dim = t.dim;
        row.result = result;
        row.nanos = now - t.startNanos;
        row.ticks = t.ticks;
        row.target = target;
        row.missing = missing;
        writer.offer(row);
        t.reason = null;
    }

    private void markPending(net.minecraft.entity.player.EntityPlayer player, String reason) {
        if (player instanceof EntityPlayerMP && !failed) {
            long now = System.nanoTime();
            Track t = tracks.computeIfAbsent(player.getUniqueID(), k -> new Track());
            t.pendingReason = reason;
            t.pendingNanos = now;
            costNanos += System.nanoTime() - now;
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            markPending(event.player, "join");
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        try {
            markPending(event.player, "respawn");
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            markPending(event.player, "dimension");
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            Track t = tracks.remove(event.player.getUniqueID());
            if (t != null && t.reason != null && event.player instanceof EntityPlayerMP && !failed) {
                emit((EntityPlayerMP) event.player, t, "left", System.nanoTime(), 0, -1);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getWorld().isRemote || failed) {
            return;
        }
        long now = System.nanoTime();
        loadedThisTick++;
        costNanos += System.nanoTime() - now;
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getWorld().isRemote || failed) {
            return;
        }
        long now = System.nanoTime();
        unloadedThisTick++;
        costNanos += System.nanoTime() - now;
    }

    @SubscribeEvent
    public void onChunkSent(ChunkWatchEvent.Watch event) {
        if (failed) {
            return;
        }
        long now = System.nanoTime();
        sentThisTick++;
        costNanos += System.nanoTime() - now;
    }

    /** Readiness state of a player (only the server thread touches it). */
    private static final class Track {
        boolean seen;
        int dim, cx, cz;
        String pendingReason;
        long pendingNanos;
        String reason;
        long startNanos, startMillis;
        int ticks;
    }

}
