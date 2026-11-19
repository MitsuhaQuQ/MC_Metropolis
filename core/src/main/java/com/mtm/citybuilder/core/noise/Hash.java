package com.mtm.citybuilder.core.noise;

/**
 * Deterministic hashing of a seed and integer coordinates, the source of every random choice in the city logic: the
 * same inputs give the same value on every machine and in every order.
 */
public final class Hash {
    private Hash() {
    }

    /** SplitMix64 finalizer: a well mixed 64 bit value of {@code value}. */
    public static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long of(long seed, long a) {
        return mix(seed ^ mix(a));
    }

    public static long of(long seed, long a, long b) {
        return mix(of(seed, a) ^ mix(b * 0x632BE59BD9B4E019L));
    }

    public static long of(long seed, long a, long b, long c) {
        return mix(of(seed, a, b) ^ mix(c * 0x85157AF5L));
    }

    /** A value in [0, 1) from a hash. */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }
}
