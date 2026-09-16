package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import cc.sighs.gravityengine.attitude.BodyAttitudeInput;
import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable capture of every production evidence value one fixed
 * body-attitude tick may consume.
 *
 * <p>The integration boundary ({@code PlayerTickEvent.Post}) resolves the
 * player, config, frame, semantic look, world
 * velocity, unified input and bootstrap candidates before invoking
 * the service. Once the service starts it never reads a {@code Player},
 * {@code Level} or live provider.</p>
 */
public record BodyAttitudeTickInput(
        long logicalStep,
        BodyAttitudeDecision decision,
        BodyAttitudeOwnership ownership,
        GravityFrame frame,
        Vec3 velocity,
        AttitudeSpaceTransform.LocalLookAngles lookScalars,
        Vec3 bootstrapLookForward,
        Optional<BodyAttitudeBootstrap> bootstrap,
        BodyAttitudeInput input,
        BodyAttitudeConfigSnapshot config,
        double maxHeadRotationRadians,
        cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract attitudeContract,
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
        velocity = new Vec3(velocity.x, velocity.y, velocity.z);
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

    @Override
    public Vec3 velocity() {
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

}
