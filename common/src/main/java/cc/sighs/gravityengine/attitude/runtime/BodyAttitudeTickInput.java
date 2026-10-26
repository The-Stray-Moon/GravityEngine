package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable capture of every production evidence value one fixed
 * body-attitude tick may consume.
 *
 * <p>The integration boundary resolves the player, config, frame, semantic
 * look, world velocity, unified input and bootstrap candidates before invoking
 * the service. Once the service starts it never reads a platform entity,
 * level or live provider.</p>
 *
 * <p>All vectors are GravityEngine-owned immutable {@link Vec3d} values: the
 * platform capture converts once, and the common service never receives a
 * mutable platform vector.</p>
 */
public record BodyAttitudeTickInput(
        long logicalStep,
        BodyAttitudeDecision decision,
        BodyAttitudeOwnership ownership,
        GravityFrame frame,
        Vec3d velocity,
        AttitudeSpaceTransform.LocalLookAngles lookScalars,
        Vec3d bootstrapLookForward,
        Optional<BodyAttitudeBootstrap> bootstrap,
        BodyAttitudeInput input,
        BodyAttitudeConfigSnapshot config,
        double maxHeadRotationRadians,
        CharacterAttitudeContract attitudeContract,
        boolean referenceRelativeLook
) {
    public BodyAttitudeTickInput {
        Objects.requireNonNull(attitudeContract, "attitudeContract");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(lookScalars, "lookScalars");
        Objects.requireNonNull(bootstrapLookForward, "bootstrapLookForward");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(config, "config");
        bootstrap = bootstrap.map(Objects::requireNonNull);
        if (!velocity.isFinite()) {
            throw new IllegalArgumentException(
                    "velocity must be finite: " + velocity);
        }
        if (!bootstrapLookForward.isFinite()) {
            throw new IllegalArgumentException(
                    "bootstrapLookForward must be finite: "
                            + bootstrapLookForward);
        }
        if (logicalStep < 0L) {
            throw new IllegalArgumentException(
                    "logicalStep must be non-negative");
        }
        if (Double.isNaN(maxHeadRotationRadians)
                || maxHeadRotationRadians < 0.0D) {
            throw new IllegalArgumentException(
                    "maxHeadRotationRadians must be non-negative or unlimited");
        }
    }
}
