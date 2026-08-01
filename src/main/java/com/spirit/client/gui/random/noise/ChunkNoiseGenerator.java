package com.spirit.client.gui.random.noise;

public class ChunkNoiseGenerator {

    private final long seed;

    public ChunkNoiseGenerator(long seed) {
        this.seed = seed;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static int floorMod(int x, int mod) {
        int r = x % mod;
        return r < 0 ? r + mod : r;
    }

    public double sample(double x, double y) {
        return sample(x, y, 4, 0.5);
    }

    public double sample(double x, double y, int octaves, double persistence) {
        double total = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double max = 0.0;

        for (int i = 0; i < octaves; i++) {
            total += tileableValueNoise(
                x * frequency,
                y * frequency,
                256,
                256
            ) * amplitude;

            max += amplitude;

            amplitude *= persistence;
            frequency *= 2.0;
        }
        return total / max;
    }

    private double tileableValueNoise(double x, double y, int periodX, int periodY) {
        int x0 = floorMod((int) Math.floor(x), periodX);
        int y0 = floorMod((int) Math.floor(y), periodY);

        int x1 = (x0 + 1) % periodX;
        int y1 = (y0 + 1) % periodY;

        double sx = fade(x - Math.floor(x));
        double sy = fade(y - Math.floor(y));

        double n00 = random(x0, y0);
        double n10 = random(x1, y0);
        double n01 = random(x0, y1);
        double n11 = random(x1, y1);

        double ix0 = lerp(n00, n10, sx);
        double ix1 = lerp(n01, n11, sx);

        return lerp(ix0, ix1, sy);
    }

    private double random(int x, int y) {
        long h = seed;

        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;

        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);

        return (h >>> 11) * (1.0 / (1L << 53));
    }
}
