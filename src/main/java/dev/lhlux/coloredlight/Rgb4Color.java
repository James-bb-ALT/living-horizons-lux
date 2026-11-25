package dev.lhlux.coloredlight;

/**
 * Compact 12-bit (4-4-4) RGB block-light value in range [0..15] per channel.
 */
public final class Rgb4Color {
    public static final int ZERO = 0;
    public static final int WHITE = pack(15, 15, 15);

    private Rgb4Color() {
    }

    public static int pack(int r, int g, int b) {
        int cr = clamp4(r);
        int cg = clamp4(g);
        int cb = clamp4(b);
        return (cr << 8) | (cg << 4) | cb;
    }

    public static int red(int packed) {
        return (packed >>> 8) & 0xF;
    }

    public static int green(int packed) {
        return (packed >>> 4) & 0xF;
    }

    public static int blue(int packed) {
        return packed & 0xF;
    }

    public static int maxComponent(int packed) {
        return Math.max(red(packed), Math.max(green(packed), blue(packed)));
    }

    public static int componentMax(int a, int b) {
        int r = Math.max(red(a), red(b));
        int g = Math.max(green(a), green(b));
        int bl = Math.max(blue(a), blue(b));
        return (r << 8) | (g << 4) | bl;
    }

    /**
     * Attenuates each RGB channel by 1 step (or filter attenuation) while preserving color ratios cleanly.
     */
    public static int attenuate(int packed, int rFilter, int gFilter, int bFilter) {
        int r = applyFilter(red(packed), rFilter);
        int g = applyFilter(green(packed), gFilter);
        int b = applyFilter(blue(packed), bFilter);
        return (r << 8) | (g << 4) | b;
    }

    private static int applyFilter(int level, int filter4) {
        if (level <= 1 || filter4 <= 0) {
            return 0;
        }
        int stepped = level - 1;
        if (filter4 >= 15) {
            return stepped;
        }
        return (stepped * filter4 + 7) / 15;
    }

    /**
     * Converts a 4-4-4 RGB color to normalized perceptual tint multipliers normalized around max channel
     * so colored light preserves brightness while tinting hue.
     */
    public static void toNormalizedTint(int packed, float[] outRgb) {
        int r = red(packed);
        int g = green(packed);
        int b = blue(packed);
        int peak = Math.max(r, Math.max(g, b));
        if (peak <= 0) {
            outRgb[0] = 1.0f;
            outRgb[1] = 1.0f;
            outRgb[2] = 1.0f;
            return;
        }
        float invPeak = 1.0f / (float) peak;
        outRgb[0] = r * invPeak;
        outRgb[1] = g * invPeak;
        outRgb[2] = b * invPeak;
    }

    private static int clamp4(int v) {
        return v < 0 ? 0 : Math.min(v, 15);
    }
}
