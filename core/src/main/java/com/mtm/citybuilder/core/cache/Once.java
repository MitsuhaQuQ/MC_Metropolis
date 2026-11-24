package com.mtm.citybuilder.core.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Work done once per key however many threads ask for it at the same time: the first works it out, the others wait
 * for its result. The chunk workers ask for a town's plots, functions and public buildings all at once when a world
 * starts in a city; each used to work them out again (a large city's take a minute or more each), the spawn area
 * waiting minutes on a dozen copies.
 * <p>
 * Only while it is being worked out: what is kept afterwards is the caller's own cache.
 */
public final class Once<V> {
    private final Map<Long, CompletableFuture<V>> running = new HashMap<>();

    /** The value for a key: worked out here, or waited for where another thread is already working it out. */
    public V get(long key, Supplier<V> work) {
        CompletableFuture<V> future;
        boolean mine = false;
        synchronized (running) {
            future = running.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                running.put(key, future);
                mine = true;
            }
        }
        if (mine) {
            try {
                future.complete(work.get());
            } catch (RuntimeException | Error e) {
                future.completeExceptionally(e);
                throw e;
            } finally {
                synchronized (running) {
                    running.remove(key, future);
                }
            }
            return future.join();
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }
}
