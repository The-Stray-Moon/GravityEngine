package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Computes the single immutable camera orientation state for one gravity-local
 * look triple.
 *
 * <p>This is the only place that maps {@code GravityLocalLook} into the
 * NeoForge {@code Camera} rest convention. The {@code CameraMixin} is the only
 * component that writes the resulting state into a {@code Camera}; nothing in
 * the production camera path may convert a quaternion back to world Euler.</p>
 */
public final class GravityCameraOrientationInstaller {
    private GravityCameraOrientationInstaller() {}

    /**
     * One immutable camera orientation snapshot. {@code rotation()} is the
     * authoritative quaternion to install; the three vectors are the camera
     * rest basis after that rotation, and the Euler scalars are the gravity-local
     * look values kept for {@code Camera#getXRot}/{@code getYRot}/{@code getRoll}
     * Vanilla API consumers.
     */
    public record CameraOrientationState(
            Quaternionf rotation,
            Vector3f forwards,
            Vector3f up,
            Vector3f left,
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
                toVector(look.forward()),
                toVector(look.up()),
                toVector(look.left()),
                localYaw,
                localPitch,
                localRoll
        );
    }

    /** Qcamera = Qcontroller * Qmodifier * camera-rest adapter.
     * Event deltas are camera-only local yaw/pitch/roll; they never update semantic aim. */
    public static CameraOrientationState computeAttitude(
            Quaterniond worldFromController,
            float modifierYaw,
            float modifierPitch,
            float cameraLocalRollDegrees
    ) {
        Quaternionf local = new Quaternionf()
                .rotationY(-toRadians(modifierYaw))
                .mul(rotationX(toRadians(modifierPitch)))
                .mul(rotationZ(toRadians(cameraLocalRollDegrees)));
        Quaternionf body = new Quaternionf(
                (float) worldFromController.x,
                (float) worldFromController.y,
                (float) worldFromController.z,
                (float) worldFromController.w
        ).normalize();
        Quaternionf orientation = body.mul(local);
        orientation.mul(CAMERA_BASIS_FLIP);
        Vector3f forwards = basis(orientation, 0.0F, 0.0F, -1.0F);
        Vector3f up = basis(orientation, 0.0F, 1.0F, 0.0F);
        Vector3f left = basis(orientation, -1.0F, 0.0F, 0.0F);
        return new CameraOrientationState(
                orientation,
                forwards,
                up,
                left,
                Mth.wrapDegrees(modifierYaw),
                modifierPitch,
                cameraLocalRollDegrees
        );
    }

    /** Camera rest basis uses forward=(0,0,-1); canonical look uses (0,0,1). */
    private static final Quaternionf CAMERA_BASIS_FLIP =
            new Quaternionf().rotationY((float) Math.PI);

    private static Vector3f basis(Quaternionf q, float x, float y, float z) {
        return new Vector3f(x, y, z).rotate(q);
    }

    private static float toRadians(float degrees) {
        return degrees * (float) (Math.PI / 180.0D);
    }

    private static Quaternionf rotationX(float radians) {
        return new Quaternionf().rotationX(radians);
    }

    private static Quaternionf rotationY(float radians) {
        return new Quaternionf().rotationY(radians);
    }

    private static Quaternionf rotationZ(float radians) {
        return new Quaternionf().rotationZ(radians);
    }

    private static Vector3f toVector(net.minecraft.world.phys.Vec3 value) {
        return new Vector3f((float) value.x, (float) value.y, (float) value.z);
    }
}
