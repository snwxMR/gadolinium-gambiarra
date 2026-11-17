// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

/**
 * Frame counter (RenderTickEvent, one per frame, the same for the shadow pass and the main pass) used by
 * DistantDragonAnimation. No Ice and Fire class here: it loads even without the mod.
 */
public final class IafFrames {

    private IafFrames() {
    }

    static volatile int frame;

    public static void onFrame() {
        frame++;
    }

}
