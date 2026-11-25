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
 * Prevents Living Horizons 1.14.2 shoreline wave particles from overlapping Wavify's shore splashes/waves.
 */
@Pseudo
@Mixin(targets = "dev.thekryzzz.livinghorizons.client.ripple.Shoreline", remap = false)
public abstract class LivingHorizonsShorelineMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lhlux$yieldShorelineToWavify(Minecraft mc, CallbackInfo ci) {
        if (LuxAddonConfig.get().wavifyCompatEnabled && ModCompat.isWavifyLoaded()) {
            ci.cancel();
        }
    }
}
