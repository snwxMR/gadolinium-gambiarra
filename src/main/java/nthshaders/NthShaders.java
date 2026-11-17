// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import nthshaders.common.BuildInfo;
import nthshaders.compat.UnfocusedFps;
import nthshaders.core.Diagnostics;
import nthshaders.core.Status;
import nthshaders.gpu.GpuCulling;
import nthshaders.render.GlErrorGuard;

/**
 * Gadolinium: shader mode on Nothirium's GL 4.3, GPU culling and the compatibility fixes that depend on Nothirium
 * (nthshaders.compat). Client only. The fixes that do not depend on it live in a separate mod, Gambiarra.
 *
 * Nothirium and RenderLib are optional ("after", not "required-after"): with them, the load order is the same as before;
 * without them, the game starts and the version gate (Status) turns the shader part off.
 */
@Mod(modid = NthShaders.MODID, name = "Gadolinium", version = NthShaders.VERSION, clientSideOnly = true,
        acceptableRemoteVersions = "*", dependencies = "after:nothirium;after:renderlib")
public class NthShaders {

    public static final String MODID = "gadolinium";
    public static final String VERSION = BuildInfo.VERSION;

    @EventHandler
    public void onPostInit(FMLPostInitializationEvent event) {
        // The OpenGL context already exists in post-init (the window opens before the mods).
        Diagnostics.captureGpu();
        Status.LOG.info("[gadolinium] State: {}", Status.describe());
        Diagnostics.write("game started");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            GlErrorGuard.onClientTick();
            GpuCulling.onClientTick();
            nthshaders.core.StartupNotice.onClientTick();
            nthshaders.render.ShaderOffProbe.onClientTick();
        }
    }

    /** Diagnostics only (log): the state the hand is drawn with, after turning shaders off. */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        nthshaders.render.ShaderOffProbe.atHand();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            GpuCulling.onFrameEnd();
            UnfocusedFps.onFrameEnd();
        }
    }

    /** F3: what GPU culling drew (Nothirium's counts there are those of the candidate list). */
    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            event.getLeft().addAll(GpuCulling.debugLines());
            event.getLeft().addAll(nthshaders.core.StartupNotice.debugLines());
        }
    }

}
