// SPDX-License-Identifier: MIT
package nthrc;

import java.util.ArrayList;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Registers the module's mixin groups (mixins.nthrc.&lt;mod&gt;.json) AFTER the regular mods join the classpath.
 *
 * The target mods are not coremods (or not all): when Cleanroom prepares the groups listed in the jar's manifest, their jars are not on the classpath yet, and the version protection (which reads the bytes of the target classes) would not find
 * them. A "late mixin loader" (MixinBooter API, built into Cleanroom; the same path as Fugue, LoliASM and UniversalTweaks) runs after that, on both sides. Cleanroom finds this class through the interface. Mod absent: the plugin applies nothing of
 * the group.
 */
public class WorldgenLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<>();
        for (Group g : Group.values()) {
            configs.add(g.mixinConfig());
        }
        return configs;
    }

}
