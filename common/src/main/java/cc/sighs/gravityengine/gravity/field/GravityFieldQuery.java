package cc.sighs.gravityengine.gravity.field;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * Pure immutable input snapshot for one field evaluation.
 *
 * <p>{@code intervalTicks} may be zero only for position-only sampling;
 * physics callers supply the actual movement interval when the field needs
 * it.</p>
 */
public record GravityFieldQuery(
        Vector3dc position,
        Vector3dc velocity,
        long gameTick,
        double intervalTicks
) {
    public GravityFieldQuery {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");

        requireFinite(position, "position");
        requireFinite(velocity, "velocity");

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

        position = new Vector3d(position);
        velocity = new Vector3d(velocity);
    }

    @Override
    public Vector3dc position() {
        return new Vector3d(position);
    }

    @Override
    public Vector3dc velocity() {
        return new Vector3d(velocity);
    }

    public static GravityFieldQuery at(
            Vector3dc position
    ) {
        Objects.requireNonNull(position, "position");

        return new GravityFieldQuery(
                position,
                new Vector3d(),
                0L,
                0.0D
        );
    }

    private static void requireFinite(
            Vector3dc vector,
            String name
    ) {
        if (!Double.isFinite(vector.x())
                || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }
}