package dev.lhlux.framegen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.render.GlStateGuard;
import dev.lhlux.render.LuxShaderProgram;
import dev.lhlux.render.PostProcessSwapChain;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Temporal Depth-Reprojection + Optical Interpolation Frame Generation engine.
 * On synthetic frames, skips heavy world/chunk/LivingHorizons world-light passes and warps the previous
 * real frame + Distant Horizons sky/far-plane accurately into the current camera pose using a signed
 * RGBA16F motion buffer, while letting the first-person hand and GUI/HUD render natively at full framerate.
 */
public final class FrameGenSystem {
    private static final FrameGenSystem INSTANCE = new FrameGenSystem();

    private final FrameGenCadenceController cadence = new FrameGenCadenceController();
    private final PostProcessSwapChain swapChain = new PostProcessSwapChain();

    private final LuxShaderProgram prepareShader = new LuxShaderProgram(
            "framegen_prepare", "shaders/core/fullscreen_quad.vsh", "shaders/core/framegen_prepare.fsh"
    );
    private final LuxShaderProgram motionShader = new LuxShaderProgram(
            "framegen_motion", "shaders/core/fullscreen_quad.vsh", "shaders/core/framegen_motion.fsh"
    );
    private final LuxShaderProgram reprojectShader = new LuxShaderProgram(
            "framegen_reproject", "shaders/core/fullscreen_quad.vsh", "shaders/core/framegen_reproject.fsh"
    );
    private final LuxShaderProgram interpolateShader = new LuxShaderProgram(
            "framegen_interpolate", "shaders/core/fullscreen_quad.vsh", "shaders/core/framegen_interpolate.fsh"
    );

    private final Matrix4f prevViewProj = new Matrix4f();
    private final Matrix4f prevInvViewProj = new Matrix4f();
    private final Matrix4f prevSkyInvViewProj = new Matrix4f();
    private final Vector3f prevCamPos = new Vector3f();

    private final Matrix4f prevPrevViewProj = new Matrix4f();
    private final Matrix4f prevPrevInvViewProj = new Matrix4f();
    private final Matrix4f prevPrevSkyInvViewProj = new Matrix4f();
    private final Vector3f prevPrevCamPos = new Vector3f();

    private boolean hasPreviousRealFrame;
    private long realFrameStartNanos;
    private boolean currentFrameSynthetic;

    private FrameGenSystem() {
    }

    public static FrameGenSystem get() {
        return INSTANCE;
    }

    public boolean isCurrentFrameSynthetic() {
        return currentFrameSynthetic;
    }

    public int reportedGeneratedFps() {
        return cadence.reportedGeneratedFps();
    }

    public void onConfigChanged() {
        cadence.reset();
        hasPreviousRealFrame = false;
        currentFrameSynthetic = false;
        if (!LuxAddonConfig.get().frameGenMode.isEnabled()) {
            releaseResources();
        }
    }

