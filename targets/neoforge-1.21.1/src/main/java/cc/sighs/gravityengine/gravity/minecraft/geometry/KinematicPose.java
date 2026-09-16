package cc.sighs.gravityengine.gravity.minecraft.geometry;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * One complete physical pose chosen before collision begins.  It is not
 * mutable entity state: committing it is the sole responsibility of the
 * geometry transition boundary.
 */
public record KinematicPose(
        Vec3 positionAnchor,
        Vec3 center,
        GravityFrame frame,
        CollisionBody body
) {
    public KinematicPose {
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(body, "body");
        requireFinite(positionAnchor, "positionAnchor");
        requireFinite(center, "center");
        if (new Vector3d(body.center())
                .sub(center.x, center.y, center.z)
                .lengthSquared()
                > GravityEntityGeometry.GEOMETRY_FRAME_EPSILON_SQUARED) {
            throw new IllegalArgumentException(
                    "kinematic pose body center does not match pose center: body="
                            + body.center() + ", center=" + center
            );
        }
        // frame owns environmental reference evidence; character body consumes only its up axis.
        // Center and finite-value checks above remain mandatory.
    }

    /**
     * P-preserving construction policy: update the collision axis while
     * retaining the entity/network position exactly. Packet validation forbids
     * a support-preserving preparation translation.
     *
     * <p>At fixed dimensions this is also the full pose-anchor policy: because
     * the canonical character geometry keeps {@code C = P + worldUp * height/2},
     * one exact body at one P exists per axis, so a separate
     * center-preserving candidate could never differ from this one.</p>
     */
    public static KinematicPose preservePositionAnchor(
            EntityDimensions dimensions,
            Vec3 positionAnchor,
            GravityFrame frame
    ) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        Objects.requireNonNull(frame, "frame");
        Vec3 center = GravityEntityGeometry.bodyCenterFromPositionAnchor(
                positionAnchor, dimensions.height());
        CollisionBody body = GravityEntityGeometry.characterBodyAtCenter(
                dimensions.width(), dimensions.height(), center, frame.up());
        return new KinematicPose(positionAnchor, center, frame, body);
    }

    private static void requireFinite(Vec3 value, String name) {
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
