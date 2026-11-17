// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The side the game was launched on, available as early as the mixin plugins (before any mod).
 *
 * The jar loads on both sides: the client parts (nthshaders, nthcompat) ask here so they do nothing on a dedicated server;
 * the server module (nthrc) applies to both.
 */
public final class LaunchSide {

    private LaunchSide() {
    }

    /** true = dedicated server (on the client, the integrated server counts as client). */
    public static boolean isDedicatedServer() {
        try {
            return FMLLaunchHandler.side() == Side.SERVER;
        } catch (Throwable t) {
            // Without FMLLaunchHandler (should not happen on Cleanroom): carries on as a client, the previous behaviour.
            return false;
        }
    }

}
