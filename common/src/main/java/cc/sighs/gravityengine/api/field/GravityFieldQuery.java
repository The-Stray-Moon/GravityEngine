package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Pure immutable input snapshot for one field evaluation.
 *
 * <p>{@code intervalTicks} may be zero for an instantaneous query;
 * Position and velocity are world-space blocks and blocks/tick. Game tick is
 * non-negative; interval is finite/non-negative ticks. Physics callers supply the actual movement interval when the field needs
 * it. Time sampling is at integer {@code gameTick}: {@code intervalTicks}
 * describes duration, not an offset within that tick. This version cannot
 * distinguish substep phases with otherwise identical inputs.</p>
 *
 * <p>{@link Vec3d} is immutable, so the snapshot stores the exact value
 * supplied by the caller. Callers cannot mutate query state through either
 * accessor.</p>
 */
public record GravityFieldQuery(
        Vec3d position,
        Vec3d velocity,
        long gameTick,
        double intervalTicks
) {
    public GravityFieldQuery {
        Objects.requireNonNull(
                position,
                "position"
        );

        Objects.requireNonNull(
                velocity,
                "velocity"
        );

        requireFinite(
                position,
                "position"
        );

        requireFinite(
                velocity,
                "velocity"
        );

        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: "
                            + gameTick
            );
        }

        if (!Double.isFinite(intervalTicks)
                || intervalTicks < 0.0D) {

            throw new IllegalArgumentException(
                    "intervalTicks must be finite and non-negative: "
                            + intervalTicks
            );
        }
    }

    /** Position-only, zero-velocity instantaneous query at tick zero. This is
     * not a query for the current world time. */
    public static GravityFieldQuery at(
            Vec3d position
    ) {
        Objects.requireNonNull(position, "position");

        return new GravityFieldQuery(
                position,
                Vec3d.ZERO,
                0L,
                0.0D
        );
    }

    private static void requireFinite(
            Vec3d vector,
            String name
    ) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }
}
