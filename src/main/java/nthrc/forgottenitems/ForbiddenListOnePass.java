// SPDX-License-Identifier: MIT
package nthrc.forgottenitems;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/**
 * forbiddenListOnePass: Forgotten Items' FIHelper.getForbiddenList, for each entry with "*" in the banned list, walks the item registry
 * calling Item.REGISTRY.getKeys().toArray() twice on each turn, and each call copies the whole registry (O(N^2) per entry). The registry
 * does not change during the build, so the array is the same: here it is made once and reused until the end of the method.
 *
 * Per thread (the client and the integrated server may build at the same time; Forgotten Items' method does not protect itself). The array
 * is only valid within one call (it is forgotten at its end) and with the same size as the registry. The key set cannot be compared by
 * identity: getKeys() returns a new wrapper (unmodifiableSet) on each call, but the two toArray calls of the method are always of the same
 * registry, which does not change midway.
 */
public final class ForbiddenListOnePass {

    private ForbiddenListOnePass() {
    }

    private static final ThreadLocal<Object[]> CURRENT = new ThreadLocal<>();
    private static final AtomicLong MADE = new AtomicLong();
    private static final AtomicLong REUSED = new AtomicLong();

    public static Object[] toArray(Set<?> keys, Operation<Object[]> original) {
        Object[] array = CURRENT.get();
        if (array != null && array.length == keys.size()) {
            REUSED.incrementAndGet();
            return array;
        }
        array = original.call(keys);
        MADE.incrementAndGet();
        CURRENT.set(array);
        return array;
    }

    public static void forget() {
        CURRENT.remove();
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registryCopies", MADE.get());
        m.put("copiesAvoided", REUSED.get());
        return m;
    }

}
