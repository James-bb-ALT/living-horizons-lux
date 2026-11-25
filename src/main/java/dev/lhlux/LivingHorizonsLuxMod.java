package dev.lhlux;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.coloredlight.ColoredLightEngine;
import dev.lhlux.coloredlight.GpuDynamicColoredLightPass;
import dev.lhlux.coloredlight.SpriteColorExtractor;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.framegen.FrameGenSystem;
import dev.lhlux.gui.LuxAddonConfigScreen;
import dev.lhlux.gui.LuxDebugHud;
import dev.lhlux.post.GpuWaterCausticsPass;
import dev.lhlux.post.SelectiveBloomPass;
import dev.lhlux.post.SpatialResolutionScaler;
import dev.lhlux.shadows.CascadedShadowEngine;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Living Horizons: Lux — Clean-room graphics, shadow, colored lighting, and FrameGen addon for Living Horizons.
 */
@Mod(LivingHorizonsLuxMod.MOD_ID)
public final class LivingHorizonsLuxMod {
    public static final String MOD_ID = "livinghorizons_lux";
    public static final Logger LOGGER = LoggerFactory.getLogger("LivingHorizonsLux");

    public static KeyMapping openConfigKey;

    public LivingHorizonsLuxMod() {
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true)
        );

        LuxAddonConfig.load(FMLPaths.CONFIGDIR.get().resolve("livinghorizons_lux.json"));

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onRegisterReloadListeners);

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new LuxAddonConfigScreen(parent))
        );

        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(this::onDebugOverlayText);
        MinecraftForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Living Horizons: Lux initialized (client-side addon)");
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        openConfigKey = new KeyMapping(
                "key.livinghorizons_lux.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "key.categories.livinghorizons_lux"
        );
        event.register(openConfigKey);
    }

    private void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> SpriteColorExtractor.clearCache());
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (openConfigKey != null) {
            while (openConfigKey.consumeClick()) {
                mc.setScreen(new LuxAddonConfigScreen(mc.screen));
            }
        }
        if (mc.level != null) {
            ColoredLightEngine.get().tickClient(mc.level);
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            releaseRenderResources();
        }
    }

    private void onDebugOverlayText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!LuxAddonConfig.get().showF3Debug) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.renderDebug) {
            return;
        }
        String line = LuxDebugHud.buildLine();
        if (!line.isEmpty()) {
            event.getLeft().add(line);
        }
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen == null) {
            return;
        }
        if ("dev.thekryzzz.livinghorizons.client.gui.LivingHorizonsConfigScreen".equals(screen.getClass().getName())) {
            int btnWidth = 88;
            int btnX = Math.max(4, screen.width - btnWidth - 6);
            event.addListener(Button.builder(
                    Component.translatable("screen.livinghorizons_lux.open_from_lh"),
                    btn -> Minecraft.getInstance().setScreen(new LuxAddonConfigScreen(screen))
            ).bounds(btnX, 4, btnWidth, 16).build());
        }
    }

    public static void onConfigChanged() {
        CascadedShadowEngine.get().onConfigChanged();
        ColoredLightEngine.get().onConfigChanged();
        GpuDynamicColoredLightPass.get().onConfigChanged();
        GpuWaterCausticsPass.get().onConfigChanged();
        SelectiveBloomPass.get().onConfigChanged();
        SpatialResolutionScaler.get().onConfigChanged();
        FrameGenSystem.get().onConfigChanged();
    }

    public static void releaseRenderResources() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(LivingHorizonsLuxMod::releaseRenderResources);
            return;
        }
        CascadedShadowEngine.get().releaseResources();
        ColoredLightEngine.get().reset();
        GpuDynamicColoredLightPass.get().releaseResources();
        GpuWaterCausticsPass.get().releaseResources();
        SelectiveBloomPass.get().releaseResources();
        SpatialResolutionScaler.get().releaseResources();
        FrameGenSystem.get().releaseResources();
    }
}
