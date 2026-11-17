// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import nthshaders.common.DevHooks;

/**
 * How many handler calls each addon mixin must leave in the target class (one per injection). The addon only acts after ALL
 * these mixins were applied and audited with the full count (Status.isActive).
 */
final class Injections {

    /** simple mixin name -> expected injections. */
    static final Map<String, Integer> EXPECTED;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        // @WrapMethod setupProgram, @WrapOperation glShaderSourceARB and glLinkProgramARB, @Inject HEAD init and uninit
        m.put("ShadersMixin", 5);
        // @WrapMethod createChunkRenderer
        m.put("OptifineRendererMixin", 1);
        // @Inject TAIL initVAOs, @ModifyVariable HEAD setup, @Inject HEAD setup (shadow volume), @Inject setup (before writing),
        // @Inject TAIL record, @WrapMethod renderChunks, @Inject TAIL dispose
        m.put("ChunkRendererGL43Mixin", 7);
        // @WrapMethod isFrustumCulled (sections outside the shadow pass volume)
        m.put("RenderChunkShadowAreaMixin", 1);
        EXPECTED = Collections.unmodifiableMap(m);
    }

    private Injections() {
    }

    static int expected(String mixinSimpleName) {
        Integer n = EXPECTED.get(mixinSimpleName);
        int expected = n != null ? n : 1;
        if (DevHooks.ON) {
            // Development build only: requires one more injection from this mixin, to prove the audit turns the whole addon off when
            // one is missing.
            if (mixinSimpleName.equals(System.getProperty("nthshaders.testAuditFail"))) {
                Status.LOG.warn("[gadolinium] [TEST] Requiring {} injections from {} (-Dnthshaders.testAuditFail)", expected + 1,
                        mixinSimpleName);
                expected++;
            }
        }
        return expected;
    }

}
