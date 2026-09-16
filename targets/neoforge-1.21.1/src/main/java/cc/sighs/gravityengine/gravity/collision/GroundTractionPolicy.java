package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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
    public static Vec3 contactGravity(Vec3 acceleration, Vec3 actorVelocity, GravitySupportContact support) {
        Vec3 normal = MinecraftGeometryAdapter.toMinecraft(support.normal());
        Vec3 relative = actorVelocity.subtract(MinecraftGeometryAdapter.toMinecraft(support.surfaceVelocity()));
        if (relative.dot(normal) > CollisionTolerances.ENTERING_PLANE_EPSILON) return acceleration;
        double inward = acceleration.dot(normal);
        return inward < 0 ? acceleration.subtract(normal.scale(inward)) : acceleration;
    }

    /**
     * Stable terminal support absorbs newly generated inward gravity as
     * well as its passive slide tendency. This is controller acceleration
     * response, never a hard plane or a filter on persistent actor motion.
     * Outward relative velocity (jump/detach) retains ordinary acceleration.
     * Generic passive/external downward proposals still reach exact CCD.
     */
    public static Vec3 supportedGravity(Vec3 acceleration, Vec3 actorVelocity, GravitySupportContact support) {
        Vec3 normal = MinecraftGeometryAdapter.toMinecraft(support.normal());
        Vec3 surfaceVelocity = MinecraftGeometryAdapter.toMinecraft(support.surfaceVelocity());
        if (actorVelocity.subtract(surfaceVelocity).dot(normal) > CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return acceleration;
        }
        return normal.scale(Math.max(0.0D, acceleration.dot(normal)));
    }

    /**
     * A supporting collision during the just-completed movement is enough to
     * prevent Vanilla travel from immediately re-introducing the same inward
     * gravity increment, even when the tangent leg ended without a terminal
     * feet-support witness.
     *
     * <p>This is deliberately weaker than terminal support:
     * it owns no support identity, material, platform velocity, hard plane or
     * cross-tick state. It filters only the newly generated gravity increment
     * for this travel invocation.</p>
     *
     * <p>Existing actor velocity is never modified here. In particular:
     * walking inertia, external pushes and explicit gravity-down velocity survive.
     * A real gravity-up separation/jump also keeps ordinary gravity.</p>
     */
    public static Vec3 supportedGravityAfterMovementContact(
            Vec3 acceleration,
            Vec3 actorVelocity,
            cc.sighs.gravityengine.gravity.GravityFrame frame
    ) {
        Objects.requireNonNull(acceleration, "acceleration");
        Objects.requireNonNull(actorVelocity, "actorVelocity");
        Objects.requireNonNull(frame, "frame");

        Vec3 up = frame.up();

        /*
         * A genuine jump/detach is already separating along reference-up.
         * Preserve ordinary gravity so the support-continuity fact cannot create
         * a coyote/hover state during explicit separation.
         */
        if (actorVelocity.dot(up)
                > CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return acceleration;
        }

        /*
         * Only suppress an acceleration that is actually entering the supporting
         * half-space. This also protects weak/deadbanded environmental samples
         * whose exact acceleration direction is not identical to frame.down().
         */
        if (acceleration.dot(up)
                >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return acceleration;
        }

        /*
         * supportingContactDuringMove already proved that this movement's
         * gravity-down leg was physically blocked by a real support-capable
         * contact. Do not immediately inject another gravity impulse after that
         * solve; the movement result already owns the blocked-down contact response.
         *
         * Return zero only for the NEW gravity increment. actorVelocity itself
         * remains completely untouched.
         */
        return Vec3.ZERO;
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
     *   <li>current support velocity replaces old support carry.</li>
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

        Vec3 normal =
                MinecraftGeometryAdapter.toMinecraft(
                        support.normal()
                );

        // Traction owns only the new surface-tangent increment. In particular,
        // a selected feet face need not touch the OBB: stopping inward normal
        // motion here would turn the locator into an invisible floor collider.
        Vec3 passive = normal.scale(motion.passive().dot(normal));

        Vec3 currentSupportMotion =
                MinecraftGeometryAdapter.toMinecraft(
                        support.surfaceVelocity()
                ).scale(intervalTicks);

        return new OwnedMotion(
                motion.selfWalk(),
                motion.externalPush(),
                passive,
                currentSupportMotion
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

        Vec3 affine =
                MinecraftGeometryAdapter.toMinecraft(
                        selected.affineOffset()
                );

        return new OwnedMotion(
                transform(linear, motion.selfWalk()),
                transform(linear, motion.externalPush()),
                transform(linear, motion.passive()),
                transform(linear, motion.supportMotion())
                        .add(affine)
        );
    }

    private static Vec3 transform(
            ContactConstraintProjector.LinearPart linear,
            Vec3 value
    ) {
        Vector3d transformed =
                linear.apply(
                        MinecraftGeometryAdapter.toJoml(
                                value,
                                new Vector3d()
                        )
                );

        return MinecraftGeometryAdapter.toMinecraft(
                transformed
        );
    }
}
