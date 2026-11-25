package dev.lhlux.shadows;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowCadenceCacheTest {
    @Test
    void nearAndFarCascadesThrottleTinyMovesAndKeepIndependentSunDirections() {
        ShadowCadenceCache cache = new ShadowCadenceCache();
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);
        Vector3f sun = new Vector3f(0.3f, 0.9f, 0.2f).normalize();

        // First frame always updates both cascades
        assertTrue(cache.shouldUpdateNear(cam, sun));
        assertTrue(cache.shouldUpdateFar(cam, sun, 4));
        cache.markNearUpdated(cam, sun);
        cache.markFarUpdated(cam, sun);

        // Immediately next frame with tiny camera jitter (< 1 block): near & far skip
        Vector3f tinyCam = new Vector3f(0.05f, 64.0f, 0.05f);
        assertFalse(cache.shouldUpdateNear(tinyCam, sun));
        assertFalse(cache.shouldUpdateFar(tinyCam, sun, 4));

        // Fast camera move (> 1 block) triggers immediate near update without resetting far sun state
        Vector3f fastCam = new Vector3f(1.5f, 64.0f, 0.0f);
        assertTrue(cache.shouldUpdateNear(fastCam, sun));
        cache.markNearUpdated(fastCam, sun);

        // Remaining far frames (counter was 1 before, now 2 -> false, then 3 -> false, then 4 -> true)
        assertFalse(cache.shouldUpdateFar(fastCam, sun, 4));
        assertFalse(cache.shouldUpdateFar(fastCam, sun, 4));
        assertTrue(cache.shouldUpdateFar(fastCam, sun, 4));
    }
}
