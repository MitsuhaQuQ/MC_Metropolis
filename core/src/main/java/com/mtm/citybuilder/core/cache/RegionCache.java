package com.mtm.citybuilder.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Least recently used cache of results per logic region. Each region is computed once even when several threads ask
 * for it at the same time; the computation runs outside the lock, so threads asking for other regions do not wait.
 * Results must be pure functions of the region coordinates, which makes evicting and computing again safe.
 */
public final class RegionCache<T> {
    /** Computes the result of one region. */
    @FunctionalInterface
    public interface Compute<T> {
        T compute(int regionX, int regionZ);
    }

    private final Compute<T> compute;
    private final LinkedHashMap<Long, CompletableFuture<T>> entries;

    public RegionCache(int capacity, Compute<T> compute) {
        this.compute = compute;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<T>> eldest) {
                return size() > capacity;
            }
        };
    }

    public T get(int regionX, int regionZ) {
        long key = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
        CompletableFuture<T> future;
        boolean owner = false;
        synchronized (entries) {
            future = entries.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                entries.put(key, future);
                owner = true;
            }
        }
        if (owner) {
            try {
                future.complete(compute.compute(regionX, regionZ));
            } catch (Throwable throwable) {
                // Leave no failed entry behind: the next caller tries again
                synchronized (entries) {
                    entries.remove(key, future);
                }
                future.completeExceptionally(throwable);
            }
        }
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for region " + regionX + ", " + regionZ, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
