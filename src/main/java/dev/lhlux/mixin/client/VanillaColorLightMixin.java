package dev.lhlux.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lhlux.coloredlight.ColoredLightEngine;
import dev.lhlux.compat.ModCompat;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Fallback per-block RGB colored lighting for Vanilla rendering when Embeddium is absent.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class VanillaColorLightMixin {
    @Unique
    private final float[] lhlux$tintScratch = new float[3];

    @ModifyVariable(
            method = "putQuadData",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private float lhlux$tintRed(
            float r,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            VertexConsumer consumer,
            PoseStack.Pose pose,
            BakedQuad quad,
            float b0, float b1, float b2, float b3,
            int l0, int l1, int l2, int l3,
            int overlay
    ) {
        if (ModCompat.isEmbeddiumLoaded() || !ColoredLightEngine.get().isActive()) {
            return r;
        }
        int blockLight = Math.max(
                Math.max((l0 >>> 4) & 0xF, (l1 >>> 4) & 0xF),
                Math.max((l2 >>> 4) & 0xF, (l3 >>> 4) & 0xF)
        );
        int skyLight = Math.max(
                Math.max((l0 >>> 20) & 0xF, (l1 >>> 20) & 0xF),
                Math.max((l2 >>> 20) & 0xF, (l3 >>> 20) & 0xF)
        );
        ColoredLightEngine.get().sampleTintMultiplier(level, pos, blockLight, skyLight, lhlux$tintScratch);
        return Math.min(1.0f, r * lhlux$tintScratch[0]);
    }
}
