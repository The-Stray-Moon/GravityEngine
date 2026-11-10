package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AngularMomentumState;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable disk persistence seed for one player body-attitude state.
 *
 * <p>The disk seed stores only continuity that cannot be reconstructed without
 * loss: absolute {@code worldFromBody}, {@code worldFromController}, and — when
 * the saved owner was dynamic — world-space angular momentum {@code L_world}.
 * Effective inertia is intentionally not durable here: in the current model it
 * belongs to the receiving control profile/config generation and is rebound at
 * restore time.</p>
 *
 * <p>The seed carries no torque accumulator, substep, trajectory, interpolation
 * history, live input, tick/revision chronology, or writable angular velocity.
 * Presence of {@code angularMomentumWorld} records that the saved owner was
 * dynamic even when the momentum vector itself is exactly zero.</p>
 */
public record BodyAttitudePersistentSeed(
        Quatd worldFromBody,
        Quatd worldFromController,
        Optional<Vec3d> angularMomentumWorld
) {
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-24D;

    public BodyAttitudePersistentSeed {
        worldFromBody = normalized(worldFromBody, "worldFromBody");
        worldFromController = normalized(
                worldFromController,
                "worldFromController"
        );

        Objects.requireNonNull(
                angularMomentumWorld,
                "angularMomentumWorld"
        );

        angularMomentumWorld = angularMomentumWorld.map(momentum -> {
            Objects.requireNonNull(
                    momentum,
                    "angularMomentumWorld entry"
            );

            if (!momentum.isFinite()
                    || !Double.isFinite(momentum.lengthSquared())) {
                throw new IllegalArgumentException(
                        "angularMomentumWorld must be finite"
                );
            }

            return momentum;
        });
    }

    /**
     * Captures one already-owned durable seed. Kinematic ownership stores no
     * momentum entry; dynamic ownership stores {@code L_world} only.
     */
    public static Optional<BodyAttitudePersistentSeed> capture(
            BodyAttitudeComponent.Snapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");

        BodyAttitudeState state = snapshot.state();
        BodyRelativeViewState view = snapshot.view();

        if (snapshot.ownership() != BodyAttitudeOwnership.ACTIVE
                || !state.initialized()
                || !view.initialized()) {
            return Optional.empty();
        }

        return Optional.of(
                new BodyAttitudePersistentSeed(
                        state.currentWorldFromBody(),
                        view.semantic(state.currentWorldFromBody())
                                .worldFromController(),
                        state.angularMomentum()
                                .map(
                                        AngularMomentumState
                                                ::angularMomentumWorld
                                )
                )
        );
    }

    private static Quatd normalized(
            Quatd value,
            String name
    ) {
        Objects.requireNonNull(value, name);

        double lengthSquared = value.lengthSquared();

        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.w())
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-degenerate"
            );
        }

        return value.normalized();
    }
}