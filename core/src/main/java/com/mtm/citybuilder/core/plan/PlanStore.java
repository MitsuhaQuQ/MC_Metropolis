package com.mtm.citybuilder.core.plan;

/**
 * Where planned blocks are kept: the part of the city plan that is planned once, when first needed, and never changes
 * after (see the design document, "规划块"). A store is part of the world save: dropping an entry makes the block be
 * planned again, maybe differently, against chunks already generated from the old plan.
 * <p>
 * Keys are paths of lower-case letters, digits, '_', '-' and '/'. Implementations must be safe to call from several
 * threads; the planners make sure each key is saved once.
 */
public interface PlanStore {
    /** The data saved under a key, or null when nothing is. */
    byte[] load(String key);

    /** Saves data under a key, for good. */
    void save(String key, byte[] data);

    /** Rejects keys that are not paths of lower-case letters, digits, '_', '-' and '/'. */
    static String checkKey(String key) {
        if (!key.matches("[a-z0-9_\\-]+(/[a-z0-9_\\-]+)*")) {
            throw new IllegalArgumentException("Bad plan key: " + key);
        }
        return key;
    }
}
