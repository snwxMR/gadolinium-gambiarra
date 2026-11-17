// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;

/**
 * sessionLockOncePerSecond (see SessionLockMixin). session.lock only serves for another process to signal it opened the same world: the game's check
 * opens the file and compares a number, for every chunk saved (hundreds in an autosave). Here, after a check that passed, the others of the same save
 * for 1 second are skipped. If another process takes the world, the warning comes at the next check, up to 1 second later instead of at the next chunk;
 * nothing else changes.
 */
public final class SessionLock {

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final boolean VALIDATE = WorldgenConfig.validate;

    private static final AtomicLong skipped = new AtomicLong();

    private SessionLock() {
    }

    /** true = the last check that passed on this save was less than 1 second ago (lastCheck 0 = none yet). */
    public static boolean skip(long lastCheck) {
        if (lastCheck == 0L || System.nanoTime() - lastCheck >= WINDOW_NANOS) {
            return false;
        }
        skipped.incrementAndGet();
        if (VALIDATE) {
            // The original runs: if it throws, validation mode shows it at once. Here it only counts what would be skipped.
            ValidationLog.checked("minecraft.sessionLockOncePerSecond");
            return false;
        }
        return true;
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checksSkipped", skipped.get());
        return m;
    }

}
