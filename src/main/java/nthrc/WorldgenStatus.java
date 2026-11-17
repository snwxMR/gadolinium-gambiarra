// SPDX-License-Identifier: MIT
package nthrc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import gambiarra.common.DevHooks;
import gambiarra.common.JarUtil;

/**
 * State of the server worldgen module, in a single place.
 *
 * Two switches per optimization:
 *  - allowed (Opt.allowed): decided ONCE, before applying mixins (config + the group's versions + the classes' fingerprints). Not allowed = its mixins are not even applied.
 *  - active (Hook.ok, per mixin): allowed AND the injections checked in the final class (HandlerCallAudit). If an injection did not apply whole, that mixin does not act; the target mod carries on as without this module. It never stays halfway.
 *
 * Independent of nthshaders and nthcompat: it only uses gambiarra.common.
 */
public final class WorldgenStatus {

    public static final Logger LOG = LogManager.getLogger("gambiarra");

    private static boolean evaluated;
    private static final Map<Group, String> groupState = Collections.synchronizedMap(new EnumMap<>(Group.class));
    private static final Map<String, String> versions = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> injections = Collections.synchronizedMap(new LinkedHashMap<>());

    private WorldgenStatus() {
    }

    /** Runs once, in the onLoad of the module's first mixin plugin. Applies on both sides. */
    public static synchronized void evaluate() {
        if (evaluated) {
            return;
        }
        evaluated = true;
        try {
            WorldgenConfig.load();
        } catch (Throwable t) {
            problems.add("unreadable config, using defaults: " + t);
            LOG.warn("[gambiarra] Could not read config/{}; using defaults", WorldgenConfig.FILE_NAME, t);
        }
        if (!WorldgenConfig.enabled) {
            problems.add("disabled in the config (enabled=false)");
            LOG.info("[gambiarra] Disabled in the config (enabled=false): no mixin will be applied.");
            return;
        }
        for (Group g : Group.values()) {
            try {
                evaluate(g);
            } catch (Throwable t) {
                groupState.put(g, "failed reading versions: " + t);
                problems.add(g.id + ": failed reading versions: " + t);
                LOG.error("[gambiarra] Failed reading the versions of {}; its optimizations will not be applied.", g.label, t);
                gambiarra.common.StartupReport.failed(g.id, "failed reading versions: " + t);
            }
        }
    }

    private static void evaluate(Group g) throws IOException {
        if (!g.present()) {
            groupState.put(g, "absent");
            LOG.info("[gambiarra] {} absent: its optimizations are inactive.", g.label);
            return;
        }
        List<String> versionProblems = new ArrayList<>();
        for (Group.Requirement r : g.requirements) {
            String found = devVersionOverride(g, r, r.found());
            versions.put(r.label, String.valueOf(found));
            if (!r.accepts(found)) {
                versionProblems.add(r.label + " unknown: found=" + found + " expected=" + r.expected());
            }
        }
        groupState.put(g, versionProblems.isEmpty() ? "recognized" : "version not recognized");
        for (Opt o : Opt.values()) {
            if (o.group == g) {
                o.allowed = decide(o, versionProblems);
            }
        }
    }

    /**
     * Since 0.3.1, the version does not decide alone: a new version with the same code where the optimization touches (the class byte for byte equal, or its methods with the same fingerprint, see gambiarra.lock.CodeLock) and with all the
     * members our classes use (the links) turns on normally, and the log says the version was not the checked one.
     */
    private static boolean decide(Opt o, List<String> versionProblems) throws IOException {
        if (!o.enabledInConfig) {
            LOG.info("[gambiarra] {}.{}: disabled in the config.", o.group.id, o.key);
            return false;
        }
        List<String> all = new ArrayList<>(mismatches(o.classes));
        all.addAll(missingLinks(o));
        if (all.isEmpty()) {
            if (versionProblems.isEmpty()) {
                LOG.info("[gambiarra] {}.{}: enabled ({} recognized).", o.group.id, o.key, o.group.label);
            } else {
                LOG.info("[gambiarra] {}.{}: enabled: {}, but the code it depends on is the same (classes and methods checked, links "
                        + "present).", o.group.id, o.key, versionProblems);
            }
            return true;
        }
        all.addAll(0, versionProblems);
        if (WorldgenConfig.forceUnknownVersion) {
            LOG.warn("[gambiarra] {}.{}: enabled anyway (forceUnknownVersion=true), despite: {}", o.group.id, o.key, all);
            return true;
        }
        problems.add(o.group.id + "." + o.key + " off: " + all);
        LOG.warn("[gambiarra] {}.{}: OFF (version not recognized). Reasons: {}", o.group.id, o.key, all);
        gambiarra.common.StartupReport.lock(o.group.id + "." + o.key, all.toString());
        return false;
    }

