package cc.sighs.gravityengine.gravity.kinematic.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import java.util.Objects;

/**
 * One complete physical pose chosen before collision begins.
 *
 * <p>This is a pure value: an entity/network position anchor, the body centre
 * that anchor produces, the environmental frame the body was built against and
 * the exact collision body itself. It is not mutable entity state; committing
 * it is the sole responsibility of the target geometry transition boundary.</p>
 *
 * <p>It owns no platform dimension type. A target captures its own
 * {@code EntityDimensions}, builds the exact body through its own geometry
 * adapter and then constructs this value.</p>
 */
public record KinematicPose(
        Vec3d positionAnchor,
        Vec3d center,
        GravityFrame frame,
        CollisionBody body
) {
    /**
     * Exact-body/proxy agreement tolerance (one micrometre), squared. The body
     * centre and the pose centre must agree within this bound.
     */
    public static final double CENTER_MATCH_EPSILON = 1.0E-6D;
    public static final double CENTER_MATCH_EPSILON_SQUARED =
            CENTER_MATCH_EPSILON * CENTER_MATCH_EPSILON;

    public KinematicPose {
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(body, "body");
        requireFinite(positionAnchor, "positionAnchor");
        requireFinite(center, "center");
        if (body.center()
                .subtract(center)
                .lengthSquared()
                > CENTER_MATCH_EPSILON_SQUARED) {
            throw new IllegalArgumentException(
                    "kinematic pose body center does not match pose center: body="
                            + body.center() + ", center=" + center
            );
        }
        // The frame owns environmental reference evidence; the character body
        // consumes only its up axis. Center and finite-value checks above
        // remain mandatory.
    }

    /** The canonical character centre produced by a height at this anchor. */
    public static Vec3d characterCenter(
            Vec3d positionAnchor,
            double height
    ) {
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        if (!Double.isFinite(height) || height < 0.0D) {
            throw new IllegalArgumentException(
                    "height must be finite and non-negative: " + height
            );
        }
        return positionAnchor.add(0.0D, height * 0.5D, 0.0D);
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
