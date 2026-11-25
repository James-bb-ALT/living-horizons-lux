package dev.lhlux.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.compat.DistantHorizonsCompat;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.mixin.client.MinecraftRenderTargetAccessor;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.render.GlStateGuard;
import dev.lhlux.render.LuxShaderProgram;
import dev.lhlux.render.PostProcessSwapChain;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Spatial & Temporal Resolution Scaler supporting:
 * 1) TEMPORAL_TAAU (FSR2-style Halton sub-pixel jitter + YCoCg variance clipping + Catmull-Rom history),
 * 2) NIS_FSR1 (directional edge-adaptive upscaling + RCAS sharpening), and
 * 3) LSR_CAS (lightweight contrast-adaptive spatial sharpening).
 */
public final class SpatialResolutionScaler {
    private static final SpatialResolutionScaler INSTANCE = new SpatialResolutionScaler();
    private static final int JITTER_CYCLE = 8;

    private final PostProcessSwapChain historySwapChain = new PostProcessSwapChain();
    private final LuxShaderProgram lsrShader = new LuxShaderProgram(
            "lsr_upscale", "shaders/core/fullscreen_quad.vsh", "shaders/core/lsr_upscale.fsh"
    );
    private final LuxShaderProgram nisShader = new LuxShaderProgram(
            "nis_upscale", "shaders/core/fullscreen_quad.vsh", "shaders/core/nis_upscale.fsh"
    );
    private final LuxShaderProgram taauShader = new LuxShaderProgram(
            "taau_resolve", "shaders/core/fullscreen_quad.vsh", "shaders/core/taau_resolve.fsh"
    );

    private final Matrix4f prevUnjitteredViewProj = new Matrix4f();
    private final Vector3f prevCamPos = new Vector3f();
    private Object prevLevelIdentity;
    private int prevDestWidth = -1;
    private int prevDestHeight = -1;
    private boolean hasTemporalHistory;
    private boolean taauToggle;
    private int jitterFrameIndex;
    private float currentJitterNdcX;
    private float currentJitterNdcY;
    private float currentJitterUvX;
    private float currentJitterUvY;

    private TextureTarget scaledTarget;
    private RenderTarget savedNativeTarget;
    private boolean worldPassActive;
    private boolean guardedByDh;

    private SpatialResolutionScaler() {
    }

    public static SpatialResolutionScaler get() {
        return INSTANCE;
    }

    public boolean isWorldPassActive() {
        return worldPassActive;
    }

    public boolean isGuardedByDh() {
        return guardedByDh;
    }

    public void onConfigChanged() {
        hasTemporalHistory = false;
        if (!LuxAddonConfig.get().lsrEnabled && !worldPassActive && scaledTarget != null) {
            scaledTarget.destroyBuffers();
            scaledTarget = null;
            historySwapChain.destroyAll();
        }
    }

    /**
     * Low-discrepancy Halton sequence in [0, 1).
     */
    public static float halton(int index, int base) {
        float result = 0.0f;
        float f = 1.0f / (float) base;
        int i = Math.max(1, index);
        while (i > 0) {
            result += f * (i % base);
            i /= base;
            f /= (float) base;
        }
        return result;
    }

    /**
     * Computes centered sub-pixel jitter in NDC and UV units for the current scaled target size.
     */
    public static void computeJitterOffset(int frameIndex, int lowWidth, int lowHeight, float[] outNdcXY, float[] outUvXY) {
        int cycleIdx = Math.floorMod(frameIndex, JITTER_CYCLE) + 1;
        float jxPx = halton(cycleIdx, 2) - 0.5f;
        float jyPx = halton(cycleIdx, 3) - 0.5f;
        float uvX = jxPx / Math.max(1, lowWidth);
        float uvY = jyPx / Math.max(1, lowHeight);
        outUvXY[0] = uvX;
        outUvXY[1] = uvY;
        outNdcXY[0] = uvX * 2.0f;
        outNdcXY[1] = uvY * 2.0f;
    }

    public boolean beginWorldRender(Minecraft mc) {
        worldPassActive = false;
        savedNativeTarget = null;
        guardedByDh = false;
        currentJitterNdcX = 0.0f;
        currentJitterNdcY = 0.0f;
        currentJitterUvX = 0.0f;
        currentJitterUvY = 0.0f;

        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.lsrEnabled || cfg.lsrScale >= 0.999f || mc.level == null
                || Minecraft.useShaderTransparency()
                || ModCompat.isExternalShaderPackActive()) {
            hasTemporalHistory = false;
            return false;
        }

        // Distant Horizons renders LOD terrain to full window size; scaling mainRenderTarget clips DH output
        if (cfg.dhDepthCompat && DistantHorizonsCompat.isDistantHorizonsRenderingActive()) {
            guardedByDh = true;
            hasTemporalHistory = false;
            return false;
        }

