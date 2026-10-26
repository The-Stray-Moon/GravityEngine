package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Immutable result of one common angular-dynamics substep.
 *
 * <p>{@code worldFromBody} and {@code angularMomentumWorld} advance atomically;
 * {@code midpointAngularVelocityWorld} is the derived sample used for the
 * quaternion increment and is diagnostics only.</p>
 */
public record AngularDynamicsStep(
        Quatd worldFromBody,
        Vec3d angularMomentumWorld,
        Vec3d midpointAngularVelocityWorld
) {
    public AngularDynamicsStep {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
        Objects.requireNonNull(midpointAngularVelocityWorld,
                "midpointAngularVelocityWorld");
        if (!worldFromBody.isFinite()
                || worldFromBody.lengthSquared() <= 1.0E-24D) {
            throw new IllegalArgumentException(
                    "worldFromBody must be finite and non-degenerate");
        }
        if (!angularMomentumWorld.isFinite()
                || !Double.isFinite(angularMomentumWorld.lengthSquared())
                || !midpointAngularVelocityWorld.isFinite()
                || !Double.isFinite(
                        midpointAngularVelocityWorld.lengthSquared())) {
            throw new IllegalArgumentException(
                    "angular dynamics step values must be finite");
        }
    }
}