    /**
     * Classes that do not match (empty = all match). Each class accepts any of the values after the name: the whole class's SHA-256, or "m:method=fingerprint[,method=fingerprint]" (all methods listed with the given fingerprint, see
     * gambiarra.lock.CodeLock).
     */
    private static List<String> mismatches(String[][] classes) throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] c : classes) {
            File jar = JarUtil.jarOf(c[0]);
            byte[] bytes = jar != null ? JarUtil.readEntry(jar, c[0]) : null;
            String name = c[0].substring(c[0].lastIndexOf('/') + 1, c[0].length() - ".class".length());
            if (bytes == null) {
                out.add(name + " absent");
                continue;
            }
            String found = JarUtil.sha256Hex(bytes);
            boolean ok = false;
            for (int i = 1; i < c.length && !ok; i++) {
                ok = c[i].startsWith("m:") ? methodsMatch(bytes, c[i].substring(2)) : found.equals(c[i]);
            }
            if (!ok) {
                out.add(name + " differs (class " + found.substring(0, 12) + ", and the checked methods changed)");
            }
        }
        return out;
    }

    private static boolean methodsMatch(byte[] classBytes, String spec) {
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            String print = gambiarra.lock.CodeLock.methodPrint(classBytes, part.substring(0, eq));
            if (!part.substring(eq + 1).equals(print)) {
                return false;
            }
        }
        return true;
    }

    /** Members of the target mod our classes use that do not exist in the installed version (empty = all present). */
    private static List<String> missingLinks(Opt o) throws IOException {
        List<String> out = new ArrayList<>();
        String[] prefixes = o.group.packages();
        if (prefixes.length == 0) {
            return out;
        }
        for (String ours : o.ours()) {
            File jar = JarUtil.jarOf(ours);
            byte[] bytes = jar != null ? JarUtil.readEntry(jar, ours) : null;
            if (bytes == null) {
                out.add("our class absent: " + ours);
                continue;
            }
            for (String m : gambiarra.lock.CodeLock.missingLinks(bytes, prefixes, WorldgenStatus::modClass)) {
                out.add("missing link: " + m);
            }
        }
        return out;
    }

    private static byte[] modClass(String internalName) {
        String resource = internalName + ".class";
        try {
            File jar = JarUtil.jarOf(resource);
            return jar != null ? JarUtil.readEntry(jar, resource) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String devVersionOverride(Group g, Group.Requirement r, String found) {
        if (DevHooks.ON && r == g.requirements[0]) {
            String forced = System.getProperty("nthrc.testVersion." + g.id);
            if (forced != null) {
                LOG.warn("[gambiarra] [TEST] Version of {} simulated: '{}' instead of '{}' (-Dnthrc.testVersion.{})", g.label, forced,
                        found, g.id);
                return forced;
            }
        }
        return found;
    }

    static boolean mixinAllowed(String mixinSimpleName) {
        Hook h = Hook.ofMixin(mixinSimpleName);
        return h != null && h.opt.allowed;
    }

    /**
     * "vanilla" group (VanillaMixinPlugin): its classes load before the mods join the classpath, so the final decision (versions, mod fingerprints) has not come out yet. Applies if the config allows; the handler checks Hook.active().
     */
    static boolean earlyMixinAllowed(String mixinSimpleName) {
        try {
            WorldgenConfig.load();
        } catch (Throwable t) {
            LOG.warn("[gambiarra] Could not read config/{} (vanilla group); using defaults", WorldgenConfig.FILE_NAME, t);
        }
        Hook h = Hook.ofMixin(mixinSimpleName);
        return WorldgenConfig.enabled && h != null && h.early && h.opt.enabledInConfig;
    }

    /**
     * The lock on the game's classes: the fingerprints of the methods the optimization depends on, on the ClassNode Mixin delivers before applying any mixin. Different: the whole optimization stops acting (Opt.vanillaLockOk), and the log shows
     * the fingerprint found.
     */
    static void checkVanilla(String mixinSimpleName, String targetClassName, org.objectweb.asm.tree.ClassNode node) {
        Hook h = Hook.ofMixin(mixinSimpleName);
        if (h == null) {
            return;
        }
        Opt o = h.opt;
        String spec = o.vanillaSpec(targetClassName);
        if (spec == null) {
            return;
        }
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            String method = part.substring(0, eq);
            String expected = part.substring(eq + 1);
            String found = gambiarra.lock.CodeLock.vanillaPrint(node, method);
            String where = targetClassName.substring(targetClassName.lastIndexOf('.') + 1) + "#" + method;
            if (expected.equals(found)) {
                continue;
            }
            o.vanillaLockOk = false;
            String why = found == null ? where + " absent" : where + " differs (found " + found + ")";
            problems.add(o.group.id + "." + o.key + " off: " + why);
            LOG.warn("[gambiarra] {}.{}: OFF, the game code it depends on changed: {}", o.group.id, o.key, why);
            if (WorldgenConfig.enabled && o.enabledInConfig) {
                gambiarra.common.StartupReport.lock(o.group.id + "." + o.key, why);
            }
        }
    }

    /** Without the audit there is no proof the injections applied: no optimization acts. */
    static void auditUnavailable(Throwable t) {
        problems.add("injection audit unavailable: " + t);
        LOG.warn("[gambiarra] Injection audit unavailable (Mixin internals changed?): no optimization will act.", t);
        gambiarra.common.StartupReport.failed("worldgen", "injection audit unavailable: " + t);
    }

    static int expectedInjections(String mixinSimpleName) {
        Hook h = Hook.ofMixin(mixinSimpleName);
        return h != null ? h.expected : 1;
    }

    /** Called by the audit at the end of the target class's transformation. */
    static void recordInjection(String target, String mixin, int found, int expected) {
        boolean ok = found >= expected;
        injections.put(mixin + " -> " + target, found + "/" + expected);
        Hook h = Hook.ofMixin(mixin);
        if (h != null) {
            // "vanilla" group: the optimization may not have been decided yet; the handler checks Hook.active().
            h.ok = ok && (h.early || h.opt.allowed);
        }
        if (ok) {
            LOG.info("[gambiarra] Mixin applied: {} -> {} ({} injections)", mixin, target, found + "/" + expected);
        } else {
            problems.add("incomplete injection: " + mixin + " in " + target + " (" + found + "/" + expected + ")");
            LOG.warn("[gambiarra] Incomplete injection: {} in {} ({}/{}) -- this mixin will not act.", mixin, target, found, expected);
            if (h != null && h.opt.enabledInConfig) {
                gambiarra.common.StartupReport.failed(h.opt.group.id + "." + h.opt.key, mixin + " has " + found + " of " + expected + " injections in " + target);
            }
        }
    }

    /** For the startup summary: {worldgen options on in the config, of those without their target mod here}. */
    public static int[] counts() {
        if (!WorldgenConfig.enabled) {
            return new int[] { 0, 0 };
        }
        int on = 0;
        int without = 0;
        for (Opt o : Opt.values()) {
            if (o.enabledInConfig) {
                on++;
                if ("absent".equals(groupState.get(o.group))) {
                    without++;
                }
            }
        }
        return new int[] { on, without };
    }

    /** State of an optimization for describe (checked = all its mixins checked). */
    private static String state(Opt o) {
        if (!o.allowed) {
            return "not allowed";
        }
        if (!o.vanillaLockOk) {
            return "off (the game code changed)";
        }
        for (Hook h : Hook.values()) {
            if (h.opt == o && !h.ok) {
                return "allowed, injections not checked yet";
            }
        }
        return "active";
    }

    /** Readable summary (log and the smoketest's perf.json, by reflection). */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (versions) {
            m.put("versions", new LinkedHashMap<>(versions));
        }
        for (Group g : Group.values()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("state", groupState.getOrDefault(g, "not evaluated"));
            for (Opt o : Opt.values()) {
                if (o.group == g) {
                    gm.put(o.key, state(o));
                    // The optimizations' classes use classes of the target mod: they are only touched if it was allowed.
                    if (o.allowed) {
                        gm.put(o.key + ".counters", Counters.of(o));
                    }
                }
            }
            m.put(g.id, gm);
        }
        synchronized (injections) {
            m.put("injections", new LinkedHashMap<>(injections));
        }
        synchronized (problems) {
            m.put("problems", new ArrayList<>(problems));
        }
        return m;
    }

    /** One line per allowed optimization, when stopping the server (shows up in the user's game log). */
    public static void logSummary() {
        for (Opt o : Opt.values()) {
            if (o.allowed) {
                LOG.info("[gambiarra] {}.{} this session: {}", o.group.id, o.key, Counters.of(o));
            }
        }
    }

}
