// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/**
 * Audit of a module's injections, done at the END of each target class's transformation (in the Mixin extensions' export(),
 * after MixinExtras' @WrapMethod and @WrapOperation, which come after the plugins' postApply).
 *
 * Counts, in the final class, the calls to handlers whose name contains {@code <prefix>$<Mixin>$} (Mixin and MixinExtras
 * keep that part of the final name). One call per injection that went in; calls made from inside those same methods (the
 * bridge MixinExtras generates for @Local/@Share calls the handler) do not count.
 *
 * Same idea as nthshaders' audit (nthshaders.core.InjectionAudit), but generic: each module gives the prefix of its
 * handlers and who receives the result. Registering an extension uses Mixin internals (see MixinExtensionRegistry); the
 * caller decides what to do if it fails.
 */
public final class HandlerCallAudit implements IExtension {

    /** Receives the result of each audited mixin. */
    public interface Listener {
        void result(String targetClassName, String mixinSimpleName, int found, int expected);
    }

    private static final class Expectation {
        final String mixinSimpleName;
        final String marker;
        final int expected;
        final Listener listener;

        Expectation(String mixinSimpleName, String marker, int expected, Listener listener) {
            this.mixinSimpleName = mixinSimpleName;
            this.marker = marker;
            this.expected = expected;
            this.listener = listener;
        }
    }

    private static final HandlerCallAudit INSTANCE = new HandlerCallAudit();

    /** target class (with '.') -> mixins expected in it. */
    private static final Map<String, List<Expectation>> pending = new LinkedHashMap<>();
    private static boolean registered;

    private HandlerCallAudit() {
    }

    /** Registers the extension (only once). Throws if the Mixin in use does not allow it. */
    public static synchronized void register() throws ReflectiveOperationException {
        if (!registered) {
            MixinExtensionRegistry.add(INSTANCE);
            registered = true;
        }
    }

    /**
     * Called from the plugin's postApply: the count waits for the export() of the same class.
     *
     * @param handlerPrefix prefix of the module's handlers, e.g. "nthrc" for nthrc$Mixin$something
     */
    public static synchronized void expect(String targetClassName, String mixinClassName, String handlerPrefix, int expected,
            Listener listener) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        pending.computeIfAbsent(targetClassName.replace('/', '.'), k -> new ArrayList<>())
                .add(new Expectation(simpleName, handlerPrefix + "$" + simpleName + "$", expected, listener));
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
        List<Expectation> expectations;
        synchronized (HandlerCallAudit.class) {
            expectations = pending.remove(name.replace('/', '.'));
        }
        if (expectations == null) {
            return;
        }
        for (Expectation e : expectations) {
            e.listener.result(name.replace('/', '.'), e.mixinSimpleName, countCalls(classNode, e.marker), e.expected);
        }
    }

    static int countCalls(ClassNode classNode, String marker) {
        int calls = 0;
        for (MethodNode method : classNode.methods) {
            if (method.name.contains(marker)) {
                // Calls from inside the mixin's own methods are not injections: with @Local/@Share, MixinExtras generates a bridge
                // (<handler>$mixinextras$bridge$N) that the injection point calls and that calls the handler.
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.contains(marker)) {
                    calls++;
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    // Two @WrapOperation on the same point (e.g. OptiRefine and this mod on RenderManager's doRender): MixinExtras chains them,
                    // and the inner one becomes an Operation (lambda) that the outer one calls. The injection point only has the reference to
                    // the handler (or its bridge) in the invokedynamic arguments.
                    for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                        if (arg instanceof Handle && ((Handle) arg).getName().contains(marker)) {
                            calls++;
                        }
                    }
                }
            }
        }
        return calls;
    }

}
