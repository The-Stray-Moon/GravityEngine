package cc.sighs.gravityengine.gravity.integration.vanilla;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Vanilla API projection from semantic view into Vanilla's restricted
 * placement representations.
 *
 * <p>Six-way direction ordering reproduces the branch and strict-comparison
 * topology of Minecraft 1.21.1 {@code Direction.orderedByNearest}; this is
 * important because exact component ties are observable placement policy.
 * World-horizontal-only carriers remain projections into world XZ because
 * {@code HORIZONTAL_FACING} cannot represent an arbitrary gravity tangent
 * plane.</p>
 *
 * <p>All results are Vanilla API projections only. They never feed back
 * into semantic look, Qbody or the environmental reference frame.</p>
 */
public final class VanillaPlacementBridge {
    private static final double HORIZONTAL_EPSILON_SQUARED =
            1.0E-8D;

    private VanillaPlacementBridge() {}

    /**
     * First element of the exact Vanilla-equivalent six-way direction order.
     */
    public static Direction nearestWorldDirection(
            VanillaActorSnapshot actor
    ) {
        return orderedLookingDirections(actor)[0];
    }

    /**
     * Vanilla {@code Direction.getFacingAxis(entity, Y)} semantics:
     * looking upward -> UP, otherwise -> DOWN. Exact horizontal therefore
     * resolves to DOWN.
     */
    public static Direction nearestVerticalDirection(
            VanillaActorSnapshot actor
    ) {
        Objects.requireNonNull(actor, "actor");
        return actor.viewForward().y > 0.0D
                ? Direction.UP
                : Direction.DOWN;
    }

    /**
     * Exact branch-equivalent form of 1.21.1
     * {@code Direction.orderedByNearest(Entity)}, evaluated from an already
     * authoritative semantic world-view vector.
     *
     * <p>The strict {@code >} comparisons are intentional. Do not replace this
     * with a dot-product sort: that changes Vanilla's observable tie order.</p>
     */
    public static Direction[] orderedLookingDirections(
            VanillaActorSnapshot actor
    ) {
        Objects.requireNonNull(actor, "actor");
        Vec3 view = actor.viewForward();

        Direction xDirection =
                view.x > 0.0D ? Direction.EAST : Direction.WEST;
        Direction yDirection =
                view.y > 0.0D ? Direction.UP : Direction.DOWN;
        Direction zDirection =
                view.z > 0.0D ? Direction.SOUTH : Direction.NORTH;

        double absX = Math.abs(view.x);
        double absY = Math.abs(view.y);
        double absZ = Math.abs(view.z);

        if (absX > absZ) {
            if (absY > absX) {
                return makeDirectionArray(
                        yDirection,
                        xDirection,
                        zDirection
                );
            }

            if (absZ > absY) {
                return makeDirectionArray(
                        xDirection,
                        zDirection,
                        yDirection
                );
            }

            return makeDirectionArray(
                    xDirection,
                    yDirection,
                    zDirection
            );
        }

        if (absY > absZ) {
            return makeDirectionArray(
                    yDirection,
                    zDirection,
                    xDirection
            );
        }

        if (absX > absY) {
            return makeDirectionArray(
                    zDirection,
                    xDirection,
                    yDirection
            );
        }

        return makeDirectionArray(
                zDirection,
                yDirection,
                xDirection
        );
    }

    public static Direction horizontalDirection(
            VanillaActorSnapshot actor,
            float vanillaYawFallback
    ) {
        Objects.requireNonNull(actor, "actor");
        Vec3 heading =
                worldHorizontalHeading(
                        actor,
                        vanillaYawFallback
                );

        return Direction.fromYRot(
                Mth.wrapDegrees(
                        (float) Math.toDegrees(
                                Math.atan2(
                                        -heading.x,
                                        heading.z
                                )
                        )
                )
        );
    }

    public static float worldYawDegrees(
            VanillaActorSnapshot actor,
            float vanillaYawFallback
    ) {
        Objects.requireNonNull(actor, "actor");
        Vec3 heading =
                worldHorizontalHeading(
                        actor,
                        vanillaYawFallback
                );

        return Mth.wrapDegrees(
                (float) Math.toDegrees(
                        Math.atan2(
                                -heading.x,
                                heading.z
                        )
                )
        );
    }

    private static Direction[] makeDirectionArray(
            Direction first,
            Direction second,
            Direction third
    ) {
        return new Direction[]{
                first,
                second,
                third,
                third.getOpposite(),
                second.getOpposite(),
                first.getOpposite()
        };
    }

    private static Vec3 worldHorizontalHeading(
            VanillaActorSnapshot actor,
            float vanillaYawFallback
    ) {
        Vec3 candidate =
                horizontalOrNull(actor.viewForward());

        if (candidate == null) {
            candidate =
                    horizontalOrNull(
                            actor.zeroPitchHeading()
                    );
        }

        if (candidate == null) {
            double yaw =
                    Math.toRadians(vanillaYawFallback);
            return new Vec3(
                    -Math.sin(yaw),
                    0.0D,
                    Math.cos(yaw)
            );
        }

        return candidate;
    }

    private static Vec3 horizontalOrNull(Vec3 direction) {
        double horizontalSquared =
                direction.x * direction.x
                        + direction.z * direction.z;

        if (!(horizontalSquared
                > HORIZONTAL_EPSILON_SQUARED)) {
            return null;
        }

        double length = Math.sqrt(horizontalSquared);

        return new Vec3(
                direction.x / length,
                0.0D,
                direction.z / length
        );
    }
}