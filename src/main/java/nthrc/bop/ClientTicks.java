// SPDX-License-Identifier: MIT
package nthrc.bop;

/**
 * Client ticks counted here (Gambiarra.ClientFrames, at the end of each client tick). The fog caches keep their answers until the next one.
 * The world's total time is not used for that: the server's time packets set it on the client, and it can repeat a value already seen
 * in an earlier tick (rc4: the fog cache returned a value from a moment before, with the player a little further inside the same block).
 */
public final class ClientTicks {

    private static long ticks;

    private ClientTicks() {
    }

    public static void onClientTick() {
        ticks++;
    }

    public static long now() {
        return ticks;
    }

}
