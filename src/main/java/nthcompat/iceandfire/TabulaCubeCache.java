// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import gambiarra.common.ValidationLog;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.alexthe666.iceandfire.client.model.util.IceAndFireTabulaModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer;
import nthcompat.CompatStatus;

/**
 * cacheTabulaPoses, part 2 (0.9.0): the by-name lookups (IceAndFireTabulaModel.getCube) that Ice and Fire 1.9.1's Tabula animators
 * do every frame, part by part, in setRotationAngles, genderMob and isWing (ice and fire dragon) and in setRotationAngles (sea
 * serpent). Mixins: IceDragonCubeLookupMixin, FireDragonCubeLookupMixin, SeaSerpentCubeLookupMixin.
 *
 * In the parts loop, almost every lookup is {@code pose.getCube(part.boxName)}: the matching part in a pose model (walk, fly,
 * hover...). The pair (part, pose model) never changes, so the answer is stored in the part itself (CubePairs), in an array indexed
 * by the model number (TabulaModelKey): one array read instead of the lookup. The loop's current part comes from
 * setRotationAngles' own Iterator.next. Lookups by a fixed name in the code ("BodyUpper", "armL1"...) are stored per model, by String
 * identity (class constants, always the same object). Nothing is computed differently: only the lookup goes away. With
 * -Dnthcompat.iceandfire.validate=true (dev), each answer is checked against the original getCube (it must be the same object).
 * Render thread only.
 */
public final class TabulaCubeCache {

    /** Stored answer meaning "no part" (getCube returned null). */
    private static final Object NONE = new Object();
    /** Limit of fixed-name lookups stored per model (the animators have ~30 names). */
    private static final int MAX_NAMED = 256;

    private static int nextId = 1;

    /** Validation mode only (on the fast path, a counter per lookup cost a memory write on every call). */
    private static long pairHits;
    private static long pairBuilds;
    private static long namedHits;
    private static long namedBuilds;
    private static long validated;
    private static long mismatches;
    private static String firstMismatch;

    private TabulaCubeCache() {
    }

    /** Number of a Tabula model (1, 2, ...), given at its first lookup. */
    public static int nextModelId() {
        return nextId++;
    }

    /**
     * A getCube of a Tabula animator. current = the current part of the setRotationAngles loop (or null), ok = this animator's injections
     * checked.
     */
    public static AdvancedModelRenderer get(boolean ok, AdvancedModelRenderer current, IceAndFireTabulaModel target, String name,
            Operation<AdvancedModelRenderer> original) {
        if (!ok || target == null) {
            return original.call(target, name);
        }
        AdvancedModelRenderer found;
        if (current != null && current.boxName == name) {
            found = pair((CubePairs) current, target, name, original);
        } else {
            found = named(target, name, original);
        }
        if (IafCompat.validating()) {
            AdvancedModelRenderer expected = original.call(target, name);
            validated++;
            ValidationLog.checked("iceandfire.cacheTabulaPoses.lookup");
            if (expected != found) {
                mismatches++;
                ValidationLog.diff("iceandfire.cacheTabulaPoses.lookup", "'" + name + "' gave another part");
                if (firstMismatch == null) {
                    firstMismatch = name;
                    CompatStatus.LOG.warn("[gambiarra] Ice and Fire, validation of cacheTabulaPoses lookups: '{}' gave another part", name);
                }
                return expected;
            }
        }
        return found;
    }

    private static AdvancedModelRenderer pair(CubePairs cube, IceAndFireTabulaModel target, String name,
            Operation<AdvancedModelRenderer> original) {
        int id = ((TabulaModelKey) target).nthcompat$modelId();
        Object[] pairs = cube.nthcompat$pairs();
        if (pairs == null || id >= pairs.length) {
            Object[] grown = new Object[Math.max(16, Integer.highestOneBit(id) << 1)];
            if (pairs != null) {
                System.arraycopy(pairs, 0, grown, 0, pairs.length);
            }
            pairs = grown;
            cube.nthcompat$setPairs(pairs);
        }
        Object value = pairs[id];
        if (value == null) {
            AdvancedModelRenderer resolved = original.call(target, name);
            value = resolved == null ? NONE : resolved;
            pairs[id] = value;
            pairBuilds++;
        } else {
            if (IafCompat.validating()) {
                pairHits++;
            }
        }
        return value == NONE ? null : (AdvancedModelRenderer) value;
    }

    private static AdvancedModelRenderer named(IceAndFireTabulaModel target, String name, Operation<AdvancedModelRenderer> original) {
        IdentityHashMap<String, Object> named = ((TabulaModelKey) target).nthcompat$named();
        Object value = named.get(name);
        if (value == null) {
            AdvancedModelRenderer resolved = original.call(target, name);
            if (named.size() < MAX_NAMED) {
                named.put(name, resolved == null ? NONE : resolved);
            }
            namedBuilds++;
            return resolved;
        }
        if (IafCompat.validating()) {
            namedHits++;
        }
        return value == NONE ? null : (AdvancedModelRenderer) value;
    }

    static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (IafCompat.validating()) {
            m.put("lookupsByPair", pairHits);
        }
        m.put("pairsStored", pairBuilds);
        if (IafCompat.validating()) {
            m.put("lookupsByFixedName", namedHits);
        }
        m.put("fixedNamesStored", namedBuilds);
        if (IafCompat.validating()) {
            m.put("validation", validated + " lookups compared, " + mismatches + " differences"
                    + (firstMismatch != null ? " (first: " + firstMismatch + ")" : ""));
        }
        return m;
    }

}
