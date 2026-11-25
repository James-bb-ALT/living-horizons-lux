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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * 100% GPU underwater/shallow-river caustic pass so Living Horizons' CPU ShadowVolume can be retired
 * completely whenever CSM sun shadows are active.
 */
public final class GpuWaterCausticsPass {
    private static final GpuWaterCausticsPass INSTANCE = new GpuWaterCausticsPass();

    private final PostProcessSwapChain swapChain = new PostProcessSwapChain();
    private final LuxShaderProgram shader = new LuxShaderProgram(
            "water_caustics_gpu", "shaders/core/fullscreen_quad.vsh", "shaders/core/water_caustics_gpu.fsh"
    );

    private boolean active;

    private GpuWaterCausticsPass() {
    }

    public static GpuWaterCausticsPass get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public void onConfigChanged() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.waterCausticsEnabled || !cfg.gpuWaterCausticsEnabled) {
            releaseResources();
        }
    }

    public void apply(Minecraft mc) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.waterCausticsEnabled
                || !cfg.gpuWaterCausticsEnabled
                || cfg.gpuWaterCausticsStrength <= 0.001f
                || mc.level == null
                || !mc.level.dimensionType().hasSkyLight()
                || !ModCompat.isLivingHorizonsActive()
                || ModCompat.isExternalShaderPackActive()) {
            active = false;
            return;
        }

        FrameMatrixCache frame = FrameMatrixCache.get();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width < 16 || main.height < 16 || !frame.isValid() || frame.daylightFactor() <= 0.04f) {
            active = false;
            return;
        }
        if (!shader.ensureCompiled()) {
            active = false;
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        boolean underwater = camera.getFluidInCamera() == FogType.WATER;
        Vector3f camPos = frame.cameraPos();
        float waterLevel = findWaterLevel(mc, camPos, underwater);
        float timeSec = (System.nanoTime() & 0xFFFFFFFFFFFFL) * 1.0e-9f;

        int w = main.width;
        int h = main.height;
        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNIT_0 | GlStateGuard.UNIT_1)) {
            TextureTarget sceneCopy = swapChain.getOrCreate("caustics_scene_copy", w, h, false, GL11.GL_LINEAR);
            PostProcessSwapChain.blitColor(main.frameBufferId, w, h, sceneCopy.frameBufferId, w, h, false);

            main.bindWrite(true);
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            shader.bind();
            shader.bindTexture("SceneSampler", 0, sceneCopy.getColorTextureId());
            shader.bindTexture("DepthSampler", 1, main.getDepthTextureId());
            shader.setMat4("InvViewProj", frame.inverseViewProjection());
            shader.setVec3("CameraPos", camPos.x, camPos.y, camPos.z);
            Vector3f sun = frame.lightDirection();
            shader.setVec3("SunDir", sun.x, sun.y, sun.z);
            shader.setFloat("TimeSec", timeSec);
            shader.setFloat("WaterLevel", waterLevel);
            shader.setFloat("DaylightFactor", frame.daylightFactor());
            shader.setFloat("RainLevel", frame.rainLevel());
            shader.setFloat("CausticStrength", cfg.gpuWaterCausticsStrength);
            shader.setInt("CameraUnderwater", underwater ? 1 : 0);
            PostProcessSwapChain.drawFullscreenQuad();
            active = true;
        } catch (Throwable ignored) {
            active = false;
        }
    }

    private static float findWaterLevel(Minecraft mc, Vector3f camPos, boolean underwater) {
        if (underwater && mc.level != null) {
            int bx = (int) Math.floor(camPos.x);
            int bz = (int) Math.floor(camPos.z);
            int startY = (int) Math.floor(camPos.y);
            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
            for (int dy = 0; dy <= 8; dy++) {
                mpos.set(bx, startY + dy, bz);
                if (!mc.level.getFluidState(mpos).is(FluidTags.WATER)) {
                    return (startY + dy - 1) + 0.88f;
                }
            }
        }
        return mc.level.getSeaLevel() - 0.12f;
    }

    public void releaseResources() {
        swapChain.destroyAll();
        shader.destroy();
        active = false;
    }
}
