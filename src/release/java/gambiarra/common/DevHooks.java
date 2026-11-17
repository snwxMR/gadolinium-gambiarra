// SPDX-License-Identifier: MIT
package gambiarra.common;

/**
 * RELEASE build: test hooks off.
 *
 * Every test hook (-Dnthshaders.* / -Dnthcompat.* properties that simulate failures or versions, or change options) sits
 * inside {@code if (DevHooks.ON) { ... }}. Since ON is a false compile-time constant, javac does not even generate those
 * blocks: the release jar has neither the code nor the property names. The other version of this class (src/dev/java) is
 * the one for the development jar, used only by the smoketest (remapDevJar task).
 *
 * This class goes into no jar: after compilation nothing references it.
 */
public final class DevHooks {

    public static final boolean ON = false;

    private DevHooks() {
    }

}
