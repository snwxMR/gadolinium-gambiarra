// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Registers mixins.nthcompat.iceandfire.json after the regular mods join the classpath: Ice and Fire and LLibrary are not coremods,
 * and the version check reads the bytes of their classes (the same path as nthrc.WorldgenLateMixinLoader). Cleanroom finds this
 * class through the interface. Without Ice and Fire, the plugin applies nothing.
 */
public class IafLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.nthcompat.iceandfire.json");
    }

}
