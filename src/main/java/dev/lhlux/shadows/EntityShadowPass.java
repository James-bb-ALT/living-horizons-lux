package dev.lhlux.shadows;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.render.FrameMatrixCache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renders nearby entities into the near shadow cascade depth buffer so mobs and players cast real sun shadows.
 * Properly configures and restores orthographic light projection, and skips Fauna Engine entities whose GPU
 * transform-feedback/deferred batches require main camera RenderLevelStageEvent lifecycles.
 */
public final class EntityShadowPass {
    private static final String FAUNA_ENGINE_PACKAGE_PREFIX = "net.magister.liberanimalium.";
    private static final IdentityHashMap<Class<?>, Boolean> FAUNA_CLASS_CACHE = new IdentityHashMap<>(64);
    private static final ArrayList<Entity> CANDIDATES = new ArrayList<>(64);
    private static final PoseStack POSE_STACK = new PoseStack();
    private static final Matrix4f SAVED_PROJECTION = new Matrix4f();
    private static volatile boolean entityShadowPassActive;

    private EntityShadowPass() {
    }

    public static boolean isEntityShadowPassActive() {
        return entityShadowPassActive;
    }

    private static boolean isFaunaEngineEntity(Entity entity) {
        Class<?> cls = entity.getClass();
        Boolean cached = FAUNA_CLASS_CACHE.get(cls);
        if (cached == null) {
            cached = cls.getName().startsWith(FAUNA_ENGINE_PACKAGE_PREFIX);
            FAUNA_CLASS_CACHE.put(cls, cached);
        }
        return cached;
    }

    public static void render(Minecraft mc, Camera camera, float partialTick, ShadowCascade nearCascade) {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        if (!cfg.entityShadowsEnabled || cfg.maxEntityShadowCasters <= 0) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null || FrameMatrixCache.get().daylightFactor() <= 0.03f) {
            return;
        }

        Vec3 camPos = camera.getPosition();
        float maxRange = Math.min(nearCascade.radius() * 0.9f, 40.0f);
        double maxRangeSq = maxRange * maxRange;

        CANDIDATES.clear();
        try {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == null || !entity.isAlive() || entity.isInvisible()) {
                    continue;
                }
                if (entity == camera.getEntity() && !camera.isDetached()) {
                    continue;
                }
                // Fauna Engine entities render via GPU Transform Feedback / deferred batches that are flushed on AFTER_ENTITIES
                if (isFaunaEngineEntity(entity)) {
                    continue;
                }
                double distSq = entity.distanceToSqr(camPos);
                if (distSq <= maxRangeSq) {
                    CANDIDATES.add(entity);
                }
            }
            if (CANDIDATES.isEmpty()) {
                return;
            }
            List<Entity> renderList = CANDIDATES;
            if (CANDIDATES.size() > cfg.maxEntityShadowCasters) {
                CANDIDATES.sort(Comparator.comparingDouble(e -> e.distanceToSqr(camPos)));
                renderList = CANDIDATES.subList(0, cfg.maxEntityShadowCasters);
            }

            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            Vector3f lightEye = nearCascade.lightEye();
            POSE_STACK.pushPose();
            POSE_STACK.last().pose().set(nearCascade.lightViewRotation());

            SAVED_PROJECTION.set(RenderSystem.getProjectionMatrix());
            VertexSorting savedSorting = RenderSystem.getVertexSorting();

            entityShadowPassActive = true;
            boolean prevShadows = mc.options.entityShadows().get();
            try {
                RenderSystem.setProjectionMatrix(nearCascade.lightProjection(), VertexSorting.ORTHOGRAPHIC_Z);
                dispatcher.setRenderShadow(false);
                for (Entity entity : renderList) {
                    double ex = entity.xOld + (entity.getX() - entity.xOld) * partialTick - lightEye.x;
                    double ey = entity.yOld + (entity.getY() - entity.yOld) * partialTick - lightEye.y;
                    double ez = entity.zOld + (entity.getZ() - entity.zOld) * partialTick - lightEye.z;
                    dispatcher.render(
                            entity,
                            ex, ey, ez,
                            entity.getViewYRot(partialTick),
                            partialTick,
                            POSE_STACK,
                            bufferSource,
                            LightTexture.FULL_BRIGHT
                    );
                }
                bufferSource.endBatch();
            } catch (Throwable ignored) {
                // Gracefully skip if a modded entity renderer dislikes depth-only pass
            } finally {
                POSE_STACK.popPose();
                RenderSystem.setProjectionMatrix(SAVED_PROJECTION, savedSorting);
                dispatcher.setRenderShadow(prevShadows);
                entityShadowPassActive = false;
            }
        } finally {
            CANDIDATES.clear();
        }
    }
}
