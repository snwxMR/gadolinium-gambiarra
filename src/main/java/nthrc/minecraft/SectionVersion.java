// SPDX-License-Identifier: MIT
package nthrc.minecraft;

/**
 * restingItemsSkipCollision: write count of a chunk section (BlockStateContainer, see SectionVersionMixin). Every block change in a loaded chunk goes
 * through the container's set(index, state); the number only goes up. Two equal reads on the same container = no block of the section changed between
 * them.
 */
public interface SectionVersion {

    int nthrc$version();

}
