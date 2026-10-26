package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.Objects;

/**
 * Pure gravity-relative movement and jump math.
 *
 * <p>The movement API consumes the already-resolved <em>semantic world view
 * forward</em> ({@code Qbody * body-relative view} for active BodyAttitude,
 * otherwise the gravity-local/Vanilla look) so Minecraft-facing layers resolve
 * reference space exactly once.  This class never reads a {@code Player},
 * BodyAttitude component, network state or renderer state.</p>
 */
public final class GravityPhysics {
    /** Input zero threshold; matches Vanilla {@code moveRelative}. */
    private static final double EPSILON = 1.0E-7D;
    /** Tangent projection length threshold for the movement basis. */
    private static final double TANGENT_EPSILON = 1.0E-6D;

    private GravityPhysics() {}

    /**
     * Semantic-view relative movement with an explicit caller-supplied
     * fallback heading used only when the view projection onto the gravity
     * tangent plane is degenerate (near-pole look).
     */
    public static Vec3d calculateRelativeMovement(
            Vec3d semanticViewForwardWorld,
            float speed,
            Vec3d input,
            GravityFrame frame,
            Vec3d fallbackHeadingWorld
    ) {
        Objects.requireNonNull(semanticViewForwardWorld,
                "semanticViewForwardWorld");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(fallbackHeadingWorld, "fallbackHeadingWorld");
        double lengthSqr = input.lengthSquared();
        if (lengthSqr < EPSILON) {
            return Vec3d.ZERO;
        }

        Vec3d scaledInput = (lengthSqr > 1.0D ? input.normalized() : input)
                .multiply(speed);
        Vec3d forward = tangentForward(
                semanticViewForwardWorld, frame, fallbackHeadingWorld);
        Vec3d left = frame.up().cross(forward).normalized();

        return left.multiply(scaledInput.x())
                .add(frame.up().multiply(scaledInput.y()))
                .add(forward.multiply(scaledInput.z()));
    }

    /**
     * Pure gravity-relative jump math.
     *
     * <p>The jump impulse appears exactly once in the returned world velocity:
     * the gravity-local vertical component is replaced by
     * {@code jumpPower} and the tangent components are preserved.
     * A sprint boost adds {@code 0.2} along the semantic view
     * projected onto the gravity tangent plane (the same tangent direction W
     * movement uses). Callers must write this result to
     * {@code Entity.deltaMovement} and must not copy it into any runtime
     * velocity channel.</p>
     */
    public static Vec3d jumpFromGround(
            Vec3d currentWorldVelocity,
            float jumpPower,
            boolean sprinting,
            Vec3d semanticViewForwardWorld,
            Vec3d fallbackHeadingWorld,
            GravityFrame frame
    ) {
        Objects.requireNonNull(currentWorldVelocity,
                "currentWorldVelocity");
        Objects.requireNonNull(semanticViewForwardWorld,
                "semanticViewForwardWorld");
        Objects.requireNonNull(fallbackHeadingWorld, "fallbackHeadingWorld");
        Objects.requireNonNull(frame, "frame");
        Vec3d local = frame.worldToLocal(currentWorldVelocity);
        Vec3d jumped = new Vec3d(
                local.x(),
                jumpPower,
                local.z()
        );

        if (sprinting) {
            Vec3d boostLocal = frame.worldToLocal(
                    tangentForward(
                            semanticViewForwardWorld,
                            frame,
                            fallbackHeadingWorld
                    ).multiply(0.2D)
            );
            jumped = jumped.add(boostLocal.x(), 0.0D, boostLocal.z());
        }

        return frame.localToWorld(jumped);
    }

    /** Tangent projection with an explicit caller fallback heading. */
    public static Vec3d tangentForward(
            Vec3d semanticViewForwardWorld,
            GravityFrame frame,
            Vec3d fallbackHeadingWorld
    ) {
        Objects.requireNonNull(semanticViewForwardWorld,
                "semanticViewForwardWorld");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(fallbackHeadingWorld, "fallbackHeadingWorld");
        Vec3d tangent = AttitudeSpaceTransform.projectedUnit(
                semanticViewForwardWorld, frame.up(), TANGENT_EPSILON);
        if (tangent == null) {
            tangent = AttitudeSpaceTransform.projectedUnit(
                    fallbackHeadingWorld, frame.up(), TANGENT_EPSILON);
        }
        if (tangent == null) {
            tangent = AttitudeSpaceTransform.projectedUnit(
                    frame.forward(), frame.up(), TANGENT_EPSILON);
        }
        if (tangent == null) {
            throw new IllegalStateException(
                    "valid gravity frame must provide a tangent forward");
        }
        return tangent;
    }

}
