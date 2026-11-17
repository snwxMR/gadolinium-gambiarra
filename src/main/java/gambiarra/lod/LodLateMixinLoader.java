// SPDX-License-Identifier: MIT
package gambiarra.lod;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Registers mixins.gambiarra.lod.mods.json after the regular mods join the classpath: LLibrary and Lycanites are not coremods, and the version check reads the bytes of their classes (the same path as IafLateMixinLoader). Without them, the plugin
 * applies nothing.
 */
public class LodLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.gambiarra.lod.mods.json");
    }

}
