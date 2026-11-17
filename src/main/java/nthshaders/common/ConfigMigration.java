// SPDX-License-Identifier: LGPL-3.0-only
package nthshaders.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * Keeps a config file from an older version working: renames its old sections and values, says in the log what was converted, and
 * warns about every option the mod does not use, so nothing is ignored silently. One copy per jar, like the rest of this package.
 *
 * Order of use: the conversions right after {@code cfg.load()}, then the mod reads its options, then {@link #report()} and
 * {@link #save()}. Once saved, the conversions find nothing more to do.
 */
public final class ConfigMigration {

    private final Configuration cfg;
    private final Logger log;
    private final String tag;
    private final String fileName;

    public ConfigMigration(Configuration cfg, Logger log, String tag, String fileName) {
        this.cfg = cfg;
        this.log = log;
        this.tag = tag;
        this.fileName = fileName;
    }

    /** A section with an old name: every option goes to the new section. An option already in the new section stays as it is. */
    public void renameSection(String oldName, String newName) {
        if (!cfg.hasCategory(oldName)) {
            return;
        }
        ConfigCategory from = cfg.getCategory(oldName);
        ConfigCategory to = cfg.getCategory(newName);
        int moved = 0;
        for (String key : new ArrayList<>(from.keySet())) {
            if (to.containsKey(key)) {
                log.warn("{} Config {}: option {}.{}={} was not converted: {}.{} already exists and stays", tag, fileName, oldName, key,
                        from.get(key).getString(), newName, key);
            } else {
                to.put(key, from.get(key));
                moved++;
            }
        }
        cfg.removeCategory(from);
        log.info("{} Config {} converted: section '{}' -> '{}' (options kept: {})", tag, fileName, oldName, newName, moved);
    }

    /** An option holding an old value name: replaced by the new one. Case and spaces around the old value do not matter. */
    public void renameValue(String section, String key, String oldValue, String newValue) {
        if (!cfg.hasKey(section, key)) {
            return;
        }
        Property property = cfg.getCategory(section).get(key);
        if (oldValue.equalsIgnoreCase(property.getString().trim())) {
            property.set(newValue);
            log.info("{} Config {} converted: {}.{} '{}' -> '{}'", tag, fileName, section, key, oldValue, newValue);
        }
    }

    /** An option that moved to another section, with its value. */
    public void moveKey(String oldSection, String newSection, String key) {
        if (cfg.moveProperty(oldSection, key, newSection)) {
            log.info("{} Config {} converted: {}.{} -> {}.{}", tag, fileName, oldSection, key, newSection, key);
        }
    }

    /** An option that no longer exists: it leaves the file, and the log says what it held and why. */
    public void removeKey(String section, String key, String why) {
        if (cfg.hasKey(section, key)) {
            Property removed = cfg.getCategory(section).remove(key);
            log.warn("{} Config {}: option {}.{}={} no longer exists and was removed ({})", tag, fileName, section, key, removed.getString(), why);
        }
    }

    /**
     * After the mod has read its options. Forge never fills the comment of an option read from the file, only of one the mod asks for:
     * an option with no comment is one nobody asked for. Also warns about values the mod cannot use as written.
     */
    public void report() {
        for (String section : cfg.getCategoryNames()) {
            for (Map.Entry<String, Property> entry : cfg.getCategory(section).entrySet()) {
                Property property = entry.getValue();
                String comment = property.getComment();
                if (comment == null || comment.isEmpty()) {
                    log.warn("{} Config {}: unknown option {}.{}={} is ignored by this version; check its name", tag, fileName, section,
                            entry.getKey(), property.getString());
                } else if (!property.isList()) {
                    checkValue(section, entry.getKey(), property);
                }
            }
        }
    }

    private void checkValue(String section, String key, Property property) {
        String value = property.getString();
        String[] valid = property.getValidValues();
        String problem = null;
        if (valid != null && valid.length > 0 && !containsIgnoreCase(valid, value)) {
            problem = "valid values: " + Arrays.toString(valid);
        } else if (property.getType() == Property.Type.BOOLEAN && !property.isBooleanValue()) {
            problem = "expected true or false";
        } else if (property.getType() == Property.Type.INTEGER) {
            problem = !property.isIntValue() ? "expected a whole number" : outOfRange(property);
        }
        if (problem != null) {
            log.warn("{} Config {}: {}.{} has the invalid value '{}' ({}); the mod is using another value instead", tag, fileName, section, key, value,
                    problem);
        }
    }

    private static String outOfRange(Property property) {
        int value = property.getInt();
        String min = property.getMinValue();
        String max = property.getMaxValue();
        if (min != null && value < Integer.parseInt(min) || max != null && value > Integer.parseInt(max)) {
            return "range: " + min + " to " + max;
        }
        return null;
    }

    private static boolean containsIgnoreCase(String[] values, String value) {
        for (String v : values) {
            if (v.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Always writes: Forge only rewrites the comments when it saves, so a file from an older version would keep its old ones. */
    public void save() {
        cfg.save();
    }

}
