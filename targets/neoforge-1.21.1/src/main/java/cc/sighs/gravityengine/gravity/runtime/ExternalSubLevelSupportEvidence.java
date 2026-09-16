package cc.sighs.gravityengine.gravity.runtime;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * Completed Sable SubLevel path-contact evidence for the current operation.
 *
 * <p>The evidence originates from Sable 2.0.5 FirstCollisionInfo. That value
 * records the first collision encountered with one SubLevel during the solve;
 * it is therefore movement-path evidence, not final-endpoint support.</p>
 *
 * <p>This value may contribute to:
 * supportingContactDuringMove, current-commit gameplay grounded continuity,
 * diagnostics and support-surface velocity evidence.</p>
 *
 * <p>It MUST NOT by itself establish:
 * terminalSupported, CompletedEndpointGround, RestingContactSnapshot,
 * TRUSTED_CURRENT_BLOCK_FACE, cross-tick PRESERVE_SUPPORT or a stable local
 * feature identity.</p>
 */
public record ExternalSubLevelSupportEvidence(
        UUID subLevelId,
        Vec3 normal,
        Vec3 surfaceVelocity,
        Vec3 representativePoint
) {
    private static final double NORMAL_EPSILON =
            1.0E-6D;

    public ExternalSubLevelSupportEvidence {
        Objects.requireNonNull(
                subLevelId,
                "subLevelId"
        );
        Objects.requireNonNull(
                normal,
                "normal"
        );
        Objects.requireNonNull(
                surfaceVelocity,
                "surfaceVelocity"
        );
        Objects.requireNonNull(
                representativePoint,
                "representativePoint"
        );

        requireFinite(normal, "normal");
        requireFinite(
                surfaceVelocity,
                "surfaceVelocity"
        );
        requireFinite(
                representativePoint,
                "representativePoint"
        );

        double lengthSquared =
                normal.lengthSqr();

        if (!(lengthSquared > 1.0E-24D)) {
            throw new IllegalArgumentException(
                    "support normal is zero"
            );
        }

        normal = normal.normalize();

        if (Math.abs(
                normal.lengthSqr() - 1.0D
        ) > NORMAL_EPSILON) {
            throw new IllegalArgumentException(
                    "support normal must normalize"
            );
        }
    }

    private static void requireFinite(
            Vec3 value,
            String name
    ) {
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: "
                            + value
            );
        }
    }
}
