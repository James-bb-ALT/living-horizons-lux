package dev.lhlux.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.lhlux.framegen.FrameGenSystem;
import dev.lhlux.post.SpatialResolutionScaler;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.shadows.CascadedShadowEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures exact unjittered world-render camera/projection matrices, skips world rendering cleanly on FrameGen
 * synthetic frames, prepares CSM shadows, starts LSR / TAAU (applying sub-pixel Halton jitter to world render),
 * and finishes upscaling cleanly before Living Horizons post-processing.
 */
@Mixin(value = LevelRenderer.class, priority = 500)
public abstract class LevelRendererLuxMixin {
    @Unique
    private final Matrix4f lhlux$savedProjection = new Matrix4f();
    @Unique
    private boolean lhlux$appliedProjectionJitter;

    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void lhlux$beginWorldRender(
            PoseStack poseStack,
            float partialTick,
            long finishNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        lhlux$appliedProjectionJitter = false;
        Minecraft mc = Minecraft.getInstance();
        Matrix4f viewMatrix = new Matrix4f(poseStack.last().pose());
        FrameMatrixCache.get().beginFrame(mc, camera, partialTick, projectionMatrix, viewMatrix);

        if (FrameGenSystem.get().beginWorldFrame(mc)) {
            ci.cancel();
            return;
        }

        CascadedShadowEngine.get().prepareShadows(mc, camera, partialTick);
        if (SpatialResolutionScaler.get().beginWorldRender(mc)) {
            lhlux$savedProjection.set(projectionMatrix);
            if (SpatialResolutionScaler.get().applyProjectionJitter(projectionMatrix)) {
                lhlux$appliedProjectionJitter = true;
                RenderSystem.setProjectionMatrix(projectionMatrix, RenderSystem.getVertexSorting());
            }
        }
    }

    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN")
    )
    private void lhlux$finishLsrAfterLevelRender(
            PoseStack poseStack,
            float partialTick,
            long finishNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        if (lhlux$appliedProjectionJitter) {
            lhlux$appliedProjectionJitter = false;
            projectionMatrix.set(lhlux$savedProjection);
            RenderSystem.setProjectionMatrix(lhlux$savedProjection, RenderSystem.getVertexSorting());
        }
        if (!FrameGenSystem.get().isCurrentFrameSynthetic() && SpatialResolutionScaler.get().isWorldPassActive()) {
            SpatialResolutionScaler.get().finishWorldRender(Minecraft.getInstance());
        }
    }

    @ModifyVariable(
            method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1,
            require = 0
    )
    private boolean lhlux$spectatorDuringShadowCapture(boolean isSpectator) {
        return CascadedShadowEngine.get().isShadowPassActive() || isSpectator;
    }
}
