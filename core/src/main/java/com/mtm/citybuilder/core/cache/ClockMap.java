package com.mtm.citybuilder.core.cache;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * A bounded map by long key, read without a lock: a hit only marks its entry used; a miss adds under a lock and, past
 * the capacity, evicts the oldest entry not used since it was last looked at (the CLOCK approximation of least recently
 * used). The access-ordered maps this replaces took a lock on every read, and the chunk workers queued on it by the
 * dozen when a world starts in a city.
 * <p>
 * For caches of pure results only: which entries are evicted changes how often a value is made again, never what it is.
 */
public final class ClockMap<V> {
    private static final class Entry<V> {
        final V value;
        volatile boolean used;

        Entry(V value) {
            this.value = value;
        }
    }

    private final int capacity;
    private final ConcurrentHashMap<Long, Entry<V>> map = new ConcurrentHashMap<>();
    /** The keys in the order they were added, or put back on their second chance. Guarded by itself. */
    private final ArrayDeque<Long> order = new ArrayDeque<>();

    public ClockMap(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** The value kept for a key, null where none is. */
    public V get(long key) {
        Entry<V> e = map.get(key);
        if (e == null) {
            return null;
        }
        e.used = true;
        return e.value;
    }

    /** The value kept for a key, made (under the map's lock, so it should be quick to make) where none is. */
    public V computeIfAbsent(long key, LongFunction<V> make) {
        Entry<V> e = map.get(key);
        if (e != null) {
            e.used = true;
            return e.value;
        }
        synchronized (order) {
            e = map.get(key);
            if (e != null) {
                e.used = true;
                return e.value;
            }
            e = new Entry<>(make.apply(key));
            map.put(key, e);
            order.addLast(key);
            while (map.size() > capacity && !order.isEmpty()) {
                Long k = order.pollFirst();
                Entry<V> old = map.get(k);
                if (old == null) {
                    continue;
                }
                if (k == key) {
                    order.addLast(k);
                } else if (old.used) {
                    old.used = false;
                    order.addLast(k);
                } else {
                    map.remove(k);
                }
            }
            return e.value;
        }
    }

    /** Removes a key's entry if it still holds this value. */
    public void remove(long key, V value) {
        synchronized (order) {
            Entry<V> e = map.get(key);
            if (e != null && e.value == value) {
                map.remove(key);
                order.remove(key);
            }
        }
    }
}