    /**
     * Called right before LevelRenderer.renderLevel.
     * Returns true if this frame is SYNTHETIC (and has already populated mainRenderTarget with reprojected world).
     */
    public boolean beginWorldFrame(Minecraft mc) {
        currentFrameSynthetic = false;
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.frameGenMode.isEnabled()) {
            return false;
        }

        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width < 16 || main.height < 16) {
            return false;
        }

        FrameMatrixCache frame = FrameMatrixCache.get();
        if (!frame.isValid()) {
            return false;
        }

        FrameGenCadenceController.Decision decision = cadence.decideNextFrame(
                cfg,
                mc.level,
                mc.screen != null,
                ModCompat.isExternalShaderPackActive(),
                main.width,
                main.height,
                frame.cameraPos(),
                frame.cameraYaw(),
                frame.cameraPitch(),
                frame.viewRotation(),
                System.nanoTime()
        );

        if (decision == FrameGenCadenceController.Decision.REAL_AND_CAPTURE) {
            realFrameStartNanos = System.nanoTime();
            return false;
        }

        boolean generated = false;
        if (decision == FrameGenCadenceController.Decision.SYNTHETIC_INTERPOLATE && hasPreviousRealFrame) {
            generated = renderInterpolatedSyntheticFrame(mc, main, frame);
        } else {
            generated = renderReprojectedSyntheticFrame(mc, main, frame);
        }

        if (generated) {
            currentFrameSynthetic = true;
            return true;
        }
        // Fallback to real render if shaders failed to compile or history wasn't ready
        cadence.reset();
        realFrameStartNanos = System.nanoTime();
        return false;
    }

    /**
     * Captures the freshly rendered real world frame (after Living Horizons WorldLight + Selective Bloom + LSR).
     */
    public void captureRealWorldFrame(Minecraft mc) {
        if (currentFrameSynthetic || !LuxAddonConfig.get().frameGenMode.isEnabled()) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        FrameMatrixCache frame = FrameMatrixCache.get();
        if (main == null || main.width < 16 || main.height < 16 || !frame.isValid()) {
            return;
        }
        if (!prepareShader.ensureCompiled()) {
            return;
        }

        int w = main.width;
        int h = main.height;

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNITS_012)) {
            TextureTarget currHistory = swapChain.getOrCreate("fg_curr", w, h, true, GL11.GL_LINEAR);
            TextureTarget prevHistory = swapChain.getOrCreate("fg_prev", w, h, true, GL11.GL_LINEAR);

            // Rotate curr -> prev for INTERPOLATED_2X
            if (cadence.hasHistory()) {
                PostProcessSwapChain.blitColor(currHistory.frameBufferId, w, h, prevHistory.frameBufferId, w, h, false);
                PostProcessSwapChain.blitDepth(currHistory.frameBufferId, w, h, prevHistory.frameBufferId, w, h);
                prevPrevCamPos.set(prevCamPos);
                prevPrevViewProj.set(prevViewProj);
                prevPrevInvViewProj.set(prevInvViewProj);
                prevPrevSkyInvViewProj.set(prevSkyInvViewProj);
                hasPreviousRealFrame = true;
            }

            // Copy world depth into history depth buffer
            PostProcessSwapChain.blitDepth(main.frameBufferId, w, h, currHistory.frameBufferId, w, h);

            // Snapshot color + edge mask via prepareShader
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            currHistory.bindWrite(true);
            prepareShader.bind();
            prepareShader.bindTexture("SceneSampler", 0, main.getColorTextureId());
            prepareShader.bindTexture("DepthSampler", 1, main.getDepthTextureId());
            prepareShader.setVec2("SourceTexelSize", 1.0f / w, 1.0f / h);
            PostProcessSwapChain.drawFullscreenQuad();

            // Store camera matrices of this captured real frame
            prevCamPos.set(frame.cameraPos());
            prevViewProj.set(frame.viewProjection());
            prevInvViewProj.set(frame.inverseViewProjection());

            Matrix4f rotProj = new Matrix4f(frame.projection()).mul(frame.viewRotation());
            prevSkyInvViewProj.set(rotProj).invert();

            long duration = Math.max(1L, System.nanoTime() - realFrameStartNanos);
            cadence.recordRealFrameCompleted(
                    mc.level, w, h,
                    frame.cameraPos(), frame.cameraYaw(), frame.cameraPitch(),
                    frame.viewRotation(),
                    duration
            );
        } catch (Throwable ignored) {
            cadence.reset();
        }
    }

    private boolean renderReprojectedSyntheticFrame(Minecraft mc, RenderTarget main, FrameMatrixCache frame) {
        if (!motionShader.ensureCompiled() || !reprojectShader.ensureCompiled()) {
            return false;
        }
        int w = main.width;
        int h = main.height;
        TextureTarget currHistory = swapChain.getOrCreate("fg_curr", w, h, true, GL11.GL_LINEAR);
        PostProcessSwapChain.FloatColorTarget motion = swapChain.getOrCreateFloat("fg_motion", w, h);
        if (motion == null) {
            return false;
        }

        // Compute ReprojectionMat = currViewProj * translate(prevCam - currCam) * prevInvViewProj
        Vector3f camDelta = new Vector3f(prevCamPos).sub(frame.cameraPos());
        Matrix4f deltaTrans = new Matrix4f().translation(camDelta.x, camDelta.y, camDelta.z);
        Matrix4f reprojectionMat = new Matrix4f(frame.viewProjection())
                .mul(deltaTrans)
                .mul(prevInvViewProj);

        Matrix4f skyReprojectionMat = new Matrix4f(frame.viewProjection()).mul(prevSkyInvViewProj);

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNITS_012)) {
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            // 1. Signed RGBA16F motion pass
            motion.bindWrite(true);
            motionShader.bind();
            motionShader.bindTexture("HistoryDepth", 0, currHistory.getDepthTextureId());
            motionShader.setMat4("ReprojectionMat", reprojectionMat);
            motionShader.setMat4("SkyReprojectionMat", skyReprojectionMat);
            PostProcessSwapChain.drawFullscreenQuad();

            // 2. Reproject into mainRenderTarget + preserve depth for first-person hand
            PostProcessSwapChain.blitDepth(currHistory.frameBufferId, w, h, main.frameBufferId, w, h);
            main.bindWrite(true);
            reprojectShader.bind();
            reprojectShader.bindTexture("HistoryColor", 0, currHistory.getColorTextureId());
            reprojectShader.bindTexture("MotionSampler", 1, motion.colorTextureId());
            reprojectShader.setVec2("SourceTexelSize", 1.0f / w, 1.0f / h);
            reprojectShader.setFloat("ReprojectionStrength", 1.0f);
            PostProcessSwapChain.drawFullscreenQuad();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean renderInterpolatedSyntheticFrame(Minecraft mc, RenderTarget main, FrameMatrixCache frame) {
        if (!motionShader.ensureCompiled() || !interpolateShader.ensureCompiled()) {
            return renderReprojectedSyntheticFrame(mc, main, frame);
        }
        int w = main.width;
        int h = main.height;
        TextureTarget currHistory = swapChain.getOrCreate("fg_curr", w, h, true, GL11.GL_LINEAR);
        TextureTarget prevHistory = swapChain.getOrCreate("fg_prev", w, h, true, GL11.GL_LINEAR);
        PostProcessSwapChain.FloatColorTarget motion = swapChain.getOrCreateFloat("fg_motion", w, h);
        if (motion == null) {
            return false;
        }

        Vector3f camDelta = new Vector3f(prevCamPos).sub(frame.cameraPos());
        Matrix4f deltaTrans = new Matrix4f().translation(camDelta.x, camDelta.y, camDelta.z);
        Matrix4f reprojectionMat = new Matrix4f(frame.viewProjection()).mul(deltaTrans).mul(prevInvViewProj);
        Matrix4f skyReprojectionMat = new Matrix4f(frame.viewProjection()).mul(prevSkyInvViewProj);

        // Maps CurrHistory clip+depth directly into PrevHistory UV
        Vector3f prevDelta = new Vector3f(prevCamPos).sub(prevPrevCamPos);
        Matrix4f prevDeltaTrans = new Matrix4f().translation(prevDelta.x, prevDelta.y, prevDelta.z);
        Matrix4f prevToPrevPrevMat = new Matrix4f(prevPrevViewProj).mul(prevDeltaTrans).mul(prevInvViewProj);
        Matrix4f prevToPrevPrevSkyMat = new Matrix4f(prevPrevViewProj).mul(prevSkyInvViewProj);

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNITS_0123)) {
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            motion.bindWrite(true);
            motionShader.bind();
            motionShader.bindTexture("HistoryDepth", 0, currHistory.getDepthTextureId());
            motionShader.setMat4("ReprojectionMat", reprojectionMat);
            motionShader.setMat4("SkyReprojectionMat", skyReprojectionMat);
            PostProcessSwapChain.drawFullscreenQuad();

            PostProcessSwapChain.blitDepth(currHistory.frameBufferId, w, h, main.frameBufferId, w, h);
            main.bindWrite(true);
            interpolateShader.bind();
            interpolateShader.bindTexture("PrevColor", 0, prevHistory.getColorTextureId());
            interpolateShader.bindTexture("CurrColor", 1, currHistory.getColorTextureId());
            interpolateShader.bindTexture("MotionSampler", 2, motion.colorTextureId());
            interpolateShader.bindTexture("HistoryDepth", 3, currHistory.getDepthTextureId());
            interpolateShader.setMat4("PrevToPrevPrevMat", prevToPrevPrevMat);
            interpolateShader.setMat4("PrevToPrevPrevSkyMat", prevToPrevPrevSkyMat);
            interpolateShader.setVec2("SourceTexelSize", 1.0f / w, 1.0f / h);
            interpolateShader.setFloat("ReprojectionStrength", 1.0f);
            interpolateShader.setFloat("BlendAlpha", 0.18f);
            PostProcessSwapChain.drawFullscreenQuad();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void releaseResources() {
        cadence.reset();
        hasPreviousRealFrame = false;
        currentFrameSynthetic = false;
        swapChain.destroyAll();
        prepareShader.destroy();
        motionShader.destroy();
        reprojectShader.destroy();
        interpolateShader.destroy();
    }
}
