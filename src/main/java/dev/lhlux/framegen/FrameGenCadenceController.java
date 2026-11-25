package dev.lhlux.framegen;

import dev.lhlux.config.LuxAddonConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Pure deterministic cadence & safety invalidation controller for FrameGen.
 * Tracks full 3D view rotation matrices (to detect roll/pitch/yaw snaps from Fauna Engine flight,
 * Do A Barrel Roll, and CameraOverhaul) and frame time gaps (to reset cleanly after Dynamic FPS throttling).
 */
public final class FrameGenCadenceController {
    private static final float DEFAULT_EMA_ALPHA = 0.25f;
    private static final long MAX_FRAME_GAP_NANOS = 250_000_000L; // 250 ms

    private final Vector3f prevCamPos = new Vector3f();
    private final Matrix4f prevViewRot = new Matrix4f();
    private float prevYaw;
    private float prevPitch;
    private int prevWidth = -1;
    private int prevHeight = -1;
    private Object prevLevelIdentity;

    private boolean hasHistory;
    private int syntheticStreak;
    private float emaRealFrameMs = 16.67f;
    private long lastFrameNanos = -1L;
    private int reportedGeneratedFps;

    public enum Decision {
        REAL_AND_CAPTURE,
        SYNTHETIC_REPROJECT,
        SYNTHETIC_INTERPOLATE
    }

    public Decision decideNextFrame(
            LuxAddonConfig cfg,
            Object levelIdentity,
            boolean screenOpen,
            boolean externalShadersActive,
            int viewportWidth,
            int viewportHeight,
            Vector3f camPos,
            float camYaw,
            float camPitch,
            long nowNanos
    ) {
        return decideNextFrame(
                cfg, levelIdentity, screenOpen, externalShadersActive,
                viewportWidth, viewportHeight, camPos, camYaw, camPitch, null, nowNanos
        );
    }

    public Decision decideNextFrame(
            LuxAddonConfig cfg,
            Object levelIdentity,
            boolean screenOpen,
            boolean externalShadersActive,
            int viewportWidth,
            int viewportHeight,
            Vector3f camPos,
            float camYaw,
            float camPitch,
            Matrix4f viewRotation,
            long nowNanos
    ) {
        if (!cfg.frameGenMode.isEnabled()
                || levelIdentity == null
                || screenOpen
                || externalShadersActive
                || viewportWidth < 16
                || viewportHeight < 16) {
            resetHistory(levelIdentity, viewportWidth, viewportHeight, camPos, camYaw, camPitch, viewRotation, nowNanos);
            return Decision.REAL_AND_CAPTURE;
        }

        if (!hasHistory
                || prevLevelIdentity != levelIdentity
                || prevWidth != viewportWidth
                || prevHeight != viewportHeight) {
            resetHistory(levelIdentity, viewportWidth, viewportHeight, camPos, camYaw, camPitch, viewRotation, nowNanos);
            return Decision.REAL_AND_CAPTURE;
        }

        // Dynamic FPS / pause gap guard: discard stale frame history after prolonged idle intervals
        if (lastFrameNanos > 0L && (nowNanos - lastFrameNanos) > MAX_FRAME_GAP_NANOS) {
            resetHistory(levelIdentity, viewportWidth, viewportHeight, camPos, camYaw, camPitch, viewRotation, nowNanos);
            return Decision.REAL_AND_CAPTURE;
        }

        // Camera snap / teleport guard against ghosting (checks 3D rotation matrix if available, plus Euler fallback)
        float distSq = prevCamPos.distanceSquared(camPos);
        float maxTeleportSq = cfg.frameGenMaxTeleportBlocks * cfg.frameGenMaxTeleportBlocks;
        float rotDeg;
        if (viewRotation != null) {
            rotDeg = matrixRotationAngleDegrees(viewRotation, prevViewRot);
        } else {
            float yawDelta = shortestAngleDelta(camYaw, prevYaw);
            float pitchDelta = Math.abs(camPitch - prevPitch);
            rotDeg = Math.max(yawDelta, pitchDelta);
        }
        boolean snapped = distSq > maxTeleportSq || rotDeg > cfg.frameGenMaxCameraSnapDegrees;
        if (snapped) {
            resetHistory(levelIdentity, viewportWidth, viewportHeight, camPos, camYaw, camPitch, viewRotation, nowNanos);
            return Decision.REAL_AND_CAPTURE;
        }

        int maxSyntheticStreak = computeMaxSyntheticStreak(cfg, distSq, rotDeg);
        if (syntheticStreak < maxSyntheticStreak) {
            syntheticStreak++;
            updateFpsEstimate(nowNanos);
            if (cfg.frameGenMode == FrameGenMode.INTERPOLATED_2X) {
                return Decision.SYNTHETIC_INTERPOLATE;
            }
            return Decision.SYNTHETIC_REPROJECT;
        }

        syntheticStreak = 0;
        updateFpsEstimate(nowNanos);
        return Decision.REAL_AND_CAPTURE;
    }

