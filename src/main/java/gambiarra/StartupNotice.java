// SPDX-License-Identifier: MIT
package gambiarra;

import gambiarra.common.StartupReport;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import nthcompat.CompatStatus;
import nthrc.WorldgenStatus;

/**
 * Client only: what StartupReport collected (options on in the config that did not start) goes once to the chat and to F3, and the log
 * gets a summary a few seconds after a world is entered, when the worldgen and the render fixes have been decided. On a dedicated
 * server, only the log summary (Gambiarra.onServerStarted).
 */
public final class StartupNotice {

    /** 5 s after entering a world. */
    private static final int DELAY_TICKS = 100;

    private Object world;
    private int ticks;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
                WorldgenStatus.LOG.info(summary());
            }
            if (ticks >= DELAY_TICKS && ticks % 20 == 0) {
                for (String line : StartupReport.takeChat("Gambiarra")) {
                    mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(TextFormatting.YELLOW + line));
                }
            }
        } catch (Throwable t) {
            // The notice never gets in the way of the game.
        }
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            event.getLeft().addAll(StartupReport.debugLines("[gambiarra]"));
            try {
                String lod = gambiarra.lod.LodAim.line();
                if (lod != null) {
                    event.getLeft().add(lod);
                }
            } catch (Throwable t) {
                // The F3 line never gets in the way of the game.
            }
        }
    }

    /** The log line, with the worldgen and the render fixes. */
    static String summary() {
        int[] w = WorldgenStatus.counts();
        int[] c = CompatStatus.counts();
        return StartupReport.summary("[gambiarra]", w[0] + c[0], w[1] + c[1]);
    }

}
