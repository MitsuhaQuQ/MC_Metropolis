package com.mtm.citybuilder.core.noise;

/**
 * Smooth deterministic noise from values hashed at lattice points, blended with a smoothstep. Values lie in [0, 1] and
 * spread symmetrically around 0.5, so a threshold of 0.5 splits the plane into equal shares.
 */
public final class ValueNoise {
    private ValueNoise() {
    }

    /** Noise of one octave with lattice spacing {@code wavelength}. */
    public static double sample(long seed, double x, double z, double wavelength) {
        double u = x / wavelength;
        double v = z / wavelength;
        long i = (long) Math.floor(u);
        long j = (long) Math.floor(v);
        double fu = smooth(u - i);
        double fv = smooth(v - j);
        double a = lattice(seed, i, j) + (lattice(seed, i + 1, j) - lattice(seed, i, j)) * fu;
        double b = lattice(seed, i, j + 1) + (lattice(seed, i + 1, j + 1) - lattice(seed, i, j + 1)) * fu;
        return a + (b - a) * fv;
    }

    /** Two octaves, the second at about a third of the wavelength and weight, for less regular shapes. */
    public static double twoOctaves(long seed, double x, double z, double wavelength) {
        return 0.7 * sample(seed, x, z, wavelength) + 0.3 * sample(Hash.mix(seed), x, z, wavelength / 2.7);
    }

    private static double lattice(long seed, long i, long j) {
        return Hash.unit(Hash.of(seed, i, j));
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
