package dev.lhlux.shadows;

import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.render.GlStateGuard;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Dual-cascade (near + far) orthographic shadow map engine with staggered far-cascade caching
 * and Distant Horizons radial horizon fade.
 */
public final class CascadedShadowEngine {
    private static final CascadedShadowEngine INSTANCE = new CascadedShadowEngine();

    private final ShadowCascade nearCascade = new ShadowCascade("near");
    private final ShadowCascade farCascade = new ShadowCascade("far");
    private final ShadowCadenceCache cadenceCache = new ShadowCadenceCache();
    private final ShadowDepthTarget nearTarget = new ShadowDepthTarget();
    private final ShadowDepthTarget farTarget = new ShadowDepthTarget();
    private final EmbeddiumShadowBridge embeddiumBridge = new EmbeddiumShadowBridge();

    private boolean active;
    private volatile boolean shadowPassActive;
    private int fallbackDepthTex;
    private boolean loggedFirstRender;

    private CascadedShadowEngine() {
    }

    public static CascadedShadowEngine get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isShadowPassActive() {
        return shadowPassActive;
    }

    public void onConfigChanged() {
        cadenceCache.reset();
        nearCascade.invalidate();
        farCascade.invalidate();
    }

    public void prepareShadows(Minecraft mc, Camera camera, float partialTick) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.csmEnabled || mc.level == null || !ModCompat.isEmbeddiumLoaded()
                || !ModCompat.isLivingHorizonsActive()
                || ModCompat.isExternalShaderPackActive() || !embeddiumBridge.isAvailable()) {
            active = false;
            return;
        }

        // Only render sun/moon cascades in worlds with sky light
        if (!mc.level.dimensionType().hasSkyLight()) {
            active = false;
            return;
        }

        FrameMatrixCache frame = FrameMatrixCache.get();
        if (!frame.isValid() || frame.daylightFactor() <= 0.02f) {
            active = false;
            return;
        }

        int res = cfg.shadowResolution;
        try {
            nearTarget.ensureSize(res);
            farTarget.ensureSize(res);
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.warn("Disabling CSM shadows due to FBO allocation failure", t);
            active = false;
            return;
        }

        Vector3f camPos = frame.cameraPos();
        Vector3f sunDir = frame.lightDirection();

        boolean updateNear = cadenceCache.shouldUpdateNear(camPos, sunDir);
        boolean updateFar = cadenceCache.shouldUpdateFar(camPos, sunDir, cfg.farCascadeUpdateInterval);

        // Cached shadow maps are still valid: refresh camera-relative matrices and skip GPU rebuild
        if (!updateNear && !updateFar) {
            nearCascade.refreshCameraRelativeMatrix(camPos);
            farCascade.refreshCameraRelativeMatrix(camPos);
            active = true;
            return;
        }

        if (updateNear) {
            nearCascade.update(camPos, sunDir, cfg.nearCascadeDistance, 220.0f, res);
        } else {
            nearCascade.refreshCameraRelativeMatrix(camPos);
        }
        if (updateFar) {
            farCascade.update(camPos, sunDir, cfg.farCascadeDistance, 320.0f, res);
        } else {
            farCascade.refreshCameraRelativeMatrix(camPos);
        }

        ShadowSectionLists.BuildResult lists = embeddiumBridge.prepareRenderLists(
                nearCascade, updateNear,
                farCascade, updateFar
        );
        if (lists == null) {
            active = false;
            return;
        }

        shadowPassActive = true;
        try (GlStateGuard ignored = new GlStateGuard(0)) {
            if (updateNear && lists.nearLists() != null) {
                nearTarget.bindForWrite();
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                embeddiumBridge.renderCascade(nearCascade, lists.chunkRenderer(), lists.nearLists());
                if (cfg.entityShadowsEnabled) {
                    EntityShadowPass.render(mc, camera, partialTick, nearCascade);
                }
                cadenceCache.markNearUpdated(camPos, sunDir);
            }

            if (updateFar && lists.farLists() != null) {
                farTarget.bindForWrite();
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                embeddiumBridge.renderCascade(farCascade, lists.chunkRenderer(), lists.farLists());
                cadenceCache.markFarUpdated(camPos, sunDir);
            }
            if (!loggedFirstRender) {
                loggedFirstRender = true;
                LivingHorizonsLuxMod.LOGGER.info(
                        "CSM active: nearRegions={}, farRegions={}, daylight={}, strength={}",
                        lists.nearLists() != null ? lists.nearLists().regionCount() : -1,
                        lists.farLists() != null ? lists.farLists().regionCount() : -1,
                        frame.daylightFactor(),
                        effectiveShadowStrength()
                );
            }
            active = true;
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.error("CSM shadow render error, disabling shadows for frame", t);
            active = false;
        } finally {
            shadowPassActive = false;
        }
    }

    public int nearDepthTexture() {
        return nearTarget.isAllocated() ? nearTarget.depthTextureId() : ensureFallbackDepthTexture();
    }

    public int farDepthTexture() {
        return farTarget.isAllocated() ? farTarget.depthTextureId() : ensureFallbackDepthTexture();
    }

    public Matrix4f nearWorldRelMatrix() {
        return nearCascade.worldRelativeToLightUvz();
    }

    public Matrix4f farWorldRelMatrix() {
        return farCascade.worldRelativeToLightUvz();
    }

    public float nearRadius() {
        return nearCascade.radius();
    }

    public float farRadius() {
        return farCascade.radius();
    }

    public float texelSize() {
        return nearCascade.texelSize();
    }

    public float effectiveShadowStrength() {
        FrameMatrixCache frame = FrameMatrixCache.get();
        float rainAtten = 1.0f - frame.rainLevel() * 0.72f;
        return LuxAddonConfig.get().shadowStrength * frame.daylightFactor() * rainAtten;
    }

    private int ensureFallbackDepthTexture() {
        if (fallbackDepthTex == 0) {
            fallbackDepthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackDepthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 1, 1, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[]{1.0f});
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        return fallbackDepthTex;
    }

    public void releaseResources() {
        nearTarget.destroy();
        farTarget.destroy();
        embeddiumBridge.clearCaches();
        cadenceCache.reset();
        nearCascade.invalidate();
        farCascade.invalidate();
        active = false;
        if (fallbackDepthTex != 0) {
            GL11.glDeleteTextures(fallbackDepthTex);
            fallbackDepthTex = 0;
        }
    }
}
