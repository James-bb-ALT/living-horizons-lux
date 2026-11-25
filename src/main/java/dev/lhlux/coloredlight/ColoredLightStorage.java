package dev.lhlux.coloredlight;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.SectionPos;

/**
 * Thread-safe per-section storage for RGB colored block light with thread-local last-section caching.
 */
public final class ColoredLightStorage {
    private final ConcurrentHashMap<Long, ColoredLightSection> sections = new ConcurrentHashMap<>();
    private final AtomicInteger structureVersion = new AtomicInteger();

    private final ThreadLocal<Cursor> localCursor = ThreadLocal.withInitial(Cursor::new);

    private static final class Cursor {
        long key = Long.MIN_VALUE;
        ColoredLightSection section;
        int version = -1;
    }

    public int getLight(int wx, int wy, int wz) {
        long key = SectionPos.asLong(wx >> 4, wy >> 4, wz >> 4);
        ColoredLightSection sec = getSectionFast(key);
        if (sec == null) {
            return 0;
        }
        return sec.get(wx & 15, wy & 15, wz & 15);
    }

    public boolean setLight(int wx, int wy, int wz, int rgb4) {
        long key = SectionPos.asLong(wx >> 4, wy >> 4, wz >> 4);
        ColoredLightSection sec = sections.get(key);
        if (sec == null) {
            if (rgb4 == 0) {
                return false;
            }
            sec = sections.computeIfAbsent(key, k -> new ColoredLightSection());
            structureVersion.incrementAndGet();
        }
        return sec.set(wx & 15, wy & 15, wz & 15, rgb4);
    }

    public void removeChunk(int chunkX, int chunkZ, int minSectionY, int maxSectionY) {
        boolean removedAny = false;
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            long key = SectionPos.asLong(chunkX, sy, chunkZ);
            if (sections.remove(key) != null) {
                removedAny = true;
            }
        }
        if (removedAny) {
            structureVersion.incrementAndGet();
        }
    }

    public boolean hasSection(long sectionKey) {
        return sections.containsKey(sectionKey);
    }

    public void clear() {
        sections.clear();
        structureVersion.incrementAndGet();
    }

    private ColoredLightSection getSectionFast(long key) {
        Cursor c = localCursor.get();
        int ver = structureVersion.get();
        if (c.version == ver && c.key == key) {
            return c.section;
        }
        ColoredLightSection sec = sections.get(key);
        c.key = key;
        c.section = sec;
        c.version = ver;
        return sec;
    }
}
