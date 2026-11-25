package dev.lhlux.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Safely gates Embeddium and Living Horizons mixins so the addon never crashes if an optional mod is absent.
 */
public final class LivingHorizonsLuxMixinPlugin implements IMixinConfigPlugin {
    private boolean hasEmbeddium;
    private boolean hasLivingHorizons;
    private boolean hasLhOceanWaves;
    private boolean hasLhShoreline;

    @Override
    public void onLoad(String mixinPackage) {
        ClassLoader loader = LivingHorizonsLuxMixinPlugin.class.getClassLoader();
        hasEmbeddium = loader.getResource("me/jellysquid/mods/sodium/client/SodiumClientMod.class") != null;
        hasLivingHorizons = loader.getResource("dev/thekryzzz/livinghorizons/LivingHorizons.class") != null;
        hasLhOceanWaves = loader.getResource("dev/thekryzzz/livinghorizons/client/ripple/OceanWaves.class") != null;
        hasLhShoreline = loader.getResource("dev/thekryzzz/livinghorizons/client/ripple/Shoreline.class") != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("dev.lhlux.mixin.embeddium.")) {
            return hasEmbeddium;
        }
        if ("dev.lhlux.mixin.lh.LivingHorizonsOceanWavesMixin".equals(mixinClassName)) {
            return hasLivingHorizons && hasLhOceanWaves;
        }
        if ("dev.lhlux.mixin.lh.LivingHorizonsShorelineMixin".equals(mixinClassName)) {
            return hasLivingHorizons && hasLhShoreline;
        }
        if (mixinClassName.startsWith("dev.lhlux.mixin.lh.")) {
            return hasLivingHorizons;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
