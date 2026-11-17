// SPDX-License-Identifier: MIT
package nthcompat.iceandfire;

import java.util.IdentityHashMap;

/** An Ice and Fire Tabula model with its number and its stored fixed-name lookups (TabulaCubeCache). */
public interface TabulaModelKey {

    int nthcompat$modelId();

    IdentityHashMap<String, Object> nthcompat$named();

}
