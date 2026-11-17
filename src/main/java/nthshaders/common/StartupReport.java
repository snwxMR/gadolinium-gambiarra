// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Options that are on in the config but did not start: a code lock that does not match, a shader the driver refused, a mixin that did
 * not go in whole. Each place logs its own warning and also reports here; the mod then shows them once in the chat and in F3, and the
 * log gets a summary when a world is entered. One copy per jar: each mod reports its own.
 *
 * Never throws: it only stores strings.
 */
public final class StartupReport {

    /** Off by the code lock: the target's code (or version) differs from the checked one. */
    private static final Map<String, String> locked = new LinkedHashMap<>();
    /** Did not start for another reason (refused shader, injection missing, error). */
    private static final Map<String, String> failed = new LinkedHashMap<>();
    /** On in the config but not needed here: the target already fixes what the option works around (not a problem, so not in the chat). */
    private static final Map<String, String> notNeeded = new LinkedHashMap<>();
    /** Reported after the first chat message: each gets its own line. */
    private static final List<String> late = new ArrayList<>();
    private static boolean chatShown;

    private StartupReport() {
    }

    public static synchronized void lock(String option, String reason) {
        add(locked, option, reason);
    }

    public static synchronized void failed(String option, String reason) {
        add(failed, option, reason);
    }

    /** The option stays off because the target mod already fixed the problem (for example a newer OptiRefine). */
    public static synchronized void notNeeded(String option, String reason) {
        notNeeded.put(option, reason == null ? "" : reason);
    }

    /** byLock: off by the code lock (or the version); otherwise it did not start for another reason. */
    public static synchronized void off(String option, String reason, boolean byLock) {
        add(byLock ? locked : failed, option, reason);
    }

    private static void add(Map<String, String> into, String option, String reason) {
        if (locked.containsKey(option) || failed.containsKey(option)) {
            return;
        }
        into.put(option, reason == null ? "" : reason);
        if (chatShown) {
            late.add(option);
        }
    }

    public static synchronized boolean isEmpty() {
        return locked.isEmpty() && failed.isEmpty();
    }

    /** The log line for a world entry. on = options on in the config; withoutTarget = of those, how many have no target mod here. */
    public static synchronized String summary(String tag, int on, int withoutTarget) {
        int active = Math.max(0, on - withoutTarget - notNeeded.size() - locked.size() - failed.size());
        return tag + " Summary: " + active + " active, " + withoutTarget + " without their target mod, " + notNeeded.size() + " not needed"
                + names(notNeeded) + ", " + locked.size()
                + " off by the code lock" + names(locked) + ", " + failed.size() + " did not start" + names(failed) + ".";
    }

    /**
     * The chat lines still to show: the first time, one line with every problem so far; afterwards, one line per new problem. Empty when
     * there is nothing new.
     */
    public static synchronized List<String> takeChat(String modName) {
        List<String> out = new ArrayList<>();
        if (!chatShown) {
            chatShown = true;
            List<String> all = new ArrayList<>(locked.keySet());
            all.addAll(failed.keySet());
            if (!all.isEmpty()) {
                out.add("[" + modName + "] " + all.size() + (all.size() == 1 ? " option is" : " options are")
                        + " on in the config but did not start: " + String.join(", ", all) + ". Details in F3 and in logs/latest.log.");
            }
        } else {
            for (String option : late) {
                String reason = locked.containsKey(option) ? locked.get(option) : failed.get(option);
                out.add("[" + modName + "] " + option + " stopped: " + shorten(reason) + ". Details in logs/latest.log.");
            }
        }
        late.clear();
        return out;
    }

    /** F3: one line per problem, or none. */
    public static synchronized List<String> debugLines(String tag) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : locked.entrySet()) {
            out.add(tag + " Off by the code lock: " + e.getKey() + " (" + shorten(e.getValue()) + ")");
        }
        for (Map.Entry<String, String> e : failed.entrySet()) {
            out.add(tag + " Did not start: " + e.getKey() + " (" + shorten(e.getValue()) + ")");
        }
        return out;
    }

    private static String names(Map<String, String> m) {
        return m.isEmpty() ? "" : " (" + String.join(", ", m.keySet()) + ")";
    }

    private static String shorten(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "see the log";
        }
        String oneLine = reason.replace('\n', ' ').trim();
        return oneLine.length() > 90 ? oneLine.substring(0, 87) + "..." : oneLine;
    }

}
