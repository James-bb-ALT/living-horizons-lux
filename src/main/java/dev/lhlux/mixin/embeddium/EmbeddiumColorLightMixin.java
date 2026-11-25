package dev.lhlux.mixin.embeddium;

import dev.lhlux.coloredlight.ColoredLightEngine;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies per-block RGB colored lighting to Embeddium chunk vertices while preserving the alpha byte
 * (which Living Horizons uses for plant/water sway markers).
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false, priority = 1200)
public abstract class EmbeddiumColorLightMixin {
    @Unique
    private BlockRenderContext lhlux$context;

    @Inject(
            method = "renderModel(Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext;Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V",
            at = @At("HEAD"),
            require = 0
    )
    private void lhlux$captureContext(BlockRenderContext context, ChunkBuildBuffers buffers, CallbackInfo ci) {
        this.lhlux$context = context;
    }

    @ModifyArg(
            method = "writeGeometry",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;)V"
            ),
            index = 0,
            require = 0
    )
    private ChunkVertexEncoder.Vertex[] lhlux$tintVertices(ChunkVertexEncoder.Vertex[] vertices) {
        BlockRenderContext ctx = this.lhlux$context;
        if (ctx == null || !ColoredLightEngine.get().isActive()) {
            return vertices;
        }
        int count = Math.min(vertices.length, 4);
        for (int i = 0; i < count; i++) {
            ChunkVertexEncoder.Vertex v = vertices[i];
            int origAlpha = v.color & 0xFF000000;
            int tinted = ColoredLightEngine.packClientBlockTint(ctx.localSlice(), ctx.pos(), v.light, v.color);
            v.color = origAlpha | (tinted & 0x00FFFFFF);
        }
        return vertices;
    }
}