    public void recordRealFrameCompleted(
            Object levelIdentity,
            int viewportWidth,
            int viewportHeight,
            Vector3f camPos,
            float camYaw,
            float camPitch,
            long durationNanos
    ) {
        recordRealFrameCompleted(levelIdentity, viewportWidth, viewportHeight, camPos, camYaw, camPitch, null, durationNanos);
    }

    public void recordRealFrameCompleted(
            Object levelIdentity,
            int viewportWidth,
            int viewportHeight,
            Vector3f camPos,
            float camYaw,
            float camPitch,
            Matrix4f viewRotation,
            long durationNanos
    ) {
        this.prevLevelIdentity = levelIdentity;
        this.prevWidth = viewportWidth;
        this.prevHeight = viewportHeight;
        this.prevCamPos.set(camPos);
        this.prevYaw = camYaw;
        this.prevPitch = camPitch;
        if (viewRotation != null) {
            this.prevViewRot.set(viewRotation);
        } else {
            this.prevViewRot.identity();
        }
        this.hasHistory = true;

        float ms = Math.max(0.5f, durationNanos / 1_000_000.0f);
        this.emaRealFrameMs = this.emaRealFrameMs * (1.0f - DEFAULT_EMA_ALPHA) + ms * DEFAULT_EMA_ALPHA;
    }

    public void reset() {
        hasHistory = false;
        syntheticStreak = 0;
        prevLevelIdentity = null;
        prevViewRot.identity();
        lastFrameNanos = -1L;
        reportedGeneratedFps = 0;
    }

    public boolean hasHistory() {
        return hasHistory;
    }

    public int reportedGeneratedFps() {
        return reportedGeneratedFps;
    }

    private int computeMaxSyntheticStreak(LuxAddonConfig cfg, float distSq, float rotDeg) {
        int base = switch (cfg.frameGenMode) {
            case OFF -> 0;
            case TFR_2X, INTERPOLATED_2X -> 1;
            case TFR_3X -> 2;
            case TFR_ADAPTIVE -> {
                float targetFrameMs = 1000.0f / Math.max(30, cfg.frameGenTargetFps);
                if (emaRealFrameMs <= targetFrameMs * 1.05f) {
                    yield 0;
                } else if (emaRealFrameMs <= targetFrameMs * 2.1f) {
                    yield 1;
                } else {
                    yield 2;
                }
            }
        };
        // Fast flying (> 0.5 blocks/frame) or rapid panning (> 4.5 deg/frame) caps streak at 1 to stop flicker
        if (base > 1 && (distSq > 0.25f || rotDeg > 4.5f)) {
            return 1;
        }
        return base;
    }

    private void resetHistory(
            Object levelIdentity,
            int w,
            int h,
            Vector3f camPos,
            float camYaw,
            float camPitch,
            Matrix4f viewRotation,
            long nowNanos
    ) {
        this.hasHistory = false;
        this.syntheticStreak = 0;
        this.prevLevelIdentity = levelIdentity;
        this.prevWidth = w;
        this.prevHeight = h;
        this.prevCamPos.set(camPos);
        this.prevYaw = camYaw;
        this.prevPitch = camPitch;
        if (viewRotation != null) {
            this.prevViewRot.set(viewRotation);
        } else {
            this.prevViewRot.identity();
        }
        this.lastFrameNanos = nowNanos;
    }

    private void updateFpsEstimate(long nowNanos) {
        if (lastFrameNanos > 0L && nowNanos > lastFrameNanos) {
            double dtSec = (nowNanos - lastFrameNanos) / 1_000_000_000.0;
            if (dtSec > 0.0001 && dtSec < 1.0) {
                int instFps = (int) Math.round(1.0 / dtSec);
                reportedGeneratedFps = reportedGeneratedFps == 0
                        ? instFps
                        : Math.round(reportedGeneratedFps * 0.8f + instFps * 0.2f);
            }
        }
        lastFrameNanos = nowNanos;
    }

    private static float matrixRotationAngleDegrees(Matrix4f a, Matrix4f b) {
        // Upper-left 3x3 trace(a * b^T) = sum of element-wise products
        float trace = a.m00() * b.m00() + a.m01() * b.m01() + a.m02() * b.m02()
                + a.m10() * b.m10() + a.m11() * b.m11() + a.m12() * b.m12()
                + a.m20() * b.m20() + a.m21() * b.m21() + a.m22() * b.m22();
        double cosTheta = Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5));
        return (float) Math.toDegrees(Math.acos(cosTheta));
    }

    private static float shortestAngleDelta(float a, float b) {
        float diff = Math.abs(a - b) % 360.0f;
        return diff > 180.0f ? 360.0f - diff : diff;
    }
}