        RenderTarget nativeTarget = mc.getMainRenderTarget();
        if (nativeTarget == null || nativeTarget.width < 16 || nativeTarget.height < 16) {
            return false;
        }

        int scaledW = Math.max(64, Math.round(nativeTarget.width * cfg.lsrScale));
        int scaledH = Math.max(64, Math.round(nativeTarget.height * cfg.lsrScale));
        if (scaledW >= nativeTarget.width && scaledH >= nativeTarget.height) {
            return false;
        }

        ensureTarget(scaledW, scaledH);
        if (scaledTarget == null) {
            return false;
        }

        if (cfg.upscalerMode == UpscalerMode.TEMPORAL_TAAU) {
            jitterFrameIndex++;
            float[] ndc = new float[2];
            float[] uv = new float[2];
            computeJitterOffset(jitterFrameIndex, scaledW, scaledH, ndc, uv);
            currentJitterNdcX = ndc[0];
            currentJitterNdcY = ndc[1];
            currentJitterUvX = uv[0];
            currentJitterUvY = uv[1];
        } else {
            hasTemporalHistory = false;
        }

        savedNativeTarget = nativeTarget;
        worldPassActive = true;
        ((MinecraftRenderTargetAccessor) mc).lhlux$setMainRenderTarget(scaledTarget);

