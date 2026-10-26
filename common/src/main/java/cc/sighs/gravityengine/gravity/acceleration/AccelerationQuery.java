package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import java.util.Objects;

/**
 * Immutable inputs for one acceleration resolution at a physics boundary.
 *
 * <p>Every component is an engine-owned value or a primitive simulation
 * scalar. The query deliberately retains no Minecraft {@code Entity} or
 * {@code Level}: the target captures the live platform facts once and resolves
 * them into these explicit operands before physics runs.</p>
 *
 * <p>{@code appliedGravity} is the authoritative committed gravity state for
 * the operation. It is the fallback evidence under DIRECT authority and the
 * orientation reference under NONE authority; a FIELD query samples the
 * registry instead and only uses this value when the live field set is empty
 * while the committed state is not default.</p>
 */
public record AccelerationQuery(
        Vec3d samplePoint,
        Vec3d velocity,
        long gameTick,
        double intervalTicks,
        GravityState appliedGravity
) {
    public AccelerationQuery {
        requireFinite(samplePoint, "samplePoint");
        requireFinite(velocity, "velocity");
        Objects.requireNonNull(appliedGravity, "appliedGravity");
        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: " + gameTick);
        }
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be finite and positive: "
                            + intervalTicks);
        }
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value);
        }
    }
}
