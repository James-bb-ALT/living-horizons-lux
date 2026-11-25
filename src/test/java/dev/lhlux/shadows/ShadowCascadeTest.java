package dev.lhlux.shadows;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowCascadeTest {
    @Test
    void texelSnappingKeepsAnchorAlignedToGrid() {
        ShadowCascade cascade = new ShadowCascade("near");
        Vector3f sun = new Vector3f(0.35f, 0.85f, 0.40f).normalize();
        float radius = 48.0f;
        int res = 1536;
        float texelWorld = (2.0f * radius) / res;

        cascade.update(new Vector3f(10.123f, 64.456f, -22.789f), sun, radius, 220.0f, res);
        Vector3f anchor1 = new Vector3f(cascade.snappedAnchor());

        // Move by less than half a texel along world axes -> snapped anchor stays on texel grid
        cascade.update(new Vector3f(10.124f, 64.456f, -22.789f), sun, radius, 220.0f, res);
        Vector3f anchor2 = new Vector3f(cascade.snappedAnchor());

        assertTrue(anchor1.distance(anchor2) <= texelWorld + 1e-4f);
        assertTrue(cascade.isValid());
        assertEquals(1.0f / res, cascade.texelSize(), 1e-6f);

        // Origin (camera relative = 0,0,0) should map inside [0,1] shadow UVZ
        Vector4f uvz = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f).mul(cascade.worldRelativeToLightUvz());
        assertTrue(uvz.x > 0.4f && uvz.x < 0.6f);
        assertTrue(uvz.y > 0.4f && uvz.y < 0.6f);
        assertTrue(uvz.z > 0.0f && uvz.z < 1.0f);
    }

    @Test
    void cadenceCacheStaggersFarCascadeUpdates() {
        ShadowCadenceCache cache = new ShadowCadenceCache();
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);
        Vector3f sun = new Vector3f(0.2f, 0.9f, 0.2f).normalize();

        assertTrue(cache.shouldUpdateNear(cam, sun));
        assertTrue(cache.shouldUpdateFar(cam, sun, 3));

        cache.markNearUpdated(cam, sun);
        cache.markFarUpdated(cam, sun);

        assertFalse(cache.shouldUpdateFar(cam, sun, 3));
        assertFalse(cache.shouldUpdateFar(cam, sun, 3));
        assertTrue(cache.shouldUpdateFar(cam, sun, 3));
    }

    @Test
    void lightViewRotationMatchesLightViewAtCameraRelativeCoordinates() {
        ShadowCascade cascade = new ShadowCascade("near");
        Vector3f cam1 = new Vector3f(120.0f, 70.0f, -40.0f);
        Vector3f sun = new Vector3f(0.4f, 0.85f, 0.3f).normalize();
        cascade.update(cam1, sun, 48.0f, 220.0f, 1536);

        // World point (130, 68, -32) rendered into shadow buffer via (world - lightEye) * lightViewRotation
        Vector3f worldPt = new Vector3f(130.0f, 68.0f, -32.0f);
        Vector3f fromEye = new Vector3f(worldPt).sub(cascade.lightEye());
        Vector4f shadowProjFromBridge = new Vector4f(fromEye, 1.0f)
                .mul(cascade.lightViewRotation())
                .mul(cascade.lightProjection());
        Vector4f shadowProjDirect = new Vector4f(worldPt, 1.0f)
                .mul(cascade.lightView())
                .mul(cascade.lightProjection());
        assertEquals(shadowProjDirect.x, shadowProjFromBridge.x, 1e-4f);
        assertEquals(shadowProjDirect.y, shadowProjFromBridge.y, 1e-4f);
        assertEquals(shadowProjDirect.z, shadowProjFromBridge.z, 1e-4f);

        // Cached shadow lookup from moved cam2 must still project worldPt identically
        Vector3f cam2 = new Vector3f(122.0f, 71.0f, -39.0f);
        Vector3f anchorBefore = new Vector3f(cascade.snappedAnchor());
        cascade.refreshCameraRelativeMatrix(cam2);
        assertEquals(anchorBefore.x, cascade.snappedAnchor().x, 1e-6f);

        Vector4f uvz = new Vector4f(worldPt.x - cam2.x, worldPt.y - cam2.y, worldPt.z - cam2.z, 1.0f)
                .mul(cascade.worldRelativeToLightUvz());
        assertEquals(shadowProjDirect.x * 0.5f + 0.5f, uvz.x, 1e-4f);
        assertEquals(shadowProjDirect.y * 0.5f + 0.5f, uvz.y, 1e-4f);
        assertEquals(shadowProjDirect.z * 0.5f + 0.5f, uvz.z, 1e-4f);
    }
}
