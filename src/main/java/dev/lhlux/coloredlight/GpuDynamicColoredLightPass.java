package dev.lhlux.coloredlight;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.render.GlStateGuard;
import dev.lhlux.render.LuxComputeShader;
import dev.lhlux.render.LuxShaderProgram;
import dev.lhlux.render.PostProcessSwapChain;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Vendor-neutral (NVIDIA & AMD) GPU deferred dynamic colored lighting pass.
 * Uses OpenGL 4.3 Compute Shader + Tiled SSBO culling for up to 256 dynamic lights on supported GPUs,
 * with automatic OpenGL 3.3 Core fragment shader fallback (up to 48 lights) on older drivers.
 */
public final class GpuDynamicColoredLightPass {
    public static final int MAX_COMPUTE_LIGHTS = 256;
    public static final int MAX_GL33_LIGHTS = 48;
    private static final double COMPUTE_GATHER_RANGE = 48.0;
    private static final double GL33_GATHER_RANGE = 36.0;

    private static final GpuDynamicColoredLightPass INSTANCE = new GpuDynamicColoredLightPass();

    private final PostProcessSwapChain swapChain = new PostProcessSwapChain();
    private final LuxShaderProgram fragmentShader = new LuxShaderProgram(
            "colored_lights_gpu", "shaders/core/fullscreen_quad.vsh", "shaders/core/colored_lights_gpu.fsh"
    );
    private final LuxComputeShader computeShader = new LuxComputeShader(
            "colored_lights_compute", "shaders/core/colored_lights_compute.csh"
    );

    // Pre-allocated scratch buffers so gather + upload creates zero heap garbage per frame
    private final float[] posRadius = new float[MAX_COMPUTE_LIGHTS * 4];
    private final float[] colorIntensity = new float[MAX_COMPUTE_LIGHTS * 4];
    private final double[] distSqBuf = new double[MAX_COMPUTE_LIGHTS];
    private final FloatBuffer ssboPosUpload = BufferUtils.createFloatBuffer(MAX_COMPUTE_LIGHTS * 4);
    private final FloatBuffer ssboColUpload = BufferUtils.createFloatBuffer(MAX_COMPUTE_LIGHTS * 4);

    private int ssboId;
    private int activeCount;
    private int currentGatherCap = MAX_GL33_LIGHTS;
    private boolean usingComputePath;
    private boolean loggedComputeFailure;
    private boolean loggedFirstActivePass;

    private GpuDynamicColoredLightPass() {
    }

    public static GpuDynamicColoredLightPass get() {
        return INSTANCE;
    }

    public int activeLightCount() {
        return activeCount;
    }

    public boolean isUsingComputePath() {
        return usingComputePath;
    }