        scaledTarget.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        scaledTarget.clear(Minecraft.ON_OSX);
        scaledTarget.bindWrite(true);
        RenderSystem.viewport(0, 0, scaledTarget.viewWidth, scaledTarget.viewHeight);
        return true;
    }

    /**
     * Applies sub-pixel projection jitter for TEMPORAL_TAAU. Returns true if the caller must restore
     * the unjittered projection matrix after LevelRenderer finishes.
     */
    public boolean applyProjectionJitter(Matrix4f projectionMatrix) {
        if (!worldPassActive || LuxAddonConfig.get().upscalerMode != UpscalerMode.TEMPORAL_TAAU) {
            return false;
        }
        if (Math.abs(currentJitterNdcX) <= 1.0e-7f && Math.abs(currentJitterNdcY) <= 1.0e-7f) {
            return false;
        }
        projectionMatrix.m20(projectionMatrix.m20() - currentJitterNdcX);
        projectionMatrix.m21(projectionMatrix.m21() - currentJitterNdcY);
        return true;
    }

    public void finishWorldRender(Minecraft mc) {
        if (!worldPassActive) {
            return;
        }
        TextureTarget source = scaledTarget;
        RenderTarget dest = savedNativeTarget;
        restoreNativeTarget(mc);

        if (source == null || dest == null) {
            return;
        }

        LuxAddonConfig cfg = LuxAddonConfig.get();
        UpscalerMode mode = cfg.upscalerMode != null ? cfg.upscalerMode : UpscalerMode.TEMPORAL_TAAU;

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNITS_012)) {
            PostProcessSwapChain.blitDepth(
                    source.frameBufferId, source.width, source.height,
                    dest.frameBufferId, dest.width, dest.height
            );

            if (mode == UpscalerMode.TEMPORAL_TAAU && taauShader.ensureCompiled()) {
                renderTemporalTaau(mc, source, dest, cfg);
                return;
            }

            dest.bindWrite(true);
            RenderSystem.viewport(0, 0, dest.viewWidth, dest.viewHeight);
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            LuxShaderProgram spatial = (mode == UpscalerMode.NIS_FSR1 && nisShader.ensureCompiled())
                    ? nisShader
                    : (lsrShader.ensureCompiled() ? lsrShader : null);

            if (spatial != null) {
                spatial.bind();
                spatial.bindTexture("LowResSampler", 0, source.getColorTextureId());
                spatial.setVec2("SourceTexelSize", 1.0f / source.width, 1.0f / source.height);
                spatial.setFloat("Sharpness", cfg.lsrSharpness);
                PostProcessSwapChain.drawFullscreenQuad();
            } else {
                PostProcessSwapChain.blitColor(
                        source.frameBufferId, source.width, source.height,
                        dest.frameBufferId, dest.width, dest.height,
                        true
                );
            }
        }
    }

    private void renderTemporalTaau(Minecraft mc, TextureTarget source, RenderTarget dest, LuxAddonConfig cfg) {
        FrameMatrixCache frame = FrameMatrixCache.get();
        int dw = dest.width;
        int dh = dest.height;

        TextureTarget currHist = historySwapChain.getOrCreate(taauToggle ? "taau_0" : "taau_1", dw, dh, false, GL11.GL_LINEAR);
        TextureTarget prevHist = historySwapChain.getOrCreate(taauToggle ? "taau_1" : "taau_0", dw, dh, false, GL11.GL_LINEAR);

        boolean sameLevel = mc.level != null && prevLevelIdentity == mc.level;
        boolean sameSize = prevDestWidth == dw && prevDestHeight == dh;
        boolean smallMove = frame.isValid() && prevCamPos.distanceSquared(frame.cameraPos()) < 36.0f;
        boolean historyValid = hasTemporalHistory && sameLevel && sameSize && smallMove && frame.isValid();

        Matrix4f currToPrev = new Matrix4f();
        Matrix4f currToPrevSky = new Matrix4f();
        if (historyValid) {
            Vector3f camDelta = new Vector3f(frame.cameraPos()).sub(prevCamPos);
            Matrix4f trans = new Matrix4f().translation(camDelta.x, camDelta.y, camDelta.z);
            currToPrev.set(prevUnjitteredViewProj).mul(trans).mul(frame.inverseViewProjection());

            Matrix4f rotProjInv = new Matrix4f(frame.projection()).mul(frame.viewRotation()).invert();
            currToPrevSky.set(prevUnjitteredViewProj).mul(rotProjInv);
        } else {
            currToPrev.identity();
            currToPrevSky.identity();
        }

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        GlStateManager._colorMask(true, true, true, true);

        currHist.bindWrite(true);
        taauShader.bind();
        taauShader.bindTexture("LowResColor", 0, source.getColorTextureId());
        taauShader.bindTexture("LowResDepth", 1, source.getDepthTextureId());
        taauShader.bindTexture("HistoryColor", 2, prevHist.getColorTextureId());
        taauShader.setMat4("CurrToPrevMat", currToPrev);
        taauShader.setMat4("CurrToPrevSkyMat", currToPrevSky);
        taauShader.setVec2("JitterUv", currentJitterUvX, currentJitterUvY);
        taauShader.setVec2("LowResTexelSize", 1.0f / source.width, 1.0f / source.height);
        taauShader.setVec2("NativeTexelSize", 1.0f / dw, 1.0f / dh);
        taauShader.setFloat("Sharpness", cfg.lsrSharpness);
        taauShader.setInt("HistoryValid", historyValid ? 1 : 0);
        PostProcessSwapChain.drawFullscreenQuad();

        // Present resolved native buffer to mainRenderTarget with subtle CAS sharpening
        dest.bindWrite(true);
        RenderSystem.viewport(0, 0, dest.viewWidth, dest.viewHeight);
        if (cfg.lsrSharpness > 0.01f && lsrShader.ensureCompiled()) {
            lsrShader.bind();
            lsrShader.bindTexture("LowResSampler", 0, currHist.getColorTextureId());
            lsrShader.setVec2("SourceTexelSize", 1.0f / dw, 1.0f / dh);
            lsrShader.setFloat("Sharpness", cfg.lsrSharpness * 0.75f);
            PostProcessSwapChain.drawFullscreenQuad();
        } else {
            PostProcessSwapChain.blitColor(currHist.frameBufferId, dw, dh, dest.frameBufferId, dw, dh, false);
        }

        if (frame.isValid()) {
            prevUnjitteredViewProj.set(frame.viewProjection());
            prevCamPos.set(frame.cameraPos());
            prevLevelIdentity = mc.level;
            prevDestWidth = dw;
            prevDestHeight = dh;
            hasTemporalHistory = true;
            taauToggle = !taauToggle;
        } else {
            hasTemporalHistory = false;
        }
    }

    public void restoreNativeTarget(Minecraft mc) {
        if (savedNativeTarget != null) {
            ((MinecraftRenderTargetAccessor) mc).lhlux$setMainRenderTarget(savedNativeTarget);
            savedNativeTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, savedNativeTarget.viewWidth, savedNativeTarget.viewHeight);
            savedNativeTarget = null;
        }
        worldPassActive = false;
    }

    private void ensureTarget(int width, int height) {
        if (scaledTarget == null) {
            scaledTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            scaledTarget.setFilterMode(GL11.GL_LINEAR);
        } else if (scaledTarget.width != width || scaledTarget.height != height) {
            scaledTarget.resize(width, height, Minecraft.ON_OSX);
            scaledTarget.setFilterMode(GL11.GL_LINEAR);
        }
    }

    public void releaseResources() {
        if (worldPassActive) {
            restoreNativeTarget(Minecraft.getInstance());
        }
        if (scaledTarget != null) {
            scaledTarget.destroyBuffers();
            scaledTarget = null;
        }
        historySwapChain.destroyAll();
        lsrShader.destroy();
        nisShader.destroy();
        taauShader.destroy();
        hasTemporalHistory = false;
        prevLevelIdentity = null;
    }
}
