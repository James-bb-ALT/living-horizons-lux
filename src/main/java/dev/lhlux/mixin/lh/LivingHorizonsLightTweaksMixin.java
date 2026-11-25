package dev.lhlux.mixin.lh;

import dev.lhlux.coloredlight.ColoredLightEngine;
import dev.lhlux.config.LuxAddonConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gently tones down Living Horizons' uniform orange block-light tint when per-block RGB colored lighting
 * is enabled so blue soul fire, redstone, and green froglights keep their true hues.
 */
@Pseudo
@Mixin(targets = "dev.thekryzzz.livinghorizons.client.LightTweaks", remap = false)
public abstract class LivingHorizonsLightTweaksMixin {
    @Redirect(
            method = "lightmapPixel",
            at = @At(
                    value = "FIELD",
                    target = "Ldev/thekryzzz/livinghorizons/config/LivingHorizonsConfig;lightWarmth:D"
            ),
            require = 0
    )
    private static double lhlux$balanceWarmthForColoredLight(dev.thekryzzz.livinghorizons.config.LivingHorizonsConfig instance) {
        double original = instance.lightWarmth;
        if (LuxAddonConfig.get().coloredLightEnabled
                && LuxAddonConfig.get().balanceLivingHorizonsWarmth
                && ColoredLightEngine.get().isActive()) {
            return original * 0.20;
        }
        return original;
    }
}