    public void onConfigChanged() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.coloredLightEnabled || !cfg.gpuDynamicColoredLights) {
            activeCount = 0;
            usingComputePath = false;
            releaseResources();
        }
    }

    public void apply(Minecraft mc, float partialTick) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.coloredLightEnabled
                || !cfg.gpuDynamicColoredLights
                || mc.level == null
                || !ModCompat.isLivingHorizonsActive()
                || ModCompat.isExternalShaderPackActive()) {
            activeCount = 0;
            usingComputePath = false;
            return;
        }

        RenderTarget main = mc.getMainRenderTarget();
        FrameMatrixCache frame = FrameMatrixCache.get();
        if (main == null || main.width < 16 || main.height < 16 || !frame.isValid()) {
            activeCount = 0;
            usingComputePath = false;
            return;
        }

        boolean tryCompute = cfg.gpuComputeLightingEnabled && computeShader.ensureCompiled();
        currentGatherCap = tryCompute ? MAX_COMPUTE_LIGHTS : MAX_GL33_LIGHTS;
        double range = tryCompute ? COMPUTE_GATHER_RANGE : GL33_GATHER_RANGE;

        gatherLights(mc, cfg, frame.cameraPos(), partialTick, range * range);
        if (activeCount <= 0) {
            usingComputePath = tryCompute;
            return;
        }

        int w = main.width;
        int h = main.height;
        if (!loggedFirstActivePass) {
            loggedFirstActivePass = true;
            LivingHorizonsLuxMod.LOGGER.info(
                    "GpuDynamicColoredLightPass active: lights={}, path={}, target={}x{}",
                    activeCount, tryCompute ? "ComputeShader" : "GL33Fragment", w, h
            );
        }
        if (tryCompute && applyComputePass(main, frame, cfg, w, h)) {
            usingComputePath = true;
            return;
        }

        usingComputePath = false;
        applyFragmentFallback(main, frame, cfg, w, h);
    }

    private boolean applyComputePass(RenderTarget main, FrameMatrixCache frame, LuxAddonConfig cfg, int w, int h) {
        ensureSsboAllocated();
        if (ssboId == 0) {
            return false;
        }

        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNIT_0 | GlStateGuard.UNIT_1)) {
            TextureTarget outTarget = swapChain.getOrCreate("dl_compute_out", w, h, false, GL11.GL_NEAREST);

            ssboPosUpload.clear();
            ssboPosUpload.put(posRadius, 0, activeCount * 4);
            ssboPosUpload.flip();
            ssboColUpload.clear();
            ssboColUpload.put(colorIntensity, 0, activeCount * 4);
            ssboColUpload.flip();

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, ssboPosUpload);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) MAX_COMPUTE_LIGHTS * 16L, ssboColUpload);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, ssboId);

            GL42.glBindImageTexture(0, outTarget.getColorTextureId(), 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);

            computeShader.bind();
            computeShader.bindTexture("SceneSampler", 0, main.getColorTextureId());
            computeShader.bindTexture("DepthSampler", 1, main.getDepthTextureId());
            computeShader.setMat4("InvViewProj", frame.inverseViewProjection());
            computeShader.setInt2("ImageSize", w, h);
            computeShader.setInt("LightCount", activeCount);
            computeShader.setFloat("GlobalIntensity", cfg.coloredLightIntensity);
            computeShader.setFloat("Saturation", cfg.coloredLightSaturation);
            computeShader.setFloat("DaylightFactor", frame.daylightFactor());
            computeShader.setFloat("DaytimeBoost", cfg.coloredLightDaytimeBoost);

            computeShader.dispatch((w + 15) / 16, (h + 15) / 16, 1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GL42.GL_FRAMEBUFFER_BARRIER_BIT);

            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            PostProcessSwapChain.blitColor(outTarget.frameBufferId, w, h, main.frameBufferId, w, h, false);
            return true;
        } catch (Throwable t) {
            if (!loggedComputeFailure) {
                loggedComputeFailure = true;
                LivingHorizonsLuxMod.LOGGER.warn("GPU Compute colored light pass failed, falling back to GL 3.3", t);
            }
            return false;
        }
    }

    private void applyFragmentFallback(RenderTarget main, FrameMatrixCache frame, LuxAddonConfig cfg, int w, int h) {
        if (!fragmentShader.ensureCompiled()) {
            return;
        }
        int uploadCount = Math.min(activeCount, MAX_GL33_LIGHTS);
        try (GlStateGuard ignored = new GlStateGuard(GlStateGuard.UNIT_0 | GlStateGuard.UNIT_1)) {
            TextureTarget sceneCopy = swapChain.getOrCreate("dl_scene_copy", w, h, false, GL11.GL_LINEAR);
            PostProcessSwapChain.blitColor(main.frameBufferId, w, h, sceneCopy.frameBufferId, w, h, false);

            main.bindWrite(true);
            GlStateManager._colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            fragmentShader.bind();
            fragmentShader.bindTexture("SceneSampler", 0, sceneCopy.getColorTextureId());
            fragmentShader.bindTexture("DepthSampler", 1, main.getDepthTextureId());
            fragmentShader.setMat4("InvViewProj", frame.inverseViewProjection());
            fragmentShader.setInt("LightCount", uploadCount);
            fragmentShader.setVec4Array("LightPosRadius", posRadius, uploadCount);
            fragmentShader.setVec4Array("LightColorIntensity", colorIntensity, uploadCount);
            fragmentShader.setFloat("GlobalIntensity", cfg.coloredLightIntensity);
            fragmentShader.setFloat("Saturation", cfg.coloredLightSaturation);
            fragmentShader.setFloat("DaylightFactor", frame.daylightFactor());
            fragmentShader.setFloat("DaytimeBoost", cfg.coloredLightDaytimeBoost);
            PostProcessSwapChain.drawFullscreenQuad();
        } catch (Throwable ignored) {
        }
    }

    private void ensureSsboAllocated() {
        if (ssboId != 0) {
            return;
        }
        ssboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) MAX_COMPUTE_LIGHTS * 32L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private void gatherLights(Minecraft mc, LuxAddonConfig cfg, Vector3f cam, float partialTick, double maxRangeSq) {
        activeCount = 0;
        float radiusScale = cfg.coloredLightRadiusScale;

        // 1. Local player handheld lights (highest priority)
        LocalPlayer localPlayer = mc.player;
        if (cfg.handheldColoredLight && localPlayer != null) {
            addPlayerHeldLights(localPlayer, cam, partialTick, radiusScale, true);
        }

        // 2. Nearby entities, dropped items, and other players
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            double ex = lerp(entity.xOld, entity.getX(), partialTick);
            double ey = lerp(entity.yOld, entity.getY(), partialTick) + entity.getBbHeight() * 0.5;
            double ez = lerp(entity.zOld, entity.getZ(), partialTick);
            double dx = ex - cam.x;
            double dy = ey - cam.y;
            double dz = ez - cam.z;
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq > maxRangeSq) {
                continue;
            }

            if (entity == localPlayer) {
                if (entity.isOnFire()) {
                    insertLight(dx, dy, dz, 7.5f * radiusScale, 1.0f, 0.55f, 0.16f, 0.85f, dSq);
                }
                continue;
            }

            if (cfg.handheldColoredLight && entity instanceof Player otherPlayer) {
                addPlayerHeldLights(otherPlayer, cam, partialTick, radiusScale, false);
            }

            if (!cfg.entityItemColoredLights) {
                continue;
            }

            if (entity instanceof ItemEntity itemEntity) {
                int packed = LightColorRegistry.getItemEmissionLevelAndRgb4(itemEntity.getItem());
                if (packed != 0) {
                    insertPackedLight(dx, dy, dz, packed, radiusScale, 0.90f, dSq);
                }
                continue;
            }

            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                int mainPacked = LightColorRegistry.getItemEmissionLevelAndRgb4(living.getMainHandItem());
                int offPacked = LightColorRegistry.getItemEmissionLevelAndRgb4(living.getOffhandItem());
                int bestPacked = (mainPacked >>> 12) >= (offPacked >>> 12) ? mainPacked : offPacked;
                if (bestPacked != 0) {
                    insertPackedLight(dx, dy, dz, bestPacked, radiusScale, 0.90f, dSq);
                }
            }

            if (entity.isOnFire() || entity instanceof Blaze || entity instanceof AbstractHurtingProjectile) {
                insertLight(dx, dy, dz, 8.5f * radiusScale, 1.0f, 0.52f, 0.14f, 0.95f, dSq);
            } else if (entity instanceof MagmaCube) {
                insertLight(dx, dy, dz, 6.5f * radiusScale, 1.0f, 0.42f, 0.10f, 0.80f, dSq);
            } else if (entity instanceof EndCrystal) {
                insertLight(dx, dy, dz, 10.5f * radiusScale, 0.95f, 0.45f, 1.0f, 1.05f, dSq);
            } else if (entity instanceof SpectralArrow) {
                insertLight(dx, dy, dz, 5.5f * radiusScale, 1.0f, 0.92f, 0.42f, 0.75f, dSq);
            } else if (entity.getClass().getSimpleName().contains("GlowSquid")) {
                insertLight(dx, dy, dz, 7.0f * radiusScale, 0.25f, 0.95f, 0.90f, 0.80f, dSq);
            } else if (entity.getClass().getSimpleName().contains("Allay")) {
                insertLight(dx, dy, dz, 6.0f * radiusScale, 0.42f, 0.85f, 1.0f, 0.75f, dSq);
            }
        }

        // 3. Nearby placed colored emitters for instant smooth per-pixel radius pools & 0ms updates
        ColoredLightEngine.get().collectNearbyEmitters(cam.x, cam.y, cam.z, maxRangeSq, (wx, wy, wz, rgb4, dSq) -> {
            int level = Rgb4Color.maxComponent(rgb4);
            if (level <= 0) {
                return;
            }
            float r = Rgb4Color.red(rgb4) / (float) level;
            float g = Rgb4Color.green(rgb4) / (float) level;
            float b = Rgb4Color.blue(rgb4) / (float) level;
            float radius = (3.0f + level * 0.65f) * radiusScale;
            float intensity = (level / 15.0f) * 0.85f;
            insertLight(wx - cam.x, wy - cam.y, wz - cam.z, radius, r, g, b, intensity, dSq);
        });
    }

    private void addPlayerHeldLights(Player player, Vector3f cam, float partialTick, float radiusScale, boolean isLocal) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        int mainPacked = LightColorRegistry.getItemEmissionLevelAndRgb4(main);
        int offPacked = LightColorRegistry.getItemEmissionLevelAndRgb4(off);
        if (mainPacked == 0 && offPacked == 0) {
            return;
        }

        double px = lerp(player.xOld, player.getX(), partialTick);
        double py = lerp(player.yOld, player.getY(), partialTick) + player.getEyeHeight() - 0.22;
        double pz = lerp(player.zOld, player.getZ(), partialTick);

        Vec3 look = player.getLookAngle();
        double sideLen = Math.hypot(-look.z, look.x);
        double rx = sideLen > 1e-4 ? (-look.z / sideLen) : 1.0;
        double rz = sideLen > 1e-4 ? (look.x / sideLen) : 0.0;

        if (mainPacked != 0) {
            double fwd = isLocal ? 0.55 : 0.35;
            double side = 0.28;
            double lx = (px + look.x * fwd + rx * side) - cam.x;
            double ly = (py + look.y * 0.35) - cam.y;
            double lz = (pz + look.z * fwd + rz * side) - cam.z;
            insertPackedLight(lx, ly, lz, mainPacked, radiusScale, 1.05f, isLocal ? 0.0 : (lx * lx + ly * ly + lz * lz));
        }
        if (offPacked != 0) {
            double fwd = isLocal ? 0.50 : 0.30;
            double side = -0.28;
            double lx = (px + look.x * fwd + rx * side) - cam.x;
            double ly = (py + look.y * 0.35) - cam.y;
            double lz = (pz + look.z * fwd + rz * side) - cam.z;
            insertPackedLight(lx, ly, lz, offPacked, radiusScale, 1.05f, isLocal ? 0.01 : (lx * lx + ly * ly + lz * lz));
        }
    }

    private void insertPackedLight(double relX, double relY, double relZ, int packed, float radiusScale, float boost, double dSq) {
        int level = (packed >>> 12) & 0xF;
        int rgb4 = packed & 0xFFF;
        if (level <= 0 || rgb4 == 0) {
            return;
        }
        int peak = Math.max(1, Rgb4Color.maxComponent(rgb4));
        float r = Rgb4Color.red(rgb4) / (float) peak;
        float g = Rgb4Color.green(rgb4) / (float) peak;
        float b = Rgb4Color.blue(rgb4) / (float) peak;
        float radius = (3.0f + level * 0.68f) * radiusScale;
        float intensity = (level / 15.0f) * boost;
        insertLight(relX, relY, relZ, radius, r, g, b, intensity, dSq);
    }

    private void insertLight(double relX, double relY, double relZ, float radius, float r, float g, float b, float intensity, double dSq) {
        int idx;
        if (activeCount < currentGatherCap) {
            idx = activeCount++;
        } else {
            // Replace the farthest active light if this one is closer
            int worstIdx = -1;
            double worstDist = dSq;
            for (int i = 0; i < currentGatherCap; i++) {
                if (distSqBuf[i] > worstDist) {
                    worstDist = distSqBuf[i];
                    worstIdx = i;
                }
            }
            if (worstIdx < 0) {
                return;
            }
            idx = worstIdx;
        }

        int base = idx * 4;
        posRadius[base] = (float) relX;
        posRadius[base + 1] = (float) relY;
        posRadius[base + 2] = (float) relZ;
        posRadius[base + 3] = radius;

        colorIntensity[base] = r;
        colorIntensity[base + 1] = g;
        colorIntensity[base + 2] = b;
        colorIntensity[base + 3] = intensity;

        distSqBuf[idx] = dSq;
    }

    private static double lerp(double prev, double curr, float t) {
        return prev + (curr - prev) * t;
    }

    public void releaseResources() {
        swapChain.destroyAll();
        fragmentShader.destroy();
        computeShader.destroy();
        if (ssboId != 0) {
            GL15.glDeleteBuffers(ssboId);
            ssboId = 0;
        }
        activeCount = 0;
        usingComputePath = false;
    }
}
