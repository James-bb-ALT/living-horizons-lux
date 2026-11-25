package dev.lhlux.framegen;

import dev.lhlux.config.LuxAddonConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenCadenceControllerTest {
    @Test
    void enforcesTfr2xCadenceAndInvalidatesOnCameraSnapOrScreen() {
        FrameGenCadenceController ctrl = new FrameGenCadenceController();
        LuxAddonConfig cfg = new LuxAddonConfig();
        cfg.frameGenMode = FrameGenMode.TFR_2X;
        Object level = new Object();
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 0f, 0f, 1_000_000L));
        ctrl.recordRealFrameCompleted(level, 1920, 1080, cam, 0f, 0f, 16_000_000L);

        assertEquals(FrameGenCadenceController.Decision.SYNTHETIC_REPROJECT,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 2f, 1f, 9_000_000L));

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 3f, 1f, 17_000_000L));
        ctrl.recordRealFrameCompleted(level, 1920, 1080, cam, 3f, 1f, 16_000_000L);

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 45f, 1f, 25_000_000L));
        ctrl.recordRealFrameCompleted(level, 1920, 1080, cam, 45f, 1f, 16_000_000L);

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, true, false, 1920, 1080, cam, 45f, 1f, 33_000_000L));
    }

    @Test
    void detectsPureRollSnapFromFlightModsAndDynamicFpsIdleGap() {
        FrameGenCadenceController ctrl = new FrameGenCadenceController();
        LuxAddonConfig cfg = new LuxAddonConfig();
        cfg.frameGenMode = FrameGenMode.TFR_2X;
        Object level = new Object();
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);
        Matrix4f baseRot = new Matrix4f().identity();
        Matrix4f rolled35Deg = new Matrix4f().rotationZ((float) Math.toRadians(35.0));

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 0f, 0f, baseRot, 10_000_000L));
        ctrl.recordRealFrameCompleted(level, 1920, 1080, cam, 0f, 0f, baseRot, 16_000_000L);

        // Even with 0 yaw/pitch change, a 35-degree Z-axis roll snap (Fauna Engine / Do A Barrel Roll) forces REAL
        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 0f, 0f, rolled35Deg, 18_000_000L));
        ctrl.recordRealFrameCompleted(level, 1920, 1080, cam, 0f, 0f, rolled35Deg, 16_000_000L);

        // Small rotation within threshold proceeds to synthetic
        Matrix4f rolled37Deg = new Matrix4f().rotationZ((float) Math.toRadians(37.0));
        assertEquals(FrameGenCadenceController.Decision.SYNTHETIC_REPROJECT,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 0f, 0f, rolled37Deg, 26_000_000L));

        // Simulate Dynamic FPS throttling gap > 250 ms -> forces REAL_AND_CAPTURE
        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1920, 1080, cam, 0f, 0f, rolled37Deg, 500_000_000L));
    }

    @Test
    void interpolated2xProducesInterpolatedDecision() {
        FrameGenCadenceController ctrl = new FrameGenCadenceController();
        LuxAddonConfig cfg = new LuxAddonConfig();
        cfg.frameGenMode = FrameGenMode.INTERPOLATED_2X;
        Object level = new Object();
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);

        assertEquals(FrameGenCadenceController.Decision.REAL_AND_CAPTURE,
                ctrl.decideNextFrame(cfg, level, false, false, 1280, 720, cam, 0f, 0f, 1_000_000L));
        ctrl.recordRealFrameCompleted(level, 1280, 720, cam, 0f, 0f, 16_000_000L);

        assertEquals(FrameGenCadenceController.Decision.SYNTHETIC_INTERPOLATE,
                ctrl.decideNextFrame(cfg, level, false, false, 1280, 720, cam, 1f, 0f, 9_000_000L));
    }
}
