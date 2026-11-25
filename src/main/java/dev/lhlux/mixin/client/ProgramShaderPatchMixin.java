package dev.lhlux.mixin.client;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import dev.lhlux.LivingHorizonsLuxMod;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.shader.LivingHorizonsWorldLightShaderPatcher;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Patches Living Horizons' post-processing world_light fragment shader at load time when Wavify
 * compatibility is active so sun/moon glitter renders on top of 3D wave geometry.
 */
@Mixin(Program.class)
public abstract class ProgramShaderPatchMixin {
    @ModifyVariable(
            method = "compileShaderInternal",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private static InputStream lhlux$patchWorldLightShaderForWavify(
            InputStream stream,
            Program.Type type,
            String name,
            InputStream ignoredStream,
            String sourceName,
            GlslPreprocessor preprocessor
    ) {
        if (stream == null
                || type != Program.Type.FRAGMENT
                || !"livinghorizons_world_light".equals(name)
                || !LuxAddonConfig.get().wavifyCompatEnabled
                || !ModCompat.isWavifyLoaded()) {
            return stream;
        }
        try {
            String src = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String patched = LivingHorizonsWorldLightShaderPatcher.patchForWavify(src);
            if (!patched.equals(src)) {
                LivingHorizonsLuxMod.LOGGER.info("Patched livinghorizons_world_light.fsh for Wavify 3D wave glitter compatibility");
            }
            return new ByteArrayInputStream(patched.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.warn("Failed to patch livinghorizons_world_light.fsh for Wavify", t);
            return stream;
        }
    }
}
