package dev.lhlux.render;

import dev.lhlux.LivingHorizonsLuxMod;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Probes active OpenGL driver capabilities once on the render thread so NVIDIA and AMD GPUs both use
 * optimal Hardware PCF, 32F depth targets, and OpenGL 4.3 Compute + SSBO paths with safe GL 3.3 fallbacks.
 */
public final class GpuCapabilities {
    private static volatile boolean probed;
    private static String vendor = "";
    private static String renderer = "";
    private static boolean nvidia;
    private static boolean amd;
    private static boolean computeAndSsbo;
    private static boolean depth32f;

    private GpuCapabilities() {
    }

    private static void ensureProbed() {
        if (probed) {
            return;
        }
        synchronized (GpuCapabilities.class) {
            if (probed) {
                return;
            }
            try {
                GLCapabilities caps = GL.getCapabilities();
                if (caps != null) {
                    String v = GL11.glGetString(GL11.GL_VENDOR);
                    String r = GL11.glGetString(GL11.GL_RENDERER);
                    vendor = v != null ? v : "";
                    renderer = r != null ? r : "";
                    String combined = (vendor + " " + renderer).toLowerCase(Locale.ROOT);
                    nvidia = combined.contains("nvidia");
                    amd = combined.contains("amd") || combined.contains("ati") || combined.contains("radeon");
                    computeAndSsbo = caps.OpenGL43 || (caps.GL_ARB_compute_shader && caps.GL_ARB_shader_storage_buffer_object);
                    depth32f = caps.OpenGL30;
                    LivingHorizonsLuxMod.LOGGER.info(
                            "Detected GPU: {} ({}) | Tag={} | Compute+SSBO={} | Depth32F={}",
                            renderer, vendor, shortTagInternal(), computeAndSsbo, depth32f
                    );
                }
            } catch (Throwable ignored) {
                // Safe no-op outside an active OpenGL context (e.g. unit tests)
            }
            probed = true;
        }
    }

    public static boolean isNvidia() {
        ensureProbed();
        return nvidia;
    }

    public static boolean isAmd() {
        ensureProbed();
        return amd;
    }

    public static boolean supportsComputeAndSsbo() {
        ensureProbed();
        return computeAndSsbo;
    }

    public static boolean supportsDepth32f() {
        ensureProbed();
        return depth32f;
    }

    public static String vendor() {
        ensureProbed();
        return vendor;
    }

    public static String renderer() {
        ensureProbed();
        return renderer;
    }

    public static String shortTag() {
        ensureProbed();
        return shortTagInternal();
    }

    private static String shortTagInternal() {
        String prefix = nvidia ? "NV-" : (amd ? "AMD-" : "");
        return prefix + (computeAndSsbo ? "GL43" : "GL33");
    }
}
