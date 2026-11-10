package cc.sighs.gravityengine.gravity.runtime;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable movement evidence produced by one completed external SubLevel
 * solve before the parent-world collision solve begins.
 *
 * <p>Sable remains the authority for SubLevel discovery, transforms and
 * collision response. This value only transports the completed operands and
 * compatibility flags that GravityEngine may consume during the same
 * {@code Entity.move}. It never owns a live SubLevel, contact feature,
 * support authority or persistent velocity state.</p>
 *
 * <p>The {@code subLevelVerticalCollision} flags are Sable's world-axis
 * compatibility facts. They may be projected into final Vanilla-facing
 * compatibility fields, but GravityEngine must not promote them into arbitrary-
 * gravity terminal support or semantic gameplay ground without a proven
 * contact normal/witness bridge. Sable 2.0.5 still uses its own entity OBB
 * against SubLevel geometry while GravityEngine uses the gravity-axisymmetric
 * character capsule against parent-Level geometry; this evidence does not
 * claim those shapes are identical.</p>
 */
public record ExternalSubLevelMoveEvidence(
        Vec3 remainingMotion,
        Vec3 preMoveVelocity,
        boolean sableCompatibilityGround,
        boolean subLevelHorizontalCollision,
        boolean subLevelVerticalCollision,
        boolean subLevelVerticalCollisionBelow,
        boolean subLevelMinorHorizontalCollision,
        boolean hasSubLevelContact,
        boolean serverPlayerTrackingFastPath,
        boolean worldYVelocitySuppressed,
        Optional<UUID> trackingSubLevelId,
        Optional<UUID> preTrackingSubLevelId,
        Optional<ExternalSubLevelSupportEvidence> supportEvidence
) {
    public ExternalSubLevelMoveEvidence {
        requireFinite(remainingMotion, "remainingMotion");
        requireFinite(preMoveVelocity, "preMoveVelocity");
        trackingSubLevelId = Objects.requireNonNull(
                trackingSubLevelId,
                "trackingSubLevelId"
        );
        preTrackingSubLevelId = Objects.requireNonNull(
                preTrackingSubLevelId,
                "preTrackingSubLevelId"
        );
        supportEvidence =
                Objects.requireNonNull(
                        supportEvidence,
                        "supportEvidence"
                );
        if (worldYVelocitySuppressed && !serverPlayerTrackingFastPath) {
            throw new IllegalArgumentException(
                    "world-Y velocity suppression requires Sable's "
                            + "ServerPlayer tracking fast path"
            );
        }
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
