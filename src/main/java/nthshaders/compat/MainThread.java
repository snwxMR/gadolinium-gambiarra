// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.compat;

import net.minecraft.client.Minecraft;

/**
 * Separate from CompileBufferLimit on purpose: CompileBufferLimit is read early (by the mixin plugin, before the game
 * classes), and only this class mentions Minecraft. It is only loaded during the game.
 */
final class MainThread {

    private MainThread() {
    }

    static boolean isCurrent() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.isCallingFromMinecraftThread();
    }

}
