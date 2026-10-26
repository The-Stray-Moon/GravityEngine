package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned transport of one persistent support over one captured
 * operation interval.
 *
 * <p>The endpoint displacement is sufficient for pure translation. A rotating
 * rigid support additionally carries the exact material-point trajectory so
 * the character route does not collapse the whole support path to one chord.</p>
 */
public record SupportTransport(
        Vec3d displacement,
        Vec3d startSurfaceVelocity,
        Vec3d endSurfaceVelocity,
        Optional<SupportMotionTrajectory> trajectory,
        double intervalTicks,
        long motionRevision,
        long gameTick
) {
    public SupportTransport {
        Objects.requireNonNull(
                displacement,
                "displacement"
        );
        Objects.requireNonNull(
                startSurfaceVelocity,
                "startSurfaceVelocity"
        );
        Objects.requireNonNull(
                endSurfaceVelocity,
                "endSurfaceVelocity"
        );
        Objects.requireNonNull(
                trajectory,
                "trajectory"
        );

        requireFinite(
                displacement,
                "displacement"
        );
        requireFinite(
                startSurfaceVelocity,
                "startSurfaceVelocity"
        );
        requireFinite(
                endSurfaceVelocity,
                "endSurfaceVelocity"
        );

        trajectory =
                trajectory.map(
                        Objects::requireNonNull
                );

        if (!Double.isFinite(intervalTicks)
                || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be finite and positive: "
                            + intervalTicks
            );
        }

        if (motionRevision < 0L) {
            throw new IllegalArgumentException(
                    "motionRevision must be non-negative: "
                            + motionRevision
            );
        }

        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: "
                            + gameTick
            );
        }
    }

    public static SupportTransport resting(
            double intervalTicks,
            long motionRevision,
            long gameTick
    ) {
        return new SupportTransport(
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Optional.empty(),
                intervalTicks,
                motionRevision,
                gameTick
        );
    }

    public boolean moving() {
        return displacement.lengthSquared()
                > 0.0D
                || startSurfaceVelocity.lengthSquared()
                > 0.0D
                || endSurfaceVelocity.lengthSquared()
                > 0.0D;
    }

    public boolean requiresGeneralSupportSweep() {
        return trajectory
                .map(SupportMotionTrajectory::requiresGeneralSweep)
                .orElse(false);
    }

    private static void requireFinite(
            Vec3d value,
            String name
    ) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: "
                            + value
            );
        }
    }
}