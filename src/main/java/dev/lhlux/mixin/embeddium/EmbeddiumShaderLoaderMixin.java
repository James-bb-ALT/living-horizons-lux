package dev.lhlux.mixin.embeddium;

import dev.lhlux.shader.EmbeddiumShaderPatcher;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compositional shader patcher running at priority 1200 (@At("RETURN")), AFTER Living Horizons'
 * SodiumShaderLoaderMixin, so Living Horizons' swaying plant/water code stays intact.
 */
@Mixin(value = ShaderLoader.class, remap = false, priority = 1200)
public abstract class EmbeddiumShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void lhlux$patchTerrainShaders(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        String patched = EmbeddiumShaderPatcher.patchShaderSource(name, current);
        if (!patched.equals(current)) {
            cir.setReturnValue(patched);
        }
    }
}
