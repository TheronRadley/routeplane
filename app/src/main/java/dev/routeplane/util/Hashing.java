package dev.routeplane.util;

import java.nio.charset.StandardCharsets;

/** Small, stable, non-cryptographic 64-bit hashing used for deterministic routing and sampling. */
public final class Hashing {
    private Hashing() {
    }

    /** SplitMix64 finaliser: a bijective mixing function with good avalanche behaviour. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** FNV-1a over the UTF-8 bytes followed by a SplitMix64 finaliser. Stable across JVM runs and platforms. */
    public static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return mix64(h);
    }

    /** Combines two hashes order-dependently. */
    public static long combine(long a, long b) {
        return mix64(a * 31 + b + 0x9e3779b97f4a7c15L);
    }

    /** Maps a 64-bit hash to a uniform double in the open interval (0, 1). */
    public static double toUnitInterval(long hash) {
        return ((hash >>> 11) + 0.5) / (double) (1L << 53);
    }
}
