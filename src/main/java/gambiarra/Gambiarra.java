// SPDX-License-Identifier: MIT
package gambiarra;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import gambiarra.common.BuildInfo;
import gambiarra.common.LaunchSide;
import nthcompat.CompatStatus;
import nthrc.WorldgenStatus;
import nthrc.diag.TickRecorder;

/**
 * Gambiarra: optimizations and fixes for other mods that do not depend on Nothirium or OptiFine (split in 0.11.0 from Nothirium Shaders, now Gadolinium). The worldgen (package nthrc: Recurrent Complex, RTG, Additional Structures, BoP, Wonderful Enchantments)
 * runs on the server, dedicated or integrated; the render fixes (package nthcompat: OptiRefine, Ice and Fire, SmartLeaves, OptiFine dynamic lights) only on the client. The packages kept their names from before the split.
 *
 * Mixins apply through the groups (mixins.nthcompat*.json through the manifest, mixins.nthrc.&lt;mod&gt;.json through WorldgenLateMixinLoader); this @Mod records the state in the log, starts the tick recorder (diagnostics.recordTicks, off by default)
 * when opening the world and, on the client, counts the frames of the distant dragon animation.
 *
 * acceptableRemoteVersions = "*": a client without this mod joins a server with it (and vice versa).
 *
 * "after:nthshaders@[0.11.0,)": Nothirium Shaders (Gadolinium since 1.0.0) is optional, but up to 0.10.0 its jar carried these same classes and mixins (nthcompat, nthrc). With one of those installed, FML stops at the missing mods screen (asks for 0.11.0 or newer) instead
 * of running two mixed copies. Only on this side: "after" on both mods would make a cycle in the load order.
 */
@Mod(modid = Gambiarra.MODID, name = "Gambiarra", version = Gambiarra.VERSION, acceptableRemoteVersions = "*",
        dependencies = "after:reccomplex;after:nthshaders@[0.11.0,)")
public class Gambiarra {

    public static final String MODID = "gambiarra";
    public static final String VERSION = BuildInfo.VERSION;

    @EventHandler
    public void onPostInit(FMLPostInitializationEvent event) {
        WorldgenStatus.LOG.info("[gambiarra] Worldgen: {}", WorldgenStatus.describe());
        if (!LaunchSide.isDedicatedServer()) {
            CompatStatus.LOG.info("[gambiarra] Render: {}", CompatStatus.describe());
            MinecraftForge.EVENT_BUS.register(new ClientFrames());
            MinecraftForge.EVENT_BUS.register(new StartupNotice());
        }
    }

    @EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        TickRecorder.start(FMLCommonHandler.instance().getMinecraftServerInstance());
        if (LaunchSide.isDedicatedServer()) {
            // StartupNotice is client only: on a dedicated server, just the worldgen part.
            int[] w = WorldgenStatus.counts();
            WorldgenStatus.LOG.info(gambiarra.common.StartupReport.summary("[gambiarra]", w[0], w[1]));
        }
    }

    @EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        TickRecorder.stop();
        WorldgenStatus.logSummary();
        gambiarra.common.ValidationLog.summary("world closed");
    }

    /** Client only: a new frame (the same counter for the shadow pass and the main pass), see IafFrames and gambiarra.lod.Lod. */
    public static final class ClientFrames {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                nthcompat.iceandfire.IafFrames.onFrame();
                gambiarra.lod.Lod.onFrame();
            }
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                nthrc.bop.ClientTicks.onClientTick();
            }
        }

    }

}
