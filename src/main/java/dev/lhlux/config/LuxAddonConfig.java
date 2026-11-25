package dev.lhlux.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.framegen.FrameGenMode;
import dev.lhlux.post.UpscalerMode;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for Living Horizons: Lux.
 */
public final class LuxAddonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static LuxAddonConfig instance = new LuxAddonConfig();
    private static Path configPath;

    public enum Preset {
        VANILLA_PLUS,
        BALANCED,
        CINEMATIC,
        LOW_END_FRAMEGEN
    }

    // Shadows (Cascaded Shadow Maps - Experimental)
    public boolean csmEnabled = false;
    public int shadowResolution = 1536;
    public float nearCascadeDistance = 48.0f;
    public float farCascadeDistance = 144.0f;
    public int farCascadeUpdateInterval = 4;
    public int pcfFilterMode = 1; // 0 = 1-tap hard, 1 = 2x2 Gather PCF, 2 = 4-tap Soft PCF
    public float shadowStrength = 0.72f;
    public boolean entityShadowsEnabled = true;
    public int maxEntityShadowCasters = 16;
    public boolean suppressLivingHorizonsVoxelShadows = true;

    // Colored Lighting (Per-Block RGB & GPU Deferred Dynamic Lights)
    public boolean coloredLightEnabled = true;
    public boolean gpuDynamicColoredLights = true;
    public boolean gpuComputeLightingEnabled = true;
    public boolean handheldColoredLight = true;
    public boolean entityItemColoredLights = true;
    public float coloredLightIntensity = 1.0f;
    public float coloredLightRadiusScale = 1.0f;
    public float coloredLightSaturation = 0.85f;
    public float coloredLightDaytimeBoost = 0.35f;
    public boolean stainedGlassFiltering = true;
    public boolean autoModdedEmitterColors = true;
    public int coloredLightUpdatesPerTick = 64;
    public boolean balanceLivingHorizonsWarmth = true;

    // Water & Waves Compatibility (Living Horizons + Wavify + GPU Caustics)
    public boolean waterSunGlitterEnabled = false;
    public boolean waterMoonGlitterEnabled = false;
    public boolean waterCausticsEnabled = true;
    public boolean gpuWaterCausticsEnabled = false;
    public float gpuWaterCausticsStrength = 0.65f;
    public boolean wavifyCompatEnabled = true;

    // Shine & Post-Processing
    public boolean bloomEnabled = true;
    public float bloomStrength = 0.48f;
    public float bloomThreshold = 0.62f;
    public float bloomRadius = 1.0f;
    public float bloomMaxDistance = 160.0f;
    public boolean rimLightingEnabled = false;
    public float rimLightingStrength = 0.20f;
    public boolean rgssAntialiasingEnabled = false;

    // Performance, LSR / TAAU Upscaling & FrameGen
    public boolean lsrEnabled = false;
    public UpscalerMode upscalerMode = UpscalerMode.TEMPORAL_TAAU;
    public float lsrScale = 0.80f;
    public float lsrSharpness = 0.55f;
    public FrameGenMode frameGenMode = FrameGenMode.OFF;
    public int frameGenTargetFps = 120;
    public float frameGenMaxCameraSnapDegrees = 10.0f;
    public float frameGenMaxTeleportBlocks = 1.6f;
    public boolean dhDepthCompat = true;
    public boolean showF3Debug = true;

    public static LuxAddonConfig get() {
        return instance;
    }

    public static void setInstanceForTest(LuxAddonConfig config) {
        instance = config.sanitize();
    }

    public static void load(Path path) {
        configPath = path;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                LuxAddonConfig loaded = GSON.fromJson(reader, LuxAddonConfig.class);
                if (loaded != null) {
                    instance = loaded.sanitize();
                    return;
                }
            } catch (Exception e) {
                LivingHorizonsLuxMod.LOGGER.warn("Failed to read config {}, using defaults", path, e);
            }
        }
        instance = new LuxAddonConfig().sanitize();
        save();
    }

    public static void save() {
        if (configPath == null) {
            return;
        }
        instance.sanitize();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            LivingHorizonsLuxMod.LOGGER.warn("Failed to save config {}", configPath, e);
        }
    }

    public void resetDefaults() {
        LuxAddonConfig defaults = new LuxAddonConfig();
        copyFrom(defaults);
        sanitize();
    }

    public void applyPreset(Preset preset) {
        switch (preset) {
            case VANILLA_PLUS -> {
                csmEnabled = true;
                shadowResolution = 1024;
                nearCascadeDistance = 40.0f;
                farCascadeDistance = 112.0f;
                farCascadeUpdateInterval = 4;
                pcfFilterMode = 1;
                shadowStrength = 0.62f;
                entityShadowsEnabled = true;
                coloredLightEnabled = true;
                coloredLightSaturation = 0.72f;
                bloomEnabled = true;
                bloomStrength = 0.22f;
                bloomThreshold = 0.84f;
                rimLightingEnabled = false;
                rgssAntialiasingEnabled = false;
                lsrEnabled = false;
                frameGenMode = FrameGenMode.OFF;
            }
            case BALANCED -> {
                resetDefaults();
            }
            case CINEMATIC -> {
                csmEnabled = true;
                shadowResolution = 2048;
                nearCascadeDistance = 56.0f;
                farCascadeDistance = 176.0f;
                farCascadeUpdateInterval = 2;
                pcfFilterMode = 2;
                shadowStrength = 0.78f;
                entityShadowsEnabled = true;
                coloredLightEnabled = true;
                coloredLightSaturation = 0.92f;
                bloomEnabled = true;
                bloomStrength = 0.48f;
                bloomThreshold = 0.72f;
                rimLightingEnabled = true;
                rimLightingStrength = 0.24f;
                rgssAntialiasingEnabled = true;
                lsrEnabled = false;
                frameGenMode = FrameGenMode.OFF;
            }
            case LOW_END_FRAMEGEN -> {
                csmEnabled = true;
                shadowResolution = 1024;
                nearCascadeDistance = 36.0f;
                farCascadeDistance = 96.0f;
                farCascadeUpdateInterval = 4;
                pcfFilterMode = 0;
                shadowStrength = 0.68f;
                entityShadowsEnabled = false;
                coloredLightEnabled = true;
                coloredLightSaturation = 0.85f;
                bloomEnabled = true;
                bloomStrength = 0.28f;
                bloomThreshold = 0.80f;
                rimLightingEnabled = false;
                rgssAntialiasingEnabled = false;
                lsrEnabled = true;
                upscalerMode = UpscalerMode.TEMPORAL_TAAU;
                lsrScale = 0.75f;
                lsrSharpness = 0.65f;
                frameGenMode = FrameGenMode.TFR_2X;
                frameGenTargetFps = 120;
            }
        }
        sanitize();
    }

    private void copyFrom(LuxAddonConfig other) {
        this.csmEnabled = other.csmEnabled;
        this.shadowResolution = other.shadowResolution;
        this.nearCascadeDistance = other.nearCascadeDistance;
        this.farCascadeDistance = other.farCascadeDistance;
        this.farCascadeUpdateInterval = other.farCascadeUpdateInterval;
        this.pcfFilterMode = other.pcfFilterMode;
        this.shadowStrength = other.shadowStrength;
        this.entityShadowsEnabled = other.entityShadowsEnabled;
        this.maxEntityShadowCasters = other.maxEntityShadowCasters;
        this.suppressLivingHorizonsVoxelShadows = other.suppressLivingHorizonsVoxelShadows;

        this.coloredLightEnabled = other.coloredLightEnabled;
        this.gpuDynamicColoredLights = other.gpuDynamicColoredLights;
        this.gpuComputeLightingEnabled = other.gpuComputeLightingEnabled;
        this.handheldColoredLight = other.handheldColoredLight;
        this.entityItemColoredLights = other.entityItemColoredLights;
        this.coloredLightIntensity = other.coloredLightIntensity;
        this.coloredLightRadiusScale = other.coloredLightRadiusScale;
        this.coloredLightSaturation = other.coloredLightSaturation;
        this.coloredLightDaytimeBoost = other.coloredLightDaytimeBoost;
        this.stainedGlassFiltering = other.stainedGlassFiltering;
        this.autoModdedEmitterColors = other.autoModdedEmitterColors;
        this.coloredLightUpdatesPerTick = other.coloredLightUpdatesPerTick;
        this.balanceLivingHorizonsWarmth = other.balanceLivingHorizonsWarmth;

        this.waterSunGlitterEnabled = other.waterSunGlitterEnabled;
        this.waterMoonGlitterEnabled = other.waterMoonGlitterEnabled;
        this.waterCausticsEnabled = other.waterCausticsEnabled;
        this.gpuWaterCausticsEnabled = other.gpuWaterCausticsEnabled;
        this.gpuWaterCausticsStrength = other.gpuWaterCausticsStrength;
        this.wavifyCompatEnabled = other.wavifyCompatEnabled;

        this.bloomEnabled = other.bloomEnabled;
        this.bloomStrength = other.bloomStrength;
        this.bloomThreshold = other.bloomThreshold;
        this.bloomRadius = other.bloomRadius;
        this.bloomMaxDistance = other.bloomMaxDistance;
        this.rimLightingEnabled = other.rimLightingEnabled;
        this.rimLightingStrength = other.rimLightingStrength;
        this.rgssAntialiasingEnabled = other.rgssAntialiasingEnabled;

        this.lsrEnabled = other.lsrEnabled;
        this.upscalerMode = other.upscalerMode;
        this.lsrScale = other.lsrScale;
        this.lsrSharpness = other.lsrSharpness;
        this.frameGenMode = other.frameGenMode;
        this.frameGenTargetFps = other.frameGenTargetFps;
        this.frameGenMaxCameraSnapDegrees = other.frameGenMaxCameraSnapDegrees;
        this.frameGenMaxTeleportBlocks = other.frameGenMaxTeleportBlocks;
        this.dhDepthCompat = other.dhDepthCompat;
        this.showF3Debug = other.showF3Debug;
    }

    public LuxAddonConfig sanitize() {
        shadowResolution = clampInt(shadowResolution, 512, 4096);
        nearCascadeDistance = clampFloat(nearCascadeDistance, 16.0f, 128.0f);
        farCascadeDistance = clampFloat(farCascadeDistance, Math.max(nearCascadeDistance + 16.0f, 48.0f), 320.0f);
        farCascadeUpdateInterval = clampInt(farCascadeUpdateInterval, 1, 16);
        pcfFilterMode = clampInt(pcfFilterMode, 0, 2);
        shadowStrength = clampFloat(shadowStrength, 0.0f, 1.0f);
        maxEntityShadowCasters = clampInt(maxEntityShadowCasters, 0, 64);

        coloredLightIntensity = clampFloat(coloredLightIntensity, 0.1f, 2.0f);
        coloredLightRadiusScale = clampFloat(coloredLightRadiusScale, 0.5f, 2.0f);
        coloredLightSaturation = clampFloat(coloredLightSaturation, 0.0f, 1.5f);
        coloredLightDaytimeBoost = clampFloat(coloredLightDaytimeBoost, 0.0f, 1.0f);
        coloredLightUpdatesPerTick = clampInt(coloredLightUpdatesPerTick, 8, 512);

        bloomStrength = clampFloat(bloomStrength, 0.0f, 2.0f);
        bloomThreshold = clampFloat(bloomThreshold, 0.10f, 1.50f);
        bloomRadius = clampFloat(bloomRadius, 0.25f, 2.50f);
        bloomMaxDistance = clampFloat(bloomMaxDistance, 32.0f, 512.0f);
        rimLightingStrength = clampFloat(rimLightingStrength, 0.0f, 1.0f);

        gpuWaterCausticsStrength = clampFloat(gpuWaterCausticsStrength, 0.0f, 1.5f);
        if (upscalerMode == null) {
            upscalerMode = UpscalerMode.TEMPORAL_TAAU;
        }
        lsrScale = clampFloat(lsrScale, 0.50f, 1.00f);
        lsrSharpness = clampFloat(lsrSharpness, 0.0f, 1.0f);
        if (frameGenMode == null) {
            frameGenMode = FrameGenMode.OFF;
        }
        frameGenTargetFps = clampInt(frameGenTargetFps, 30, 500);
        frameGenMaxCameraSnapDegrees = clampFloat(frameGenMaxCameraSnapDegrees, 5.0f, 90.0f);
        frameGenMaxTeleportBlocks = clampFloat(frameGenMaxTeleportBlocks, 1.0f, 32.0f);
        return this;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampFloat(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }
}
