package dev.lhlux.mixin.embeddium;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.render.FrameMatrixCache;
import dev.lhlux.shadows.CascadedShadowEngine;
import java.util.function.IntFunction;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniform;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds and uploads CSM shadow textures/matrices and RGSS AA flags to Embeddium terrain shaders.
 */
@Mixin(value = ChunkShaderInterface.class, remap = false)
public abstract class EmbeddiumChunkShaderInterfaceMixin {
    @Unique private GlUniformInt lhlux$nearShadow;
    @Unique private GlUniformInt lhlux$farShadow;
    @Unique private GlUniformMatrix4f lhlux$nearMat;
    @Unique private GlUniformMatrix4f lhlux$farMat;
    @Unique private GlUniformFloat4v lhlux$cascadeParams;
    @Unique private GlUniformFloat3v lhlux$sunDir;
    @Unique private GlUniformInt lhlux$enabled;
    @Unique private GlUniformInt lhlux$pcfMode;
    @Unique private GlUniformInt lhlux$rgssEnabled;
    @Unique private GlUniformInt lhlux$shadowPass;
    @Unique private final float[] lhlux$cascadeBuf = new float[4];

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lhlux$bindShadowUniforms(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        this.lhlux$nearShadow = lhlux$bindSafe(context, "u_LhLuxNearShadow", GlUniformInt::new);
        this.lhlux$farShadow = lhlux$bindSafe(context, "u_LhLuxFarShadow", GlUniformInt::new);
        this.lhlux$nearMat = lhlux$bindSafe(context, "u_LhLuxNearMat", GlUniformMatrix4f::new);
        this.lhlux$farMat = lhlux$bindSafe(context, "u_LhLuxFarMat", GlUniformMatrix4f::new);
        this.lhlux$cascadeParams = lhlux$bindSafe(context, "u_LhLuxCascadeParams", GlUniformFloat4v::new);
        this.lhlux$sunDir = lhlux$bindSafe(context, "u_LhLuxSunDir", GlUniformFloat3v::new);
        this.lhlux$enabled = lhlux$bindSafe(context, "u_LhLuxEnabled", GlUniformInt::new);
        this.lhlux$pcfMode = lhlux$bindSafe(context, "u_LhLuxPcfMode", GlUniformInt::new);
        this.lhlux$rgssEnabled = lhlux$bindSafe(context, "u_LhLuxRgssEnabled", GlUniformInt::new);
        this.lhlux$shadowPass = lhlux$bindSafe(context, "u_LhLuxShadowPass", GlUniformInt::new);
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void lhlux$uploadShadowUniforms(CallbackInfo ci) {
        if (this.lhlux$enabled == null) {
            return;
        }
        CascadedShadowEngine csm = CascadedShadowEngine.get();
        LuxAddonConfig cfg = LuxAddonConfig.get();

        boolean inShadowPass = csm.isShadowPassActive();
        if (this.lhlux$shadowPass != null) {
            this.lhlux$shadowPass.setInt(inShadowPass ? 1 : 0);
        }
        if (this.lhlux$rgssEnabled != null) {
            this.lhlux$rgssEnabled.setInt(!inShadowPass && cfg.rgssAntialiasingEnabled ? 1 : 0);
        }

        boolean active = !inShadowPass && csm.isActive();
        this.lhlux$enabled.setInt(active ? 1 : 0);
        if (!active) {
            return;
        }

        // Bind near and far depth maps on high texture units (6 & 7) to avoid clashing with block atlas/lightmap
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 6);
        GlStateManager._bindTexture(csm.nearDepthTexture());
        if (this.lhlux$nearShadow != null) {
            this.lhlux$nearShadow.setInt(6);
        }

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 7);
        GlStateManager._bindTexture(csm.farDepthTexture());
        if (this.lhlux$farShadow != null) {
            this.lhlux$farShadow.setInt(7);
        }

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        if (this.lhlux$nearMat != null) {
            this.lhlux$nearMat.set(csm.nearWorldRelMatrix());
        }
        if (this.lhlux$farMat != null) {
            this.lhlux$farMat.set(csm.farWorldRelMatrix());
        }
        if (this.lhlux$cascadeParams != null) {
            this.lhlux$cascadeBuf[0] = csm.nearRadius();
            this.lhlux$cascadeBuf[1] = csm.farRadius();
            this.lhlux$cascadeBuf[2] = csm.texelSize();
            this.lhlux$cascadeBuf[3] = csm.effectiveShadowStrength();
            this.lhlux$cascadeParams.set(this.lhlux$cascadeBuf);
        }
        if (this.lhlux$sunDir != null) {
            Vector3f sun = FrameMatrixCache.get().lightDirection();
            this.lhlux$sunDir.set(sun.x, sun.y, sun.z);
        }
        if (this.lhlux$pcfMode != null) {
            this.lhlux$pcfMode.setInt(cfg.pcfFilterMode);
        }
    }

    @Unique
    private static <U extends GlUniform<?>> U lhlux$bindSafe(
            ShaderBindingContext context,
            String name,
            IntFunction<U> factory
    ) {
        try {
            return context.bindUniform(name, factory);
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.debug("CSM uniform '{}' not present in shader variant: {}", name, t.getMessage());
            return null;
        }
    }
}
