package dev.lhlux.mixin.lh;

import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.shadows.CascadedShadowEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Living Horizons' CPU voxel shadow baking when our GPU Cascaded Shadow Map engine is active,
 * unless water caustics or water glitter still rely on ShadowVolume's water column row.
 */
@Pseudo
@Mixin(targets = "dev.thekryzzz.livinghorizons.client.grade.ShadowVolume", remap = false)
public abstract class LivingHorizonsShadowVolumeMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private static void lhlux$skipCpuVoxelShadowUpdate(ClientLevel level, Vec3 camPos, CallbackInfo ci) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.csmEnabled || !cfg.suppressLivingHorizonsVoxelShadows || !CascadedShadowEngine.get().isActive()) {
            return;
        }
        boolean needsWaterMap = (cfg.waterCausticsEnabled && !cfg.gpuWaterCausticsEnabled)
                || cfg.waterSunGlitterEnabled
                || cfg.waterMoonGlitterEnabled;
        if (!needsWaterMap) {
            ci.cancel();
        }
    }
}
