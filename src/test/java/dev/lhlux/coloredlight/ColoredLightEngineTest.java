package dev.lhlux.coloredlight;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColoredLightEngineTest {
    @Test
    void rgb4PackAndNormalizedTintWork() {
        int blueSoul = Rgb4Color.pack(4, 13, 15);
        assertEquals(4, Rgb4Color.red(blueSoul));
        assertEquals(13, Rgb4Color.green(blueSoul));
        assertEquals(15, Rgb4Color.blue(blueSoul));

        float[] tint = new float[3];
        Rgb4Color.toNormalizedTint(blueSoul, tint);
        assertEquals(4.0f / 15.0f, tint[0], 1e-4f);
        assertEquals(13.0f / 15.0f, tint[1], 1e-4f);
        assertEquals(1.0f, tint[2], 1e-4f);
    }

    @Test
    void bfsPropagationRespectsOcclusionAndStainedGlassFilters() {
        ColoredLightStorage storage = new ColoredLightStorage();
        ColoredLightPropagator propagator = new ColoredLightPropagator();

        // Seed warm orange emitter at (0, 64, 0)
        int warm = Rgb4Color.pack(15, 11, 5);
        storage.setLight(0, 64, 0, warm);
        propagator.enqueueIncrease(0, 64, 0, warm);

        ColoredLightPropagator.BlockQuery query = new ColoredLightPropagator.BlockQuery() {
            @Override
            public boolean isOpaque(int x, int y, int z) {
                // Wall blocking negative X
                return x == -1 && y == 64 && z == 0;
            }

            @Override
            public int getEmissionRgb4(int x, int y, int z) {
                return (x == 0 && y == 64 && z == 0) ? warm : 0;
            }

            @Override
            public int getFilterRgb4(int x, int y, int z) {
                // Blue stained glass at (1, 64, 0) filters out red & green!
                if (x == 1 && y == 64 && z == 0) {
                    return Rgb4Color.pack(2, 4, 15);
                }
                return Rgb4Color.pack(15, 15, 15);
            }

            @Override public int minY() { return 0; }
            @Override public int maxY() { return 256; }
        };

        int[] dirtyCount = {0};
        propagator.propagate(storage, query, 2000, (sx, sy, sz) -> dirtyCount[0]++);

        assertTrue(dirtyCount[0] > 0);
        // Opaque block at (-1,64,0) stays unlit
        assertEquals(0, storage.getLight(-1, 64, 0));
        // Filtered path through blue glass at (1,64,0) heavily reduces red vs blue
        int atGlass = storage.getLight(1, 64, 0);
        assertTrue(Rgb4Color.blue(atGlass) > Rgb4Color.red(atGlass));
        // Clear air at (0,65,0) keeps warm orange tint (red > blue)
        int above = storage.getLight(0, 65, 0);
        assertTrue(Rgb4Color.red(above) > Rgb4Color.blue(above));
    }

    @Test
    void bfsDecreasePropagationClearsRemovedEmitterThroughPrimitiveQueue() {
        ColoredLightStorage storage = new ColoredLightStorage();
        ColoredLightPropagator propagator = new ColoredLightPropagator();

        boolean[] emitterPresent = {true};
        int cyan = Rgb4Color.pack(4, 13, 15);
        storage.setLight(0, 64, 0, cyan);
        propagator.enqueueIncrease(0, 64, 0, cyan);

        ColoredLightPropagator.BlockQuery query = new ColoredLightPropagator.BlockQuery() {
            @Override public boolean isOpaque(int x, int y, int z) { return false; }
            @Override public int getEmissionRgb4(int x, int y, int z) {
                return (emitterPresent[0] && x == 0 && y == 64 && z == 0) ? cyan : 0;
            }
            @Override public int getFilterRgb4(int x, int y, int z) { return Rgb4Color.pack(15, 15, 15); }
            @Override public int minY() { return 0; }
            @Override public int maxY() { return 256; }
        };

        propagator.propagate(storage, query, 5000, (sx, sy, sz) -> {});
        assertTrue(storage.getLight(2, 64, 0) != 0);

        emitterPresent[0] = false;
        storage.setLight(0, 64, 0, 0);
        propagator.enqueueDecrease(0, 64, 0, cyan);
        propagator.propagate(storage, query, 10000, (sx, sy, sz) -> {});

        assertEquals(0, storage.getLight(0, 64, 0));
        assertEquals(0, storage.getLight(2, 64, 0));
    }

    @Test
    void extractsGreenFlameIgnoringBrownTorchStickAndMarksBelowSectionDirty() {
        // Synthetic 16x16 torch texture: top 6 rows bright green flame (ABGR = 0xFF22FF22), bottom 10 rows brown stick (0xFF204878)
        int[] abgr = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (x >= 7 && x <= 8 && y >= 2 && y <= 5) {
                    abgr[y * 16 + x] = 0xFF22FF22; // B=0x22, G=0xFF, R=0x22
                } else if (x >= 7 && x <= 8 && y >= 6 && y <= 15) {
                    abgr[y * 16 + x] = 0xFF204878; // B=0x20, G=0x48, R=0x78 (brown)
                } else {
                    abgr[y * 16 + x] = 0x00000000;
                }
            }
        }
        int greenRgb4 = SpriteColorExtractor.extractFromAbgrPixels(abgr, 16, 16, true, 14);
        assertTrue(Rgb4Color.green(greenRgb4) >= 12);
        assertTrue(Rgb4Color.green(greenRgb4) > Rgb4Color.red(greenRgb4) * 2);

        // Placing/propagating at y=64 (bottom of section sy=4) must also mark section sy=3 (sand floor below) dirty
        LongOpenHashSet dirty = new LongOpenHashSet();
        ColoredLightPropagator.markSectionAndBorders(
                (sx, sy, sz) -> dirty.add(SectionPos.asLong(sx, sy, sz)),
                8, 64, 8
        );
        assertTrue(dirty.contains(SectionPos.asLong(0, 4, 0)));
        assertTrue(dirty.contains(SectionPos.asLong(0, 3, 0)));
    }

    @Test
    void extractsGreenCenterCoreForWaxedCopperLanternIgnoringOrangeCopperFrame() {
        // 16x16 lantern texture: orange copper border (R=0xC8,G=0x60,B=0x20 -> ABGR=0xFF2060C8), green center core (0xFF38FF38)
        int[] abgr = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean inCore = x >= 5 && x <= 10 && y >= 5 && y <= 11;
                abgr[y * 16 + x] = inCore ? 0xFF38FF38 : 0xFF2060C8;
            }
        }
        int lanternRgb4 = SpriteColorExtractor.extractFromAbgrPixels(
                abgr, 16, 16, SpriteColorExtractor.detectRegionMode("waxed_copper_lantern"), 15
        );
        assertTrue(Rgb4Color.green(lanternRgb4) >= 13);
        assertTrue(Rgb4Color.green(lanternRgb4) > Rgb4Color.red(lanternRgb4) * 2);
    }
}
