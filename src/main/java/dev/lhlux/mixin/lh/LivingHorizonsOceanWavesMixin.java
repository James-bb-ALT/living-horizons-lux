package dev.lhlux.mixin.lh;

import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Living Horizons 1.14.2 flat OceanWaves crest particles from clashing with Wavify 3D waves.
 */
@Pseudo
@Mixin(targets = "dev.thekryzzz.livinghorizons.client.ripple.OceanWaves", remap = false)
public abstract class LivingHorizonsOceanWavesMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lhlux$yieldOceanWavesToWavify(Minecraft mc, CallbackInfo ci) {
        if (LuxAddonConfig.get().wavifyCompatEnabled && ModCompat.isWavifyLoaded()) {
            ci.cancel();
        }
    }
}
