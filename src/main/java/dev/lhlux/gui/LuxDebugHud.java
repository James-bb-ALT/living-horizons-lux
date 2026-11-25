package dev.lhlux.gui;

import dev.lhlux.coloredlight.ColoredLightEngine;
import dev.lhlux.coloredlight.GpuDynamicColoredLightPass;
import dev.lhlux.compat.DistantHorizonsCompat;
import dev.lhlux.compat.ModCompat;
import dev.lhlux.config.LuxAddonConfig;
import dev.lhlux.framegen.FrameGenSystem;
import dev.lhlux.post.GpuWaterCausticsPass;
import dev.lhlux.post.SelectiveBloomPass;
import dev.lhlux.post.SpatialResolutionScaler;
import dev.lhlux.render.GpuCapabilities;
import dev.lhlux.shadows.CascadedShadowEngine;

/**
 * Builds the concise F3 debug line showing active Living Horizons: Lux GPU & optimization subsystems.
 */
public final class LuxDebugHud {
    private LuxDebugHud() {
    }

    public static String buildLine() {
        LuxAddonConfig cfg = LuxAddonConfig.get();
        StringBuilder sb = new StringBuilder(160);
        sb.append("[LH:Lux ").append(GpuCapabilities.shortTag()).append("] ");
        sb.append("CSM:").append(CascadedShadowEngine.get().isActive() ? "HW-PCF(" + cfg.shadowResolution + ")" : "OFF");

        GpuDynamicColoredLightPass gpuCl = GpuDynamicColoredLightPass.get();
        if (ColoredLightEngine.get().isActive() || gpuCl.activeLightCount() > 0) {
            sb.append(" CL:").append(gpuCl.isUsingComputePath() ? "CS(" : "GL33(")
                    .append(gpuCl.activeLightCount()).append(")");
        } else {
            sb.append(" CL:OFF");
        }

        if (GpuWaterCausticsPass.get().isActive()) {
            sb.append(" Caustics:GPU");
        }
        sb.append(" Bloom:").append(SelectiveBloomPass.get().isActive() ? "ON" : "OFF");
        if (cfg.lsrEnabled) {
            if (SpatialResolutionScaler.get().isGuardedByDh()) {
                sb.append(" LSR:DH-Guard");
            } else {
                sb.append(" LSR:").append(cfg.upscalerMode.name())
                        .append("(").append(Math.round(cfg.lsrScale * 100)).append("%)");
            }
        }
        if (cfg.frameGenMode.isEnabled()) {
            int fgFps = FrameGenSystem.get().reportedGeneratedFps();
            sb.append(" FG:").append(cfg.frameGenMode.name());
            if (fgFps > 0) {
                sb.append("(").append(fgFps).append("fps)");
            }
        }
        if (ModCompat.isDistantHorizonsLoaded()) {
            sb.append(" DH:").append(DistantHorizonsCompat.isDistantHorizonsRenderingActive() ? "Compat" : "Idle");
        }
        return sb.toString();
    }
}
