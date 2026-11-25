package dev.lhlux.config;

import dev.lhlux.framegen.FrameGenMode;
import dev.lhlux.post.UpscalerMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class LuxAddonConfigTest {
    @Test
    void roundTripsAndClampsValues(@TempDir Path tempDir) {
        Path cfgFile = tempDir.resolve("livinghorizons_lux.json");
        LuxAddonConfig cfg = new LuxAddonConfig();
        cfg.applyPreset(LuxAddonConfig.Preset.LOW_END_FRAMEGEN);
        cfg.lsrScale = 0.10f; // below minimum 0.50
        cfg.shadowResolution = 99999; // above max 4096
        LuxAddonConfig.setInstanceForTest(cfg);
        LuxAddonConfig.load(cfgFile);
        LuxAddonConfig.get().applyPreset(LuxAddonConfig.Preset.LOW_END_FRAMEGEN);
        LuxAddonConfig.get().lsrScale = 0.10f;
        LuxAddonConfig.get().upscalerMode = UpscalerMode.NIS_FSR1;
        LuxAddonConfig.get().gpuWaterCausticsStrength = 9.0f;
        LuxAddonConfig.get().gpuWaterCausticsEnabled = true;
        LuxAddonConfig.get().gpuComputeLightingEnabled = false;
        LuxAddonConfig.save();

        LuxAddonConfig.load(cfgFile);
        LuxAddonConfig reloaded = LuxAddonConfig.get();
        assertEquals(FrameGenMode.TFR_2X, reloaded.frameGenMode);
        assertEquals(UpscalerMode.NIS_FSR1, reloaded.upscalerMode);
        assertEquals(0.50f, reloaded.lsrScale, 1e-5f);
        assertEquals(1.50f, reloaded.gpuWaterCausticsStrength, 1e-5f);
        assertFalse(reloaded.gpuComputeLightingEnabled);
        assertTrue(reloaded.gpuWaterCausticsEnabled);
        assertTrue(reloaded.csmEnabled);
        assertTrue(reloaded.coloredLightEnabled);
    }
}
