package dev.lhlux.gui;

import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Organized tabbed settings screen for Living Horizons: Lux (accessible from F8 or Forge Mods Config button).
 */
public final class LuxAddonConfigScreen extends Screen {
    private enum Tab {
        COLORED_LIGHT("screen.livinghorizons_lux.tab.colored_light"),
        VISUALS("screen.livinghorizons_lux.tab.visuals"),
        PERFORMANCE("screen.livinghorizons_lux.tab.performance"),
        COMPAT("screen.livinghorizons_lux.tab.compat"),
        EXPERIMENTAL("screen.livinghorizons_lux.tab.experimental"),
        PRESETS("screen.livinghorizons_lux.tab.presets");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private record SectionLabel(int x, int y, Component text, int color, boolean centered) {
    }

    private final Screen parent;
    private Tab currentTab = Tab.COLORED_LIGHT;
    private final List<SectionLabel> sectionLabels = new ArrayList<>();

    public LuxAddonConfigScreen(Screen parent) {
        super(Component.translatable("screen.livinghorizons_lux.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        sectionLabels.clear();

        Tab[] tabs = Tab.values();
        int tabWidth = Math.min(96, Math.max(50, (width - 24) / tabs.length - 4));
        int totalTabW = tabs.length * tabWidth + (tabs.length - 1) * 4;
        int startX = (width - totalTabW) / 2;

        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            Button btn = Button.builder(Component.translatable(tab.key), b -> {
                currentTab = tab;
                init();
            }).bounds(startX + i * (tabWidth + 4), 26, tabWidth, 20).build();
            btn.active = (tab != currentTab);
            addRenderableWidget(btn);
        }

        buildTabContent();

        int bottomY = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("screen.livinghorizons_lux.save"), b -> {
            LuxAddonConfig.save();
            LivingHorizonsLuxMod.onConfigChanged();
        }).bounds(width / 2 - 155, bottomY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.livinghorizons_lux.reset"), b -> {
            LuxAddonConfig.get().resetDefaults();
            LuxAddonConfig.save();
            LivingHorizonsLuxMod.onConfigChanged();
            init();
        }).bounds(width / 2 - 50, bottomY, 100, 20).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 55, bottomY, 100, 20).build());
    }

    private void buildTabContent() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        int colW = Math.min(310, width - 32);
        int x = (width - colW) / 2;
        int y = 58;
        int row = 22;
        boolean twoCol = width >= 400;
        int halfW = Math.min(192, (width - 36) / 2);
        int leftX = width / 2 - halfW - 4;
        int rightX = width / 2 + 4;

        switch (currentTab) {
            case COLORED_LIGHT -> {
                if (twoCol) {
                    addSection(leftX, y, Component.translatable("screen.livinghorizons_lux.section.cl_engine"), 0xFFE0A8, false);
                    addSection(rightX, y, Component.translatable("screen.livinghorizons_lux.section.cl_tuning"), 0xFFE0A8, false);
                    int cy = y + 13;
                    addToggle(leftX, cy, halfW, "option.livinghorizons_lux.coloredLightEnabled", cfg.coloredLightEnabled, v -> cfg.coloredLightEnabled = v);
                    addSlider(rightX, cy, halfW, "option.livinghorizons_lux.coloredLightIntensity", 0.2f, 2.0f, cfg.coloredLightIntensity,
                            v -> cfg.coloredLightIntensity = v, v -> Math.round(v * 100) + "%");
                    cy += row;
                    addToggle(leftX, cy, halfW, "option.livinghorizons_lux.gpuDynamicColoredLights", cfg.gpuDynamicColoredLights, v -> cfg.gpuDynamicColoredLights = v);
                    addSlider(rightX, cy, halfW, "option.livinghorizons_lux.coloredLightRadiusScale", 0.5f, 2.0f, cfg.coloredLightRadiusScale,
                            v -> cfg.coloredLightRadiusScale = v, v -> String.format("%.2fx", v));
                    cy += row;
                    addToggle(leftX, cy, halfW, "option.livinghorizons_lux.gpuComputeLightingEnabled", cfg.gpuComputeLightingEnabled, v -> cfg.gpuComputeLightingEnabled = v);
                    addSlider(rightX, cy, halfW, "option.livinghorizons_lux.coloredLightSaturation", 0.0f, 1.5f, cfg.coloredLightSaturation,
                            v -> cfg.coloredLightSaturation = v, v -> Math.round(v * 100) + "%");
                    cy += row;
                    addToggle(leftX, cy, halfW, "option.livinghorizons_lux.handheldColoredLight", cfg.handheldColoredLight, v -> cfg.handheldColoredLight = v);
                    addSlider(rightX, cy, halfW, "option.livinghorizons_lux.coloredLightDaytimeBoost", 0.0f, 1.0f, cfg.coloredLightDaytimeBoost,
                            v -> cfg.coloredLightDaytimeBoost = v, v -> Math.round(v * 100) + "%");
                    cy += row;
                    addToggle(leftX, cy, halfW, "option.livinghorizons_lux.entityItemColoredLights", cfg.entityItemColoredLights, v -> cfg.entityItemColoredLights = v);
                    addToggle(rightX, cy, halfW, "option.livinghorizons_lux.stainedGlassFiltering", cfg.stainedGlassFiltering, v -> cfg.stainedGlassFiltering = v);
                    cy += row;
                    addToggle(width / 2 - halfW / 2, cy, halfW, "option.livinghorizons_lux.autoModdedEmitterColors", cfg.autoModdedEmitterColors, v -> cfg.autoModdedEmitterColors = v);
                } else {
                    addToggle(x, y, colW, "option.livinghorizons_lux.coloredLightEnabled", cfg.coloredLightEnabled, v -> cfg.coloredLightEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.gpuDynamicColoredLights", cfg.gpuDynamicColoredLights, v -> cfg.gpuDynamicColoredLights = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.gpuComputeLightingEnabled", cfg.gpuComputeLightingEnabled, v -> cfg.gpuComputeLightingEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.handheldColoredLight", cfg.handheldColoredLight, v -> cfg.handheldColoredLight = v);
                    y += row;
                    addSlider(x, y, colW, "option.livinghorizons_lux.coloredLightIntensity", 0.2f, 2.0f, cfg.coloredLightIntensity,
                            v -> cfg.coloredLightIntensity = v, v -> Math.round(v * 100) + "%");
                    y += row;
                    addSlider(x, y, colW, "option.livinghorizons_lux.coloredLightSaturation", 0.0f, 1.5f, cfg.coloredLightSaturation,
                            v -> cfg.coloredLightSaturation = v, v -> Math.round(v * 100) + "%");
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.stainedGlassFiltering", cfg.stainedGlassFiltering, v -> cfg.stainedGlassFiltering = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.autoModdedEmitterColors", cfg.autoModdedEmitterColors, v -> cfg.autoModdedEmitterColors = v);
                }
            }
            case VISUALS -> {
                if (twoCol) {
                    addSection(leftX, y, Component.translatable("screen.livinghorizons_lux.section.water_lh"), 0x8CD8FF, false);
                    addSection(rightX, y, Component.translatable("screen.livinghorizons_lux.section.shine_post"), 0xFFE0A8, false);
                    int ly = y + 13;
                    int ry = y + 13;
                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.waterSunGlitterEnabled", cfg.waterSunGlitterEnabled, v -> cfg.waterSunGlitterEnabled = v);
                    ly += row;
                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.waterMoonGlitterEnabled", cfg.waterMoonGlitterEnabled, v -> cfg.waterMoonGlitterEnabled = v);
                    ly += row;
                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.waterCausticsEnabled", cfg.waterCausticsEnabled, v -> cfg.waterCausticsEnabled = v);
                    ly += row + 6;
                    addSection(leftX, ly, Component.translatable("screen.livinghorizons_lux.section.aa"), 0xD0D8FF, false);
                    ly += 13;
                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.rgssAntialiasingEnabled",
                            cfg.rgssAntialiasingEnabled, v -> cfg.rgssAntialiasingEnabled = v);

                    addToggle(rightX, ry, halfW, "option.livinghorizons_lux.bloomEnabled", cfg.bloomEnabled, v -> cfg.bloomEnabled = v);
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.bloomStrength", 0.0f, 1.4f, cfg.bloomStrength,
                            v -> cfg.bloomStrength = v, v -> Math.round(v * 100) + "%");
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.bloomThreshold", 0.2f, 1.2f, cfg.bloomThreshold,
                            v -> cfg.bloomThreshold = v, v -> String.format("%.2f", v));
                    ry += row;
                    addToggle(rightX, ry, halfW, "option.livinghorizons_lux.rimLightingEnabled", cfg.rimLightingEnabled, v -> cfg.rimLightingEnabled = v);
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.rimLightingStrength", 0.0f, 0.8f, cfg.rimLightingStrength,
                            v -> cfg.rimLightingStrength = v, v -> Math.round(v * 100) + "%");
                } else {
                    addToggle(x, y, colW, "option.livinghorizons_lux.waterSunGlitterEnabled", cfg.waterSunGlitterEnabled, v -> cfg.waterSunGlitterEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.waterMoonGlitterEnabled", cfg.waterMoonGlitterEnabled, v -> cfg.waterMoonGlitterEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.waterCausticsEnabled", cfg.waterCausticsEnabled, v -> cfg.waterCausticsEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.bloomEnabled", cfg.bloomEnabled, v -> cfg.bloomEnabled = v);
                    y += row;
                    addSlider(x, y, colW, "option.livinghorizons_lux.bloomStrength", 0.0f, 1.4f, cfg.bloomStrength,
                            v -> cfg.bloomStrength = v, v -> Math.round(v * 100) + "%");
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.rimLightingEnabled", cfg.rimLightingEnabled, v -> cfg.rimLightingEnabled = v);
                    y += row;
                    addToggle(x, y, colW, "option.livinghorizons_lux.rgssAntialiasingEnabled",
                            cfg.rgssAntialiasingEnabled, v -> cfg.rgssAntialiasingEnabled = v);
                }
            }
            case PERFORMANCE -> {
                addSection(width / 2, y, Component.translatable("screen.livinghorizons_lux.section.upscaling"), 0xA8F0C8, true);
                int py = y + 13;
                if (twoCol) {
                    addToggle(leftX, py, halfW, "option.livinghorizons_lux.lsrEnabled", cfg.lsrEnabled, v -> cfg.lsrEnabled = v);
                    addRenderableWidget(Button.builder(
                            Component.translatable("option.livinghorizons_lux.upscalerMode")
                                    .append(": " + cfg.upscalerMode.label()),
                            b -> {
                                cfg.upscalerMode = cfg.upscalerMode.next();
                                LivingHorizonsLuxMod.onConfigChanged();
                                init();
                            }
                    ).bounds(rightX, py, halfW, 20).build());
                    py += row;
                    addSlider(leftX, py, halfW, "option.livinghorizons_lux.lsrScale", 0.50f, 1.00f, cfg.lsrScale,
                            v -> cfg.lsrScale = v, v -> Math.round(v * 100) + "%");
                    addSlider(rightX, py, halfW, "option.livinghorizons_lux.lsrSharpness", 0.0f, 1.0f, cfg.lsrSharpness,
                            v -> cfg.lsrSharpness = v, v -> Math.round(v * 100) + "%");
                    py += row + 6;
                    addToggle(width / 2 - halfW / 2, py, halfW, "option.livinghorizons_lux.showF3Debug", cfg.showF3Debug, v -> cfg.showF3Debug = v);
                } else {
                    addToggle(x, py, colW, "option.livinghorizons_lux.lsrEnabled", cfg.lsrEnabled, v -> cfg.lsrEnabled = v);
                    py += row;
                    addRenderableWidget(Button.builder(
                            Component.translatable("option.livinghorizons_lux.upscalerMode")
                                    .append(": " + cfg.upscalerMode.label()),
                            b -> {
                                cfg.upscalerMode = cfg.upscalerMode.next();
                                LivingHorizonsLuxMod.onConfigChanged();
                                init();
                            }
                    ).bounds(x, py, colW, 20).build());
                    py += row;
                    addSlider(x, py, colW, "option.livinghorizons_lux.lsrScale", 0.50f, 1.00f, cfg.lsrScale,
                            v -> cfg.lsrScale = v, v -> Math.round(v * 100) + "%");
                    py += row;
                    addSlider(x, py, colW, "option.livinghorizons_lux.lsrSharpness", 0.0f, 1.0f, cfg.lsrSharpness,
                            v -> cfg.lsrSharpness = v, v -> Math.round(v * 100) + "%");
                    py += row;
                    addToggle(x, py, colW, "option.livinghorizons_lux.showF3Debug", cfg.showF3Debug, v -> cfg.showF3Debug = v);
                }
            }
            case COMPAT -> {
                String detected = String.format("LH:%s  Embeddium:%s  Wavify:%s  DH:%s",
                        ModCompat.isLivingHorizonsLoaded() ? "ON" : "--",
                        ModCompat.isEmbeddiumLoaded() ? "ON" : "--",
                        ModCompat.isWavifyLoaded() ? "ON" : "--",
                        ModCompat.isDistantHorizonsLoaded() ? "ON" : "--");
                addSection(width / 2, y, Component.literal(detected), 0x98E0FF, true);
                int cy = y + 14;
                addToggle(x, cy, colW, "option.livinghorizons_lux.wavifyCompatEnabled", cfg.wavifyCompatEnabled, v -> cfg.wavifyCompatEnabled = v);
                cy += row;
                addToggle(x, cy, colW, "option.livinghorizons_lux.dhDepthCompat", cfg.dhDepthCompat, v -> cfg.dhDepthCompat = v);
                cy += row;
                addSlider(x, cy, colW, "option.livinghorizons_lux.bloomMaxDistance", 48f, 384f, cfg.bloomMaxDistance,
                        v -> cfg.bloomMaxDistance = Math.round(v), v -> Math.round(v) + "m");
                cy += row;
                addToggle(x, cy, colW, "option.livinghorizons_lux.balanceLivingHorizonsWarmth",
                        cfg.balanceLivingHorizonsWarmth, v -> cfg.balanceLivingHorizonsWarmth = v);
                cy += row;
                addToggle(x, cy, colW, "option.livinghorizons_lux.suppressLivingHorizonsVoxelShadows",
                        cfg.suppressLivingHorizonsVoxelShadows, v -> cfg.suppressLivingHorizonsVoxelShadows = v);
            }
            case EXPERIMENTAL -> {
                addSection(width / 2, y - 4, Component.translatable("screen.livinghorizons_lux.experimental_warning"), 0xFFAA44, true);
                int ey = y + 10;
                if (twoCol) {
                    addSection(leftX, ey, Component.translatable("screen.livinghorizons_lux.section.exp_csm"), 0xFFD280, false);
                    addSection(rightX, ey, Component.translatable("screen.livinghorizons_lux.section.exp_gpu_fg"), 0xFFD280, false);
                    int ly = ey + 12;
                    int ry = ey + 12;

                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.csmEnabled", cfg.csmEnabled, v -> cfg.csmEnabled = v);
                    ly += row;
                    addCycleInt(leftX, ly, halfW, "option.livinghorizons_lux.shadowResolution",
                            new int[]{1024, 1536, 2048}, cfg.shadowResolution, v -> cfg.shadowResolution = v);
                    ly += row;
                    addSlider(leftX, ly, halfW, "option.livinghorizons_lux.nearCascadeDistance", 24f, 96f, cfg.nearCascadeDistance,
                            v -> cfg.nearCascadeDistance = Math.round(v), v -> Math.round(v) + "m");
                    ly += row;
                    addSlider(leftX, ly, halfW, "option.livinghorizons_lux.farCascadeDistance", 64f, 256f, cfg.farCascadeDistance,
                            v -> cfg.farCascadeDistance = Math.round(v), v -> Math.round(v) + "m");
                    ly += row;
                    addCycleInt(leftX, ly, halfW, "option.livinghorizons_lux.pcfFilterMode",
                            new int[]{0, 1, 2}, cfg.pcfFilterMode, v -> cfg.pcfFilterMode = v);
                    ly += row;
                    addSlider(leftX, ly, halfW, "option.livinghorizons_lux.shadowStrength", 0.1f, 1.0f, cfg.shadowStrength,
                            v -> cfg.shadowStrength = v, v -> Math.round(v * 100) + "%");
                    ly += row;
                    addToggle(leftX, ly, halfW, "option.livinghorizons_lux.entityShadowsEnabled", cfg.entityShadowsEnabled, v -> cfg.entityShadowsEnabled = v);

                    addToggle(rightX, ry, halfW, "option.livinghorizons_lux.gpuWaterCausticsEnabled", cfg.gpuWaterCausticsEnabled, v -> cfg.gpuWaterCausticsEnabled = v);
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.gpuWaterCausticsStrength", 0.0f, 1.5f, cfg.gpuWaterCausticsStrength,
                            v -> cfg.gpuWaterCausticsStrength = v, v -> Math.round(v * 100) + "%");
                    ry += row + 6;
                    addRenderableWidget(Button.builder(
                            Component.translatable("option.livinghorizons_lux.frameGenMode")
                                    .append(": " + cfg.frameGenMode.label()),
                            b -> {
                                cfg.frameGenMode = cfg.frameGenMode.next();
                                LivingHorizonsLuxMod.onConfigChanged();
                                init();
                            }
                    ).bounds(rightX, ry, halfW, 20).build());
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.frameGenTargetFps", 30f, 240f, cfg.frameGenTargetFps,
                            v -> cfg.frameGenTargetFps = Math.round(v), v -> Math.round(v) + " FPS");
                    ry += row;
                    addSlider(rightX, ry, halfW, "option.livinghorizons_lux.frameGenMaxCameraSnapDegrees", 5f, 45f, cfg.frameGenMaxCameraSnapDegrees,
                            v -> cfg.frameGenMaxCameraSnapDegrees = Math.round(v), v -> Math.round(v) + " deg");
                } else {
                    addToggle(x, ey, colW, "option.livinghorizons_lux.csmEnabled", cfg.csmEnabled, v -> cfg.csmEnabled = v);
                    ey += row;
                    addCycleInt(x, ey, colW, "option.livinghorizons_lux.shadowResolution",
                            new int[]{1024, 1536, 2048}, cfg.shadowResolution, v -> cfg.shadowResolution = v);
                    ey += row;
                    addSlider(x, ey, colW, "option.livinghorizons_lux.shadowStrength", 0.1f, 1.0f, cfg.shadowStrength,
                            v -> cfg.shadowStrength = v, v -> Math.round(v * 100) + "%");
                    ey += row;
                    addToggle(x, ey, colW, "option.livinghorizons_lux.gpuWaterCausticsEnabled", cfg.gpuWaterCausticsEnabled, v -> cfg.gpuWaterCausticsEnabled = v);
                    ey += row;
                    addSlider(x, ey, colW, "option.livinghorizons_lux.gpuWaterCausticsStrength", 0.0f, 1.5f, cfg.gpuWaterCausticsStrength,
                            v -> cfg.gpuWaterCausticsStrength = v, v -> Math.round(v * 100) + "%");
                    ey += row;
                    addRenderableWidget(Button.builder(
                            Component.translatable("option.livinghorizons_lux.frameGenMode")
                                    .append(": " + cfg.frameGenMode.label()),
                            b -> {
                                cfg.frameGenMode = cfg.frameGenMode.next();
                                LivingHorizonsLuxMod.onConfigChanged();
                                init();
                            }
                    ).bounds(x, ey, colW, 20).build());
                }
            }
            case PRESETS -> {
                addSection(width / 2, y, Component.translatable("screen.livinghorizons_lux.section.presets"), 0xE0E0E0, true);
                int py = y + 14;
                for (LuxAddonConfig.Preset preset : LuxAddonConfig.Preset.values()) {
                    addRenderableWidget(Button.builder(
                            Component.translatable("preset.livinghorizons_lux." + preset.name().toLowerCase()),
                            b -> {
                                cfg.applyPreset(preset);
                                LuxAddonConfig.save();
                                LivingHorizonsLuxMod.onConfigChanged();
                                init();
                            }
                    ).bounds(x, py, colW, 20).build());
                    py += row + 2;
                }
            }
        }
    }

    private void addSection(int x, int y, Component text, int color, boolean centered) {
        sectionLabels.add(new SectionLabel(x, y, text, color, centered));
    }

    private void addToggle(int x, int y, int w, String key, boolean current, java.util.function.Consumer<Boolean> setter) {
        addRenderableWidget(Button.builder(
                Component.translatable(key).append(": " + (current ? "ON" : "OFF")),
                b -> {
                    setter.accept(!current);
                    LivingHorizonsLuxMod.onConfigChanged();
                    init();
                }
        ).bounds(x, y, w, 20).build());
    }

    private void addCycleInt(int x, int y, int w, String key, int[] options, int current, java.util.function.IntConsumer setter) {
        addRenderableWidget(Button.builder(
                Component.translatable(key).append(": " + current),
                b -> {
                    int idx = 0;
                    for (int i = 0; i < options.length; i++) {
                        if (options[i] == current) {
                            idx = (i + 1) % options.length;
                            break;
                        }
                    }
                    setter.accept(options[idx]);
                    LivingHorizonsLuxMod.onConfigChanged();
                    init();
                }
        ).bounds(x, y, w, 20).build());
    }

    private void addSlider(
            int x, int y, int w, String key,
            float min, float max, float current,
            java.util.function.Consumer<Float> setter,
            java.util.function.Function<Float, String> formatter
    ) {
        double norm = (current - min) / Math.max(1e-5f, max - min);
        norm = Math.max(0.0, Math.min(1.0, norm));
        addRenderableWidget(new AbstractSliderButton(x, y, w, 20, Component.empty(), norm) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                float val = min + (float) this.value * (max - min);
                setMessage(Component.translatable(key).append(": " + formatter.apply(val)));
            }

            @Override
            protected void applyValue() {
                float val = min + (float) this.value * (max - min);
                setter.accept(val);
            }
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        for (SectionLabel label : sectionLabels) {
            if (label.centered) {
                graphics.drawCenteredString(font, label.text, label.x, label.y, label.color);
            } else {
                graphics.drawString(font, label.text, label.x, label.y, label.color, true);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        LuxAddonConfig.save();
        LivingHorizonsLuxMod.onConfigChanged();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
