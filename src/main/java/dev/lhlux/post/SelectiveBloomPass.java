package dev.lhlux.post;

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
import org.lwjgl.opengl.GL11;

/**
 * Shine-inspired 5-level selective bloom pyramid with Distant Horizons depth-distance fade
 * and optional sun rim lighting.
 */
public final class SelectiveBloomPass {
    private static final SelectiveBloomPass INSTANCE = new SelectiveBloomPass();
    private static final int PYRAMID_LEVELS = 5;

    private final PostProcessSwapChain swapChain = new PostProcessSwapChain();
    private final LuxShaderProgram extractShader = new LuxShaderProgram(
            "bloom_extract", "shaders/core/fullscreen_quad.vsh", "shaders/core/bloom_extract.fsh"
    );
    private final LuxShaderProgram downsampleShader = new LuxShaderProgram(
            "bloom_downsample", "shaders/core/fullscreen_quad.vsh", "shaders/core/bloom_downsample.fsh"
    );
    private final LuxShaderProgram upsampleShader = new LuxShaderProgram(
            "bloom_upsample", "shaders/core/fullscreen_quad.vsh", "shaders/core/bloom_upsample.fsh"
    );
    private final LuxShaderProgram compositeShader = new LuxShaderProgram(
            "bloom_composite", "shaders/core/fullscreen_quad.vsh", "shaders/core/bloom_composite.fsh"
    );

    private boolean active;

    private SelectiveBloomPass() {
    }

    public static SelectiveBloomPass get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public void onConfigChanged() {
        if (!LuxAddonConfig.get().bloomEnabled && !LuxAddonConfig.get().rimLightingEnabled) {
            releaseResources();
        }
    }

    public void apply(Minecraft mc) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if ((!cfg.bloomEnabled && !cfg.rimLightingEnabled)
                || mc.level == null
                || !ModCompat.isLivingHorizonsActive()
                || ModCompat.isExternalShaderPackActive()) {
            active = false;
            return;
        }

        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width < 16 || main.height < 16) {
            active = false;
            return;
        }

        if (!extractShader.ensureCompiled()
                || !downsampleShader.ensureCompiled()
                || !upsampleShader.ensureCompiled()
                || !compositeShader.ensureCompiled()) {
            active = false;
            return;
        }

        int w = main.width;
        int h = main.height;

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNITS_012)) {
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            // Copy scene color to a stable read buffer so we can safely composite back to main
            TextureTarget sceneCopy = swapChain.getOrCreate("scene_copy", w, h, false, GL11.GL_LINEAR);
            PostProcessSwapChain.blitColor(main.frameBufferId, w, h, sceneCopy.frameBufferId, w, h, false);

            TextureTarget[] down = new TextureTarget[PYRAMID_LEVELS];
            for (int i = 0; i < PYRAMID_LEVELS; i++) {
                int lw = Math.max(1, w >> (i + 1));
                int lh = Math.max(1, h >> (i + 1));
                down[i] = swapChain.getOrCreate("bloom_down_" + i, lw, lh, false, GL11.GL_LINEAR);
            }

            if (cfg.bloomEnabled && cfg.bloomStrength > 0.001f) {
                // 1. Selective extract into half-res target
                down[0].bindWrite(true);
                extractShader.bind();
                extractShader.bindTexture("SceneSampler", 0, sceneCopy.getColorTextureId());
                extractShader.bindTexture("DepthSampler", 1, main.getDepthTextureId());
                extractShader.setFloat("Threshold", cfg.bloomThreshold);
                extractShader.setFloat("MaxDistance", cfg.dhDepthCompat ? cfg.bloomMaxDistance : 1024.0f);
                extractShader.setFloat("NearPlane", FrameMatrixCache.get().nearPlane());
                extractShader.setFloat("FarPlane", FrameMatrixCache.get().farPlane());
                PostProcessSwapChain.drawFullscreenQuad();

                // 2. Downsample chain
                downsampleShader.bind();
                for (int i = 1; i < PYRAMID_LEVELS; i++) {
                    TextureTarget prev = down[i - 1];
                    TextureTarget curr = down[i];
                    curr.bindWrite(true);
                    downsampleShader.bindTexture("SourceSampler", 0, prev.getColorTextureId());
                    downsampleShader.setVec2("TexelSize", 1.0f / prev.width, 1.0f / prev.height);
                    PostProcessSwapChain.drawFullscreenQuad();
                }

                // 3. Upsample pyramid back into half-res
                upsampleShader.bind();
                upsampleShader.setFloat("Radius", cfg.bloomRadius);
                for (int i = PYRAMID_LEVELS - 2; i >= 0; i--) {
                    TextureTarget low = down[i + 1];
                    TextureTarget curr = down[i];
                    TextureTarget upTmp = swapChain.getOrCreate("bloom_up_" + i, curr.width, curr.height, false, GL11.GL_LINEAR);
                    upTmp.bindWrite(true);
                    upsampleShader.bindTexture("LowSampler", 0, low.getColorTextureId());
                    upsampleShader.bindTexture("CurrentSampler", 1, curr.getColorTextureId());
                    upsampleShader.setVec2("LowTexelSize", 1.0f / low.width, 1.0f / low.height);
                    PostProcessSwapChain.drawFullscreenQuad();
                    down[i] = upTmp;
                }
            } else {
                down[0].setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                down[0].clear(Minecraft.ON_OSX);
            }

            // 4. Composite bloom + optional rim lighting back to main target
            main.bindWrite(true);
            compositeShader.bind();
            compositeShader.bindTexture("SceneSampler", 0, sceneCopy.getColorTextureId());
            compositeShader.bindTexture("BloomSampler", 1, down[0].getColorTextureId());
            compositeShader.bindTexture("DepthSampler", 2, main.getDepthTextureId());
            compositeShader.setVec2("SceneTexelSize", 1.0f / w, 1.0f / h);
            compositeShader.setFloat("BloomStrength", cfg.bloomEnabled ? cfg.bloomStrength : 0.0f);
            compositeShader.setInt("RimEnabled", cfg.rimLightingEnabled ? 1 : 0);
            compositeShader.setFloat("RimStrength", cfg.rimLightingStrength);
            compositeShader.setFloat("DaylightFactor", FrameMatrixCache.get().daylightFactor());
            PostProcessSwapChain.drawFullscreenQuad();

            active = true;
        } catch (Throwable t) {
            active = false;
        }
    }

    public void releaseResources() {
        swapChain.destroyAll();
        extractShader.destroy();
        downsampleShader.destroy();
        upsampleShader.destroy();
        compositeShader.destroy();
        active = false;
    }
}
