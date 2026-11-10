package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import java.util.Objects;

/**
 * Source-specific delta filtering and selected-response evidence transport.
 *
 * <p>This class never classifies Minecraft request types and never performs
 * an independent geometric solve.</p>
 */
public final class GroundTractionPolicy {
    private GroundTractionPolicy() {}

    /** Steep foot contact supplies normal reaction only, retaining gravity tangent. */
    public static Vec3d contactGravity(Vec3d acceleration, Vec3d actorVelocity, GravitySupportContact support) {
        Vec3d normal = support.normal();
        Vec3d relative = actorVelocity.subtract(support.surfaceVelocity());
        if (relative.dot(normal) > CollisionTolerances.ENTERING_PLANE_EPSILON) return acceleration;
        double inward = acceleration.dot(normal);
        return inward < 0 ? acceleration.subtract(normal.multiply(inward)) : acceleration;
    }

    /**
     * Stable terminal support absorbs newly generated inward gravity as
     * well as its passive slide tendency. This is controller acceleration
     * response, never a hard plane or a filter on persistent actor motion.
     * Outward relative velocity (jump/detach) retains ordinary acceleration.
     * Generic passive/external downward proposals still reach exact CCD.
     */
    public static Vec3d supportedGravity(Vec3d acceleration, Vec3d actorVelocity, GravitySupportContact support) {
        Vec3d normal = support.normal();
        Vec3d surfaceVelocity = support.surfaceVelocity();
        if (actorVelocity.subtract(surfaceVelocity).dot(normal) > CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return acceleration;
        }
        return normal.multiply(Math.max(0.0D, acceleration.dot(normal)));
    }

    /**
     * Filters an explicitly identified new acceleration/correction delta.
     * Never pass persistent actor velocity as passive evidence.
     *
     * <ul>
     *   <li>SELF_WALK survives;</li>
     *   <li>explicit external push survives;</li>
     *   <li>known newly introduced acceleration/correction tangent is absorbed;</li>
     *   <li>both inward and outward normal motion survive for hard collision
     *       response or separation; the feet locator never blocks them;</li>
     *   <li>exact positional support transport survives unchanged; surface
     *       velocity belongs to relative contact response and never replaces
     *       that transport.</li>
     * </ul>
     */
    public static OwnedMotion supported(
            OwnedMotion motion,
            GravitySupportContact support,
            double intervalTicks
    ) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(support, "support");

        if (!Double.isFinite(intervalTicks)
                || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be finite and positive: "
                            + intervalTicks
            );
        }

        Vec3d normal = support.normal();

        // Traction owns only the new surface-tangent increment. In particular,
        // a selected feet face need not touch the OBB: stopping inward normal
        // motion here would turn the locator into an invisible floor collider.
        Vec3d passive = normal.multiply(motion.passive().dot(normal));

        return new OwnedMotion(
                motion.selfWalk(),
                motion.externalPush(),
                passive,
                motion.supportMotion()
        );
    }

    /**
     * Every ownership component follows the exact linear transform selected
     * by the one contact solve.
     *
     * <p>The affine part belongs to current environment/support motion. No
     * component performs another projection of its own.</p>
     */
    public static OwnedMotion followResponse(
            OwnedMotion motion,
            ContactConstraintProjector.Result selected
    ) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(selected, "selected");

        if (selected.infeasible()) {
            throw new IllegalArgumentException(
                    "cannot transport ownership through infeasible response"
            );
        }

        ContactConstraintProjector.LinearPart linear =
                selected.linearPart();

        Vec3d affine = selected.affineOffset();

        return new OwnedMotion(
                transform(linear, motion.selfWalk()),
                transform(linear, motion.externalPush()),
                transform(linear, motion.passive()),
                transform(linear, motion.supportMotion())
                        .add(affine)
        );
    }

    private static Vec3d transform(
            ContactConstraintProjector.LinearPart linear,
            Vec3d value
    ) {
        return linear.apply(value);
    }
}
