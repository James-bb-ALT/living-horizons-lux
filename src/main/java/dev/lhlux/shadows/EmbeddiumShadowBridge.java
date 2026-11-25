package dev.lhlux.shadows;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.mixin.embeddium.RenderSectionManagerAccessor;
import dev.lhlux.mixin.embeddium.SodiumWorldRendererAccessor;
import dev.lhlux.render.FrameMatrixCache;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Renders Embeddium terrain geometry directly into shadow cascade depth FBOs.
 */
public final class EmbeddiumShadowBridge {
    private final ShadowSectionLists.Cache nearCache = new ShadowSectionLists.Cache();
    private final ShadowSectionLists.Cache farCache = new ShadowSectionLists.Cache();
    private int frameCounter;

    public boolean isAvailable() {
        return SodiumWorldRenderer.instanceNullable() != null;
    }

    public ShadowSectionLists.BuildResult prepareRenderLists(
            ShadowCascade nearCascade,
            boolean buildNear,
            ShadowCascade farCascade,
            boolean buildFar
    ) {
        SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
        if (worldRenderer == null) {
            return null;
        }
        RenderSectionManager manager = ((SodiumWorldRendererAccessor) worldRenderer).lhlux$getRenderSectionManager();
        if (manager == null) {
            return null;
        }
        RenderSectionManagerAccessor accessor = (RenderSectionManagerAccessor) manager;
        return ShadowSectionLists.build(
                accessor.lhlux$getChunkRenderer(),
                accessor.lhlux$getSectionByPosition(),
                nearCascade, buildNear,
                farCascade, buildFar,
                ++frameCounter,
                nearCache,
                farCache,
                FrameMatrixCache.get().cameraPos()
        );
    }

    public void renderCascade(ShadowCascade cascade, ChunkRenderer renderer, ShadowSectionLists lists) {
        if (renderer == null || lists == null || lists.regionCount() == 0) {
            return;
        }

        Vector3f eye = cascade.lightEye();
        ChunkRenderMatrices matrices = new ChunkRenderMatrices(cascade.lightProjection(), cascade.lightViewRotation());
        CameraTransform camera = new CameraTransform(eye.x, eye.y, eye.z);

        boolean prevBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
        SodiumClientMod.options().performance.useBlockFaceCulling = false;

        RenderDevice.enterManagedCode();
        CommandList commandList = null;
        try {
            commandList = RenderDevice.INSTANCE.createCommandList();
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();

            renderer.render(matrices, commandList, lists, DefaultTerrainRenderPasses.SOLID, camera);
            renderer.render(matrices, commandList, lists, DefaultTerrainRenderPasses.CUTOUT, camera);
        } finally {
            SodiumClientMod.options().performance.useBlockFaceCulling = prevBlockFaceCulling;
            GlStateManager._colorMask(true, true, true, true);
            if (commandList != null) {
                commandList.close();
            }
            RenderDevice.exitManagedCode();
        }
    }

    public void clearCaches() {
        nearCache.clear();
        farCache.clear();
    }
}
