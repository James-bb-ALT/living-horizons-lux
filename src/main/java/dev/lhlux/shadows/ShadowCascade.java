package dev.lhlux.shadows;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Clean-room texel-snapped orthographic shadow cascade for sun/moon directional shadows.
 */
public final class ShadowCascade {
    private static final Matrix4f CLIP_TO_UV = new Matrix4f()
            .translation(0.5f, 0.5f, 0.5f)
            .scale(0.5f, 0.5f, 0.5f);

    private final String name;
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f lightViewRotation = new Matrix4f();
    private final Matrix4f lightProjection = new Matrix4f();
    private final Matrix4f worldRelativeToLightUvz = new Matrix4f();
    private final Vector3f snappedAnchor = new Vector3f();
    private final Vector3f lightEye = new Vector3f();
    private final Vector3f lightDir = new Vector3f(0.0f, 1.0f, 0.0f);

    private float radius = 48.0f;
    private float depthRange = 220.0f;
    private int resolution = 1536;
    private boolean valid;

    public ShadowCascade(String name) {
        this.name = name;
    }

    /**
     * Updates the orthographic projection and view matrices centered around cameraPos with texel snapping
     * so shadow edges remain stable during sub-texel camera movement.
     */
    public void update(Vector3f cameraPos, Vector3f toLightDir, float cascadeRadius, float depthSpan, int mapResolution) {
        this.radius = Math.max(8.0f, cascadeRadius);
        this.depthRange = Math.max(64.0f, depthSpan);
        this.resolution = Math.max(256, mapResolution);
        this.lightDir.set(toLightDir).normalize();

        // Pick stable up vector avoiding gimbal lock when sun is directly overhead
        Vector3f up = Math.abs(lightDir.y) > 0.985f ? new Vector3f(0.0f, 0.0f, 1.0f) : new Vector3f(0.0f, 1.0f, 0.0f);

        // Compute light basis (right, orthoUp, forward = -lightDir)
        Vector3f right = new Vector3f(up).cross(lightDir).normalize();
        Vector3f orthoUp = new Vector3f(lightDir).cross(right).normalize();

        // Project camera world position onto light plane and snap to shadow texel grid
        float texelWorldSize = (2.0f * this.radius) / (float) this.resolution;
        float projRight = cameraPos.dot(right);
        float projUp = cameraPos.dot(orthoUp);
        float projDepth = cameraPos.dot(lightDir);

        float snappedRight = (float) Math.floor(projRight / texelWorldSize) * texelWorldSize;
        float snappedUp = (float) Math.floor(projUp / texelWorldSize) * texelWorldSize;
        float snappedDepth = (float) Math.floor(projDepth / texelWorldSize) * texelWorldSize;

        snappedAnchor.set(right).mul(snappedRight)
                .fma(snappedUp, orthoUp)
                .fma(snappedDepth, lightDir);

        float halfDepth = this.depthRange * 0.5f;
        lightEye.set(snappedAnchor).fma(halfDepth, lightDir);

        lightView.identity().lookAt(lightEye, snappedAnchor, orthoUp);
        lightViewRotation.identity().lookAt(
                0.0f, 0.0f, 0.0f,
                -lightDir.x, -lightDir.y, -lightDir.z,
                orthoUp.x, orthoUp.y, orthoUp.z
        );
        lightProjection.identity().ortho(
                -this.radius, this.radius,
                -this.radius, this.radius,
                0.5f, this.depthRange,
                false
        );

        this.valid = true;
        refreshCameraRelativeMatrix(cameraPos);
    }

    /**
     * Recomputes the camera-relative shadow projection matrix for the current frame's camera position
     * without requiring a shadow map rebuild when the cascade is cached.
     */
    public void refreshCameraRelativeMatrix(Vector3f cameraPos) {
        if (!valid) {
            return;
        }
        Matrix4f camToLightEye = new Matrix4f().translation(
                cameraPos.x - lightEye.x,
                cameraPos.y - lightEye.y,
                cameraPos.z - lightEye.z
        );
        worldRelativeToLightUvz
                .set(CLIP_TO_UV)
                .mul(lightProjection)
                .mul(lightViewRotation)
                .mul(camToLightEye);
    }

    public String name() {
        return name;
    }

    public Matrix4f lightView() {
        return lightView;
    }

    public Matrix4f lightViewRotation() {
        return lightViewRotation;
    }

    public Matrix4f lightProjection() {
        return lightProjection;
    }

    public Matrix4f worldRelativeToLightUvz() {
        return worldRelativeToLightUvz;
    }

    public Vector3f snappedAnchor() {
        return snappedAnchor;
    }

    public Vector3f lightEye() {
        return lightEye;
    }

    public Vector3f lightDirection() {
        return lightDir;
    }

    public float radius() {
        return radius;
    }

    public float texelSize() {
        return 1.0f / (float) resolution;
    }

    public int resolution() {
        return resolution;
    }

    public boolean isValid() {
        return valid;
    }

    public void invalidate() {
        valid = false;
    }
}
