// SPDX-License-Identifier: MIT
package nthcompat.pulsar;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Registers mixins.nthcompat.pulsar.json after the regular mods join the classpath: the version check reads the bytes of Pulsar's
 * classes (the same path as nthcompat.iceandfire.IafLateMixinLoader). Cleanroom finds this class through the interface. Without Pulsar,
 * the plugin applies nothing.
 */
public class PulsarLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.nthcompat.pulsar.json");
    }

}
