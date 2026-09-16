package cc.sighs.gravityengine.gravity.look;

import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private static final Quaternionf CAMERA_BASIS_FLIP =
            new Quaternionf().rotationY((float) Math.PI);

    /**
     * One fully resolved world-space look result. {@code orientation()} is the
     * quaternion to install into {@link net.minecraft.client.Camera} (it already
     * contains the camera basis flip); {@code forward()}/{@code up()}/{@code left()}/
     * {@code right()} are the corresponding world-space camera basis vectors.
     */
    public record WorldLook(
            Quaternionf orientation,
            Vec3 forward,
            Vec3 up,
            Vec3 left,
            Vec3 right,
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

        /**
         * Full view forward projected onto the plane perpendicular to
         * {@code frame.up}. Falls back to the pitch-zero yaw-only forward when
         * the projection is degenerate (near-straight-down/up).
         */
        public Vec3 tangentForward(GravityFrame frame) {
            Vec3 projection = forward.subtract(frame.up().scale(forward.dot(frame.up())));
            if (projection.lengthSqr() >= EPSILON) {
                return projection.normalize();
            }
            return yawForward(frame);
        }

        /** Pitch-zero forward derived from the same yaw convention. */
        public Vec3 yawForward(GravityFrame frame) {
            double yaw = Math.toRadians(this.localYaw);
            Quaternionf look = new Quaternionf()
                    .rotationY((float) -yaw);
            Quaternionf base = new Quaternionf(frame.rotation());
            base.mul(look);
            Vector3f mapped = new Vector3f(0.0F, 0.0F, 1.0F).rotate(base);
            return new Vec3(mapped.x, mapped.y, mapped.z);
        }
    }

    private static final double EPSILON = 1.0E-7D;

    /** Resolves the canonical look orientation (no camera flip). */
    public static Quaternionf lookQuaternion(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        return lookQuaternion(frame.rotation(), localYaw, localPitch, localRoll);
    }

    /** Resolves the canonical look orientation from a raw gravity quaternion. */
    public static Quaternionf lookQuaternion(
            Quaternionf gravityRotation,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Quaternionf look = new Quaternionf()
                .rotationY(-toRadians(localYaw))
                .mul(rotationX(toRadians(localPitch)))
                .mul(rotationZ(toRadians(localRoll)));
        return new Quaternionf(gravityRotation).mul(look);
    }

    /** Resolves the camera-install orientation (includes the basis flip). */
    public static Quaternionf cameraQuaternion(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Quaternionf orientation = lookQuaternion(frame, localYaw, localPitch, localRoll);
        return orientation.mul(new Quaternionf(CAMERA_BASIS_FLIP));
    }

    /** Resolves the full world-space look for the supplied local look scalars. */
    public static WorldLook toWorld(
            GravityFrame frame,
            float localYaw,
            float localPitch,
            float localRoll
    ) {
        Quaternionf orientation = cameraQuaternion(frame, localYaw, localPitch, localRoll);
        Vec3 forward = basis(orientation, 0.0F, 0.0F, -1.0F);
        Vec3 up = basis(orientation, 0.0F, 1.0F, 0.0F);
        Vec3 left = basis(orientation, -1.0F, 0.0F, 0.0F);
        Vec3 right = basis(orientation, 1.0F, 0.0F, 0.0F);
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

    private static Vec3 basis(Quaternionf q, float x, float y, float z) {
        Vector3f rotated = new Vector3f(x, y, z).rotate(q);
        return new Vec3(rotated.x, rotated.y, rotated.z);
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
}
