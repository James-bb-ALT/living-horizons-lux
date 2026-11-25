package dev.lhlux.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lhlux.coloredlight.GpuDynamicColoredLightPass;
import dev.lhlux.framegen.FrameGenSystem;
import dev.lhlux.post.GpuWaterCausticsPass;
import dev.lhlux.post.SelectiveBloomPass;
import dev.lhlux.post.SpatialResolutionScaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs post-world passes (SelectiveBloomPass, LSR upscale, FrameGen capture) right before first-person
 * hand rendering (with a RETURN fallback when HUD/hand are hidden), without redirecting LevelRenderer.renderLevel
 * so TimescaleLib, KnightLib, Diorama, and Living Horizons coexist cleanly.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class GameRendererLuxMixin {
    @Unique
    private boolean lhlux$postWorldDoneThisFrame;

    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"))
    private void lhlux$resetFrameFlag(float partialTick, long finishNano, PoseStack poseStack, CallbackInfo ci) {
        lhlux$postWorldDoneThisFrame = false;
    }

    @Inject(
            method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void lhlux$postWorldBeforeHand(float partialTick, long finishNano, PoseStack poseStack, CallbackInfo ci) {
        lhlux$finishWorldPostPasses(partialTick);
    }

    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"))
    private void lhlux$postWorldFallbackAtReturn(float partialTick, long finishNano, PoseStack poseStack, CallbackInfo ci) {
        lhlux$finishWorldPostPasses(partialTick);
    }

    @Unique
    private void lhlux$finishWorldPostPasses(float partialTick) {
        if (lhlux$postWorldDoneThisFrame) {
            return;
        }
        lhlux$postWorldDoneThisFrame = true;

        Minecraft mc = Minecraft.getInstance();
        if (FrameGenSystem.get().isCurrentFrameSynthetic()) {
            return;
        }
        try {
            GpuWaterCausticsPass.get().apply(mc);
            GpuDynamicColoredLightPass.get().apply(mc, partialTick);
            SelectiveBloomPass.get().apply(mc);
            if (SpatialResolutionScaler.get().isWorldPassActive()) {
                SpatialResolutionScaler.get().finishWorldRender(mc);
            }
            FrameGenSystem.get().captureRealWorldFrame(mc);
        } finally {
            if (SpatialResolutionScaler.get().isWorldPassActive()) {
                SpatialResolutionScaler.get().restoreNativeTarget(mc);
            }
        }
    }
}
