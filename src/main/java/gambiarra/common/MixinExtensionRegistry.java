// SPDX-License-Identifier: MIT
package gambiarra.common;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;

/**
 * Registers a Mixin extension (IExtension) at run time.
 *
 * Uses Mixin internals, the same way MixinExtras does: Extensions.add() only adds to the general list, and the list used
 * while transforming (activeExtensions, private) is only rebuilt on the next environment phase change. So the extension
 * also goes straight into the active list.
 *
 * Used by nthshaders (injection audit) and nthcompat (OptiRefine's acc$ placeholders). The caller remembers whether it
 * already registered: calling twice would put the extension in the general list twice.
 */
public final class MixinExtensionRegistry {

    private MixinExtensionRegistry() {
    }

    /** Throws if the Mixin in use does not have this structure (the caller decides what to do). */
    @SuppressWarnings("unchecked")
    public static synchronized void add(IExtension extension) throws ReflectiveOperationException {
        Object transformer = MixinEnvironment.getDefaultEnvironment().getActiveTransformer();
        Extensions extensions = (Extensions) ((IMixinTransformer) transformer).getExtensions();
        extensions.add(extension);
        Field activeField = Extensions.class.getDeclaredField("activeExtensions");
        activeField.setAccessible(true);
        List<IExtension> active = new ArrayList<>((List<IExtension>) activeField.get(extensions));
        if (!active.contains(extension)) {
            active.add(extension);
        }
        activeField.set(extensions, Collections.unmodifiableList(active));
    }

}
