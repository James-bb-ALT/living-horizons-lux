package dev.lhlux.shadows;

import org.joml.Vector3f;

/**
 * Staggered shadow cascade update scheduler so neither cascade rebuilds unnecessarily every frame.
 */
public final class ShadowCadenceCache {
    private final Vector3f lastNearCam = new Vector3f();
    private final Vector3f lastFarCam = new Vector3f();
    private final Vector3f lastNearSunDir = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f lastFarSunDir = new Vector3f(0.0f, 1.0f, 0.0f);

    private boolean hasNear;
    private boolean hasFar;
    private int nearFrameCounter;
    private int farFrameCounter;

    public boolean shouldUpdateNear(Vector3f camPos, Vector3f sunDir) {
        if (!hasNear) {
            return true;
        }
        nearFrameCounter++;
        float camMoveSq = lastNearCam.distanceSquared(camPos);
        if (camMoveSq > 1.0f) {
            return true;
        }
        float sunDot = lastNearSunDir.dot(sunDir);
        if (nearFrameCounter >= 2 && (camMoveSq > 0.04f || sunDot < 0.99995f)) {
            return true;
        }
        // Periodic refresh for animated entity shadows while stationary (every 4 frames)
        return nearFrameCounter >= 4;
    }

    public boolean shouldUpdateFar(Vector3f camPos, Vector3f sunDir, int intervalFrames) {
        if (!hasFar) {
            return true;
        }
        int safeInterval = Math.max(1, intervalFrames);
        farFrameCounter++;
        if (farFrameCounter >= safeInterval) {
            return true;
        }
        float camMoveSq = lastFarCam.distanceSquared(camPos);
        float sunDot = lastFarSunDir.dot(sunDir);
        return camMoveSq > 36.0f || sunDot < 0.9995f;
    }

    public void markNearUpdated(Vector3f camPos, Vector3f sunDir) {
        lastNearCam.set(camPos);
        lastNearSunDir.set(sunDir);
        nearFrameCounter = 0;
        hasNear = true;
    }

    public void markFarUpdated(Vector3f camPos, Vector3f sunDir) {
        lastFarCam.set(camPos);
        lastFarSunDir.set(sunDir);
        farFrameCounter = 0;
        hasFar = true;
    }

    public void reset() {
        hasNear = false;
        hasFar = false;
        nearFrameCounter = 0;
        farFrameCounter = 0;
    }
}
