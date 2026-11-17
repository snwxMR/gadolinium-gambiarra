// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.Loader;
import nthshaders.common.StartupReport;

/**
 * What StartupReport collected (options on in the config that did not start) goes once to the chat and to F3, and the log gets a summary
 * a few seconds after a world is entered, when the renderer and GPU culling have started. Called from NthShaders (client only).
 */
public final class StartupNotice {

    /** 5 s after entering a world. */
    private static final int DELAY_TICKS = 100;

    private static Object world;
    private static int ticks;

    private StartupNotice() {
    }

    public static void onClientTick() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                world = null;
                return;
            }
            if (mc.world != world) {
                world = mc.world;
                ticks = 0;
            }
            ticks++;
            if (ticks == DELAY_TICKS) {
                Status.LOG.info(summary());
            }
            if (ticks >= DELAY_TICKS && ticks % 20 == 0) {
                for (String line : StartupReport.takeChat("Gadolinium")) {
                    mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(TextFormatting.YELLOW + line));
                }
            }
        } catch (Throwable t) {
            // The notice never gets in the way of the game.
        }
    }

    public static List<String> debugLines() {
        return StartupReport.debugLines("[gadolinium]");
    }

    static String summary() {
        if (!NthConfig.enabled) {
            return "[gadolinium] Summary: the addon is off in the config (enabled=false).";
        }
        if (!Status.isActive()) {
            return "[gadolinium] Summary: the addon is not active, so none of its options run (" + String.join("; ", Status.problemList())
                    + "); Nothirium runs as without it.";
        }
        boolean gpu = NthConfig.gpuCulling;
        boolean[] on = { true, NthConfig.limitShadowPass, gpu, gpu && NthConfig.gpuShadowLightCulling, gpu && NthConfig.gpuOcclusionCulling,
                gpu && !NthConfig.gpuOcclusionCulling && NthConfig.gpuOcclusionAuto, gpu && NthConfig.gpuFaceCulling,
                gpu && NthConfig.gpuFaceCulling && NthConfig.gpuShadowLightCulling && NthConfig.gpuFaceCullingShadow,
                gpu && NthConfig.gpuCullingEntities, gpu && NthConfig.gpuCullingSkipNoOpDirtyRebuilds, NthConfig.unfocusedFpsLimit > 0,
                NthConfig.fixRenderLibOptiRefine, NthConfig.fixUpdateClouds, NthConfig.fixEntityRendering,
                NthConfig.fixEntityRendering && NthConfig.entityFixFastAccess, NthConfig.limitChunkCompileBuffers,
                !"arrivalOrder".equalsIgnoreCase(NthConfig.chunkCompileOrder), NthConfig.fixSmartAnimationsShadowRace };
        int count = 0;
        for (boolean b : on) {
            if (b) {
                count++;
            }
        }
        // With Gambiarra, its own RenderLib fix acts; without it, nobody turns Smart Animations on.
        boolean gambiarra = Loader.isModLoaded("gambiarra");
        int without = (NthConfig.fixRenderLibOptiRefine && gambiarra ? 1 : 0) + (NthConfig.fixSmartAnimationsShadowRace && !gambiarra ? 1 : 0);
        return StartupReport.summary("[gadolinium]", count, without);
    }

}
