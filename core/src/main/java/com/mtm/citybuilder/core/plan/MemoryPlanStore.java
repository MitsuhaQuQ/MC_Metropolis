package com.mtm.citybuilder.core.plan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A plan store in memory, for tests and tools: what it holds is lost with it. */
public final class MemoryPlanStore implements PlanStore {
    private final Map<String, byte[]> entries = new ConcurrentHashMap<>();

    @Override
    public byte[] load(String key) {
        byte[] data = entries.get(PlanStore.checkKey(key));
        return data == null ? null : data.clone();
    }

    @Override
    public void save(String key, byte[] data) {
        entries.put(PlanStore.checkKey(key), data.clone());
    }

    /** How many entries it holds. */
    public int size() {
        return entries.size();
    }
}
