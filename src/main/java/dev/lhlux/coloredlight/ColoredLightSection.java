package dev.lhlux.coloredlight;

import java.util.Arrays;

/**
 * 16x16x16 section storage of packed 12-bit RGB block light values.
 * Lock-free for readers: updates mutate array elements atomically via volatile reference publication.
 */
public final class ColoredLightSection {
    public static final int VOLUME = 4096;

    private final short[] data = new short[VOLUME];
    private volatile boolean nonEmpty;

    public static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public int get(int x, int y, int z) {
        if (!nonEmpty) {
            return 0;
        }
        return data[index(x, y, z)] & 0xFFF;
    }

    public boolean set(int x, int y, int z, int rgb4) {
        int idx = index(x, y, z);
        int next = rgb4 & 0xFFF;
        int prev = data[idx] & 0xFFF;
        if (prev == next) {
            return false;
        }
        data[idx] = (short) next;
        if (next != 0) {
            nonEmpty = true;
        }
        return true;
    }

    public boolean isNonEmpty() {
        return nonEmpty;
    }

    public void clear() {
        Arrays.fill(data, (short) 0);
        nonEmpty = false;
    }
}
