package dev.lhlux.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame cached camera, projection, inverse, and celestial matrices/vectors so passes avoid redundant inversions.
 */
public final class FrameMatrixCache {
    private static final FrameMatrixCache INSTANCE = new FrameMatrixCache();

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewRotation = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseViewRotation = new Matrix4f();
    private final Matrix4f inverseViewProjection = new Matrix4f();

    private final Vector3f lightDirection = new Vector3f(0.3f, 0.8f, 0.4f).normalize();
    private final Vector3f cameraPos = new Vector3f();
    private float cameraYaw;
    private float cameraPitch;
    private float daylightFactor = 1.0f;
    private float rainLevel;
    private float nearPlane = 0.05f;
    private float farPlane = 256.0f;
    private long frameSerial;
    private boolean valid;

    private FrameMatrixCache() {
    }

    public static FrameMatrixCache get() {
        return INSTANCE;
    }

    public void beginFrame(Minecraft mc, Camera camera, float partialTick, Matrix4f projMatrix, Matrix4f viewRotMatrix) {
        frameSerial++;
        projection.set(projMatrix);
        viewRotation.set(viewRotMatrix);
        viewProjection.set(projection).mul(viewRotation);

        inverseProjection.set(projection).invert();
        inverseViewRotation.set(viewRotation).invert();
        inverseViewProjection.set(viewProjection).invert();

        Vec3 pos = camera.getPosition();
        cameraPos.set((float) pos.x, (float) pos.y, (float) pos.z);
        cameraYaw = camera.getYRot();
        cameraPitch = camera.getXRot();

        farPlane = Math.max(32.0f, mc.gameRenderer.getRenderDistance());
        updateCelestialState(mc.level, partialTick);
        valid = true;
    }

    private void updateCelestialState(ClientLevel level, float partialTick) {
        if (level == null) {
            lightDirection.set(0.3f, 0.85f, 0.4f).normalize();
            daylightFactor = 1.0f;
            rainLevel = 0.0f;
            return;
        }
        float sunAngle = level.getSunAngle(partialTick);
        float sunY = Mth.cos(sunAngle);
        float sunX = -Mth.sin(sunAngle);
        float tiltZ = 0.28f;

        if (sunY >= -0.05f) {
            lightDirection.set(sunX, Math.max(0.08f, sunY), tiltZ).normalize();
        } else {
            // Nighttime: gently follow moon direction
            lightDirection.set(-sunX, Math.max(0.10f, -sunY), -tiltZ).normalize();
        }

        daylightFactor = Mth.clamp((sunY + 0.15f) / 0.45f, 0.0f, 1.0f);
        rainLevel = Mth.clamp(level.getRainLevel(partialTick), 0.0f, 1.0f);
    }

    public boolean isValid() {
        return valid;
    }

    public long frameSerial() {
        return frameSerial;
    }

    public Matrix4f projection() {
        return projection;
    }

    public Matrix4f viewRotation() {
        return viewRotation;
    }

    public Matrix4f viewProjection() {
        return viewProjection;
    }

    public Matrix4f inverseProjection() {
        return inverseProjection;
    }

    public Matrix4f inverseViewRotation() {
        return inverseViewRotation;
    }

    public Matrix4f inverseViewProjection() {
        return inverseViewProjection;
    }

    public Vector3f lightDirection() {
        return lightDirection;
    }

    public Vector3f cameraPos() {
        return cameraPos;
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    public float daylightFactor() {
        return daylightFactor;
    }

    public float rainLevel() {
        return rainLevel;
    }

    public float nearPlane() {
        return nearPlane;
    }

    public float farPlane() {
        return farPlane;
    }

    public void invalidate() {
        valid = false;
    }
}
