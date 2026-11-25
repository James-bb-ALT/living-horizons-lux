package dev.lhlux.compat;

import dev.lhlux.LivingHorizonsLuxMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraftforge.fml.ModList;

/**
 * Safe reflective compatibility checks for Living Horizons, Embeddium, Oculus/Iris, and Distant Horizons.
 */
public final class ModCompat {
    private static volatile boolean initialized;
    private static boolean livingHorizonsLoaded;
    private static boolean embeddiumLoaded;
    private static boolean distantHorizonsLoaded;
    private static boolean wavifyLoaded;

    private static Method lhShaderPackActiveMethod;
    private static Method lhConfigGetMethod;
    private static Field lhConfigEnabledField;
    private static Method lhExclusionsActiveMethod;
    private static Object irisApiInstance;
    private static Method irisShadersInUseMethod;

    private ModCompat() {
    }

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        synchronized (ModCompat.class) {
            if (initialized) {
                return;
            }
            ModList modList = ModList.get();
            if (modList != null) {
                livingHorizonsLoaded = modList.isLoaded("livinghorizons");
                embeddiumLoaded = modList.isLoaded("embeddium") || modList.isLoaded("rubidium");
                distantHorizonsLoaded = modList.isLoaded("distanthorizons");
                wavifyLoaded = modList.isLoaded("wavify");
            } else {
                livingHorizonsLoaded = classExists("dev.thekryzzz.livinghorizons.LivingHorizons");
                embeddiumLoaded = classExists("me.jellysquid.mods.sodium.client.SodiumClientMod");
                distantHorizonsLoaded = classExists("com.seibel.distanthorizons.api.DhApi");
                wavifyLoaded = classExists("net.superkat.wavify.Wavify");
            }

            if (livingHorizonsLoaded) {
                try {
                    Class<?> compatClass = Class.forName("dev.thekryzzz.livinghorizons.client.Compat");
                    lhShaderPackActiveMethod = compatClass.getMethod("isShaderPackActive");
                } catch (Throwable t) {
                    LivingHorizonsLuxMod.LOGGER.debug("Could not resolve Living Horizons Compat.isShaderPackActive()", t);
                }
                try {
                    Class<?> cfgClass = Class.forName("dev.thekryzzz.livinghorizons.config.LivingHorizonsConfig");
                    lhConfigGetMethod = cfgClass.getMethod("get");
                    lhConfigEnabledField = cfgClass.getField("enabled");
                } catch (Throwable t) {
                    LivingHorizonsLuxMod.LOGGER.debug("Could not resolve LivingHorizonsConfig.enabled", t);
                }
                try {
                    Class<?> exclClass = Class.forName("dev.thekryzzz.livinghorizons.client.Exclusions");
                    lhExclusionsActiveMethod = exclClass.getMethod("active");
                } catch (Throwable t) {
                    LivingHorizonsLuxMod.LOGGER.debug("Could not resolve Living Horizons Exclusions.active()", t);
                }
            }

            try {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Method getInstance = irisApi.getMethod("getInstance");
                irisApiInstance = getInstance.invoke(null);
                irisShadersInUseMethod = irisApi.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
                try {
                    Class<?> coderIrisApi = Class.forName("net.coderbot.iris.api.v0.IrisApi");
                    Method getInstance = coderIrisApi.getMethod("getInstance");
                    irisApiInstance = getInstance.invoke(null);
                    irisShadersInUseMethod = coderIrisApi.getMethod("isShaderPackInUse");
                } catch (Throwable ignored2) {
                }
            }
            initialized = true;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ModCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isLivingHorizonsLoaded() {
        ensureInit();
        return livingHorizonsLoaded;
    }

    /**
     * Returns true when Living Horizons is enabled and not paused by biome/dimension exclusions
     * (or if Living Horizons classes are absent, e.g. inside isolated unit tests).
     */
    public static boolean isLivingHorizonsActive() {
        ensureInit();
        if (!livingHorizonsLoaded) {
            return true;
        }
        try {
            if (lhConfigGetMethod != null && lhConfigEnabledField != null) {
                Object cfg = lhConfigGetMethod.invoke(null);
                if (cfg != null && !lhConfigEnabledField.getBoolean(cfg)) {
                    return false;
                }
            }
            if (lhExclusionsActiveMethod != null) {
                Object excluded = lhExclusionsActiveMethod.invoke(null);
                if (excluded instanceof Boolean b && b) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isEmbeddiumLoaded() {
        ensureInit();
        return embeddiumLoaded;
    }

    public static boolean isDistantHorizonsLoaded() {
        ensureInit();
        return distantHorizonsLoaded;
    }

    public static boolean isWavifyLoaded() {
        ensureInit();
        return wavifyLoaded;
    }

    public static boolean isExternalShaderPackActive() {
        ensureInit();
        if (lhShaderPackActiveMethod != null) {
            try {
                Object result = lhShaderPackActiveMethod.invoke(null);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (irisApiInstance != null && irisShadersInUseMethod != null) {
            try {
                Object result = irisShadersInUseMethod.invoke(irisApiInstance);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
