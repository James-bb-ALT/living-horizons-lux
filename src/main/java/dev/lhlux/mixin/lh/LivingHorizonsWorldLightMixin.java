package dev.lhlux.mixin.lh;

import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.framegen.FrameGenSystem;
import dev.lhlux.shadows.CascadedShadowEngine;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cooperates with Living Horizons' WorldLight (1.12.0 & 1.14.2):
 * 1) Skips WorldLight.apply on FrameGen synthetic frames (already reprojected).
 * 2) Zeroes voxel sun-shadow strength (handling both texel 23 on LH 1.12.0 and texel 24 on LH 1.14.2).
 * 3) Exposes toggles for water caustics (texel 9 alpha), sun glitter dots (texel 20 a/g),
 *    moon glitter (texel 23 green on LH 1.14.2), and Wavify 3D wave compatibility.
 */
@Pseudo
@Mixin(targets = "dev.thekryzzz.livinghorizons.client.grade.WorldLight", remap = false)
public abstract class LivingHorizonsWorldLightMixin {
    @Shadow
    private static int[] texels;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private static void lhlux$skipOnSyntheticFrame(Minecraft mc, float partialTick, CallbackInfo ci) {
        if (FrameGenSystem.get().isCurrentFrameSynthetic()) {
            ci.cancel();
        }
    }

    @Inject(method = "upload", at = @At("HEAD"))
    private static void lhlux$patchWorldLightTexels(Minecraft mc, CallbackInfo ci) {
        if (texels == null || texels.length < 24) {
            return;
        }
        LuxAddonConfig cfg = LuxAddonConfig.get();
        boolean isLh114 = texels.length >= 3993;

        // 1. Replace Living Horizons voxel sun shadows when GPU CSM is active
        if (cfg.csmEnabled
                && cfg.suppressLivingHorizonsVoxelShadows
                && CascadedShadowEngine.get().isActive()) {
            // Texel 18 packs sun.z in r/b (0x00FF00FF) and sunShadowStrength in a/g (0xFF00FF00)
            texels[18] &= 0x00FF00FF;
            if (isLh114) {
                // LH 1.14.2 shifted entity shadow caster count from texel 23 to texel 24
                texels[24] = 0;
            } else {
                texels[23] = 0;
            }
        }

        // 2. Water caustics toggle (retire LH CPU-backed caustics when disabled or replaced by 100% GPU caustics)
        if (!cfg.waterCausticsEnabled || cfg.gpuWaterCausticsEnabled) {
            texels[9] &= 0x00FFFFFF;
        }

        // 3. Water sun glitter (white dots) explicit user toggle
        if (!cfg.waterSunGlitterEnabled) {
            // Texel 20 packs camera.z in r/b (0x00FF00FF) and sunGlitterStrength in a/g (0xFF00FF00)
            texels[20] &= 0x00FF00FF;
        }

        // 4. Water moon glitter explicit user toggle on LH 1.14.2 (texel 23 green channel; red keeps snow glitter)
        if (isLh114 && !cfg.waterMoonGlitterEnabled) {
            texels[23] &= 0xFFFF00FF;
        }
    }
}
