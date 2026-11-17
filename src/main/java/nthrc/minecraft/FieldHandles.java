// SPDX-License-Identifier: MIT
package nthrc.minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import gambiarra.common.ValidationLog;
import nthrc.WorldgenConfig;

/**
 * reflectionFieldHandles (see ReflectionFieldMixin): field access through a MethodHandle, found once. ReflectionField (RenderLib, RLTweaker)
 * uses java.lang.reflect.Field; on Java 25 Cleanroom passes each use through CompatibilityHooks, which recomputes the field's position
 * (Unsafe.objectFieldOffset) every time. A field MethodHandle keeps the position.
 *
 * Static field: the object received is ignored, as with Field. Any error (final field, wrong type) returns "not done", and the original path
 * runs, with the original exception if any.
 */
public final class FieldHandles {

    private static final boolean VALIDATE = WorldgenConfig.validate;
    private static final String ITEM = "minecraft.reflectionFieldHandles";
    /** Returned by get when the original path must run. */
    public static final Object NOT_DONE = new Object();

    private static final AtomicLong calls = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();

    private final Field field;
    private final MethodHandle getter, setter;
    private final boolean isBoolean, isInt;

    private FieldHandles(Field field, MethodHandle getter, MethodHandle setter) {
        this.field = field;
        this.getter = getter;
        this.setter = setter;
        this.isBoolean = field != null && field.getType() == boolean.class;
        this.isInt = field != null && field.getType() == int.class;
    }

    public static FieldHandles of(Field field) {
        if (field == null) {
            return new FieldHandles(null, null, null);
        }
        MethodHandle g = null;
        MethodHandle s = null;
        try {
            field.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            MethodHandle rawGet = lookup.unreflectGetter(field);
            g = isStatic ? MethodHandles.dropArguments(rawGet, 0, Object.class) : rawGet;
            if (!Modifier.isFinal(field.getModifiers())) {
                MethodHandle rawSet = lookup.unreflectSetter(field);
                s = isStatic ? MethodHandles.dropArguments(rawSet, 0, Object.class) : rawSet;
            }
            Class<?> type = field.getType();
            g = g.asType(MethodType.methodType(type == boolean.class || type == int.class ? type : Object.class, Object.class));
            if (s != null) {
                s = s.asType(MethodType.methodType(void.class, Object.class, type == boolean.class || type == int.class ? type : Object.class));
            }
        } catch (Throwable t) {
            failed.incrementAndGet();
            return new FieldHandles(null, null, null);
        }
        return new FieldHandles(field, g, s);
    }

    public Object get(Object instance) {
        if (getter == null || isBoolean || isInt) {
            return NOT_DONE;
        }
        try {
            Object v = (Object) getter.invokeExact(instance);
            calls.incrementAndGet();
            if (VALIDATE) {
                ValidationLog.check(ITEM, Objects.equals(v, field.get(instance)), field + ": get disagrees");
            }
            return v;
        } catch (Throwable t) {
            return NOT_DONE;
        }
    }

    public boolean set(Object instance, Object value) {
        if (setter == null || isBoolean || isInt) {
            return false;
        }
        try {
            setter.invokeExact(instance, value);
            calls.incrementAndGet();
            if (VALIDATE) {
                ValidationLog.check(ITEM, field.get(instance) == value, field + ": set disagrees");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasBoolean() {
        return getter != null && isBoolean;
    }

    public boolean getBoolean(Object instance) throws Throwable {
        boolean v = (boolean) getter.invokeExact(instance);
        calls.incrementAndGet();
        if (VALIDATE) {
            ValidationLog.check(ITEM, v == field.getBoolean(instance), field + ": getBoolean disagrees");
        }
        return v;
    }

    public boolean setBoolean(Object instance, boolean value) {
        if (setter == null || !isBoolean) {
            return false;
        }
        try {
            setter.invokeExact(instance, value);
            calls.incrementAndGet();
            if (VALIDATE) {
                ValidationLog.check(ITEM, field.getBoolean(instance) == value, field + ": setBoolean disagrees");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasInt() {
        return getter != null && isInt;
    }

    public int getInt(Object instance) throws Throwable {
        int v = (int) getter.invokeExact(instance);
        calls.incrementAndGet();
        if (VALIDATE) {
            ValidationLog.check(ITEM, v == field.getInt(instance), field + ": getInt disagrees");
        }
        return v;
    }

    public boolean setInt(Object instance, int value) {
        if (setter == null || !isInt) {
            return false;
        }
        try {
            setter.invokeExact(instance, value);
            calls.incrementAndGet();
            if (VALIDATE) {
                ValidationLog.check(ITEM, field.getInt(instance) == value, field + ": setInt disagrees");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accesses", calls.get());
        m.put("fieldsWithoutHandle", failed.get());
        return m;
    }

}
