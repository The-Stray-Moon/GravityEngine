package cc.sighs.gravityengine.gravity.look;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;

/**
 * Single authoritative gravity-local look transform.
 *
 * <p>For a {@code CUSTOM_ACTIVE} gravity frame the entity scalars
 * {@code yRot}/{@code xRot} are declared to be <em>gravity-local</em> yaw and
 * pitch, not world-space Euler angles. Every consumer that derives a world
 * direction or a world camera orientation from those scalars must go through
 * this transform. Camera, movement, picking, targeting and gameplay look must
 * never independently re-derive the trig/quaternion convention.</p>
 *
 * <p>Canonical local axes (matching {@link GravityFrame}): local down=(0,-1,0),
 * up=(0,1,0), left=(1,0,0), right=(-1,0,0), forward=(0,0,1). Minecraft/Camera
 * use a rest camera basis of forward=(0,0,-1), up=(0,1,0), left=(-1,0,0), so
 * the camera-install orientation carries the constant 180-degree Y flip that
 * aligns those two conventions.</p>
 */
public final class GravityLocalLook {
    private GravityLocalLook() {}

    /** The camera rest basis uses forward=(0,0,-1); canonical look uses (0,0,1). */
    private static final Quatd CAMERA_BASIS_FLIP =
            Quatd.rotationY(Math.PI);

    /**
     * One fully resolved world-space look result. {@code orientation()} is the
     * canonical quaternion presented to the camera boundary (it already
     * contains the camera basis flip); {@code forward()}/{@code up()}/{@code left()}/
     * {@code right()} are the corresponding world-space camera basis vectors.
     */
    public record WorldLook(
            Quatd orientation,
            Vec3d forward,
            Vec3d up,
            Vec3d left,
            Vec3d right,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        public WorldLook {
            if (orientation == null) throw new NullPointerException("orientation");
            if (forward == null || up == null || left == null || right == null) {
                throw new NullPointerException("world basis vector");
            }
        }
    }

    /** Resolves the canonical look orientation (no camera flip). */
    public static Quatd lookQuaternion(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Quatd gravity = BodyOrientation3d.quaternion(
                frame.orientation());
        Quatd look = Quatd.rotationY(-toRadians(localYaw))
                .multiply(Quatd.rotationX(toRadians(localPitch)))
                .multiply(Quatd.rotationZ(toRadians(localRoll)));
        return gravity.multiply(look).normalized();
    }

    /** Resolves the camera-install orientation (includes the basis flip). */
    public static Quatd cameraQuaternion(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        return lookQuaternion(frame, localYaw, localPitch, localRoll)
                .multiply(CAMERA_BASIS_FLIP)
                .normalized();
    }

    /** Resolves the full world-space look for the supplied local look scalars. */
    public static WorldLook toWorld(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Quatd orientation = cameraQuaternion(frame, localYaw, localPitch, localRoll);
        Vec3d forward = basis(orientation, 0.0D, 0.0D, -1.0D);
        Vec3d up = basis(orientation, 0.0D, 1.0D, 0.0D);
        Vec3d left = basis(orientation, -1.0D, 0.0D, 0.0D);
        Vec3d right = basis(orientation, 1.0D, 0.0D, 0.0D);
        return new WorldLook(orientation, forward, up, left, right, localYaw, localPitch, localRoll);
    }

    /** Convenience overload with zero roll. */
    public static WorldLook toWorld(
            GravityFrame frame,
            float localYaw,
            float localPitch
    ) {
        return toWorld(frame, localYaw, localPitch, 0.0F);
    }

    private static Vec3d basis(Quatd q, double x, double y, double z) {
        return q.transform(new Vec3d(x, y, z));
    }

    private static float toRadians(float degrees) {
        return degrees * (float) (Math.PI / 180.0D);
    }
}
