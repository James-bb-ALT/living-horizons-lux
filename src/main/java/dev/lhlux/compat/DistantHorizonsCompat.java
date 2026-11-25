package dev.lhlux.compat;

import dev.lhlux.LivingHorizonsLuxMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional reflective bridge to Distant Horizons (2.x / 3.3.3+) so we never hard-require DH at compile or runtime.
 */
public final class DistantHorizonsCompat {
    private static volatile boolean probed;
    private static boolean apiAvailable;
    private static Object dhGraphicsConfig;
    private static Method worldRenderEnabledMethod;

    private DistantHorizonsCompat() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        synchronized (DistantHorizonsCompat.class) {
            if (probed) {
                return;
            }
            // Check DH public DhApi first
            try {
                Class<?> dhApiClass = Class.forName("com.seibel.distanthorizons.api.DhApi");
                Field delayedField = dhApiClass.getField("Delayed");
                Object delayed = delayedField.get(null);
                if (delayed != null) {
                    try {
                        Field configsField = delayed.getClass().getField("configs");
                        Object configs = configsField.get(delayed);
                        if (configs != null) {
                            Method graphicsMethod = configs.getClass().getMethod("graphics");
                            Object graphics = graphicsMethod.invoke(configs);
                            if (graphics != null) {
                                worldRenderEnabledMethod = graphics.getClass().getMethod("renderingEnabled");
                                dhGraphicsConfig = graphics;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                apiAvailable = true;
            } catch (Throwable t) {
                // Fallback: DH 3.3.3 internal ClientApi presence check
                try {
                    Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi", false, DistantHorizonsCompat.class.getClassLoader());
                    apiAvailable = true;
                } catch (Throwable t2) {
                    LivingHorizonsLuxMod.LOGGER.debug("Distant Horizons API not present: {}", t.getMessage());
                }
            }
            probed = true;
        }
    }

    public static boolean isDistantHorizonsApiAvailable() {
        probe();
        return apiAvailable;
    }

    public static boolean isDistantHorizonsRenderingActive() {
        if (!ModCompat.isDistantHorizonsLoaded()) {
            return false;
        }
        probe();
        if (dhGraphicsConfig != null && worldRenderEnabledMethod != null) {
            try {
                Object res = worldRenderEnabledMethod.invoke(dhGraphicsConfig);
                if (res != null) {
                    Method getValue = res.getClass().getMethod("getValue");
                    Object val = getValue.invoke(res);
                    if (val instanceof Boolean b) {
                        return b;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }
}
