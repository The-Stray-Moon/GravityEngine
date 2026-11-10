package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;

/**
 * Computes the single immutable camera orientation state for one gravity-local
 * look triple.
 *
 * <p>This is the only place that resolves {@code GravityLocalLook} into the
 * canonical camera state. The {@code CameraMixin} is the only component that
 * converts that state into NeoForge {@code Camera} values; nothing in the
 * production camera path may convert a quaternion back to world Euler.</p>
 */
public final class GravityCameraOrientationInstaller {
    private GravityCameraOrientationInstaller() {}

    /**
     * One immutable camera orientation snapshot. {@code rotation()} is the
     * authoritative canonical quaternion to present; the three vectors are the camera
     * rest basis after that rotation, and the Euler scalars are the gravity-local
     * look values kept for {@code Camera#getXRot}/{@code getYRot}/{@code getRoll}
     * Vanilla API consumers.
     */
    public record CameraOrientationState(
            Quatd rotation,
            Vec3d forwards,
            Vec3d up,
            Vec3d left,
            float yRot,
            float xRot,
            float roll
    ) {
        public CameraOrientationState {
            if (rotation == null || forwards == null || up == null || left == null) {
                throw new NullPointerException("camera orientation state");
            }
        }
    }

    /** Computes the immutable camera orientation from a gravity frame + local look. */
    public static CameraOrientationState compute(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        GravityLocalLook.WorldLook look =
                GravityLocalLook.toWorld(frame, localYaw, localPitch, localRoll);
        return new CameraOrientationState(
                look.orientation(),
                look.forward(),
                look.up(),
                look.left(),
                localYaw,
                localPitch,
                localRoll
        );
    }

    /** Qcamera = Qcontroller * Qmodifier * camera-rest adapter.
     * Event deltas are camera-only local yaw/pitch/roll; they never update semantic aim. */
    public static CameraOrientationState computeAttitude(
            Quatd worldFromController,
            float modifierYaw,
            float modifierPitch,
            float cameraLocalRollDegrees
    ) {
        Quatd local = Quatd.rotationY(-toRadians(modifierYaw))
                .multiply(Quatd.rotationX(toRadians(modifierPitch)))
                .multiply(Quatd.rotationZ(
                        toRadians(cameraLocalRollDegrees)));
        Quatd body = BodyOrientation3d.normalized(worldFromController);
        Quatd orientation = body.multiply(local)
                .multiply(CAMERA_BASIS_FLIP)
                .normalized();
        Vec3d forwards = basis(orientation, 0.0D, 0.0D, -1.0D);
        Vec3d up = basis(orientation, 0.0D, 1.0D, 0.0D);
        Vec3d left = basis(orientation, -1.0D, 0.0D, 0.0D);
        return new CameraOrientationState(
                orientation,
                forwards,
                up,
                left,
                wrapDegrees(modifierYaw),
                modifierPitch,
                cameraLocalRollDegrees
        );
    }

    /** Camera rest basis uses forward=(0,0,-1); canonical look uses (0,0,1). */
    private static final Quatd CAMERA_BASIS_FLIP =
            Quatd.rotationY(Math.PI);

    private static Vec3d basis(Quatd q, double x, double y, double z) {
        return q.transform(new Vec3d(x, y, z));
    }

    private static float toRadians(float degrees) {
        return degrees * (float) (Math.PI / 180.0D);
    }

    /** Vanilla-compatible angle presentation without a Minecraft dependency. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
