// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import nthshaders.common.MixinExtensionRegistry;

/**
 * Audit of the addon's injections, done at the END of each target class's transformation.
 *
 * Why not in the plugin's postApply: MixinExtras applies @WrapMethod (and other "late" injectors) in a Mixin extension that
 * runs AFTER the plugins' postApply. There, a @WrapMethod has not left any call to the handler in the class yet and the
 * count was 0. Mixin calls the extensions' export() after all of that, with the final class; that is where we count.
 *
 * Registering an extension uses Mixin internals (see nthshaders.common.MixinExtensionRegistry). If that fails, the audit
 * is unavailable and the addon turns itself off: without proof that the injections went in, never act halfway.
 */
final class InjectionAudit implements IExtension {

    private static final InjectionAudit INSTANCE = new InjectionAudit();

    /** target class (with '.') -> addon mixin -> expected injections. */
    private static final Map<String, Map<String, Integer>> pending = new LinkedHashMap<>();
    private static boolean registered;

    private InjectionAudit() {
    }

    /** Called from the plugin's onLoad. false = audit unavailable. */
    static synchronized boolean register() {
        if (registered) {
            return true;
        }
        try {
            MixinExtensionRegistry.add(INSTANCE);
            registered = true;
            return true;
        } catch (Throwable t) {
            Status.LOG.warn("[gadolinium] Could not register the injection audit with Mixin", t);
            return false;
        }
    }

    /** Called from the plugin's postApply: the count waits for the export() of the same class. */
    static synchronized void expect(String targetClassName, String mixinClassName, int expected) {
        pending.computeIfAbsent(targetClassName.replace('/', '.'), k -> new LinkedHashMap<>()).put(mixinClassName, expected);
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
        Map<String, Integer> mixins;
        synchronized (InjectionAudit.class) {
            mixins = pending.remove(name.replace('/', '.'));
        }
        if (mixins == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : mixins.entrySet()) {
            String mixinClassName = e.getKey();
            String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            Status.recordInjection(simpleName, name.replace('/', '.'), countHandlerCalls(classNode, simpleName), e.getValue());
        }
    }

    /**
     * Every addon handler is named nthshaders$&lt;Mixin&gt;$&lt;something&gt;; Mixin and MixinExtras keep that part of the
     * final name. Counts the calls to those methods in the final class (one per injection that went in).
     */
    static int countHandlerCalls(ClassNode classNode, String mixinSimpleName) {
        String marker = "nthshaders$" + mixinSimpleName + "$";
        int calls = 0;
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.contains(marker)) {
                    calls++;
                }
            }
        }
        return calls;
    }

}
