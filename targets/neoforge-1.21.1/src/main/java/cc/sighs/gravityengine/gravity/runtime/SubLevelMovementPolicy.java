package cc.sighs.gravityengine.gravity.runtime;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Pure merge rules for one hybrid Sable SubLevel + GravityEngine parent-world
 * movement.
 *
 * <p>The parent-world/transport endpoint result remains authoritative for
 * exact terminal support. Sable may contribute completed gravity-relative
 * path-contact evidence to supportingContactDuringMove and same-commit
 * gameplay-grounded continuity, but Sable 2.0.5 FirstCollisionInfo is not a
 * final endpoint witness and never becomes persistent resting support.</p>
 */
public final class SubLevelMovementPolicy {
    private static final double AXIS_EPSILON_SQUARED = 1.0E-24D;

    private SubLevelMovementPolicy() {}

    /**
     * The parent-world solve consumes the completed SubLevel result exactly.
     * It must never reconstruct a request from the outer displacement.
     */
    public static Vec3 parentWorldSolveInput(
            Vec3 actualRemainingMotion,
            ExternalSubLevelMoveEvidence external
    ) {
        return external == null
                ? actualRemainingMotion
                : external.remainingMotion();
    }

    public static VanillaCollisionState mergeParentWorldState(
            VanillaCollisionState parentWorldState,
            ExternalSubLevelMoveEvidence external
    ) {
        if (external == null) {
            return parentWorldState;
        }
        return new VanillaCollisionState(
                parentWorldState.onGround()
                        || external.sableCompatibilityGround(),
                parentWorldState.horizontalCollision()
                        || external.subLevelHorizontalCollision(),
                parentWorldState.verticalCollision()
                        || external.subLevelVerticalCollision(),
                parentWorldState.verticalCollisionBelow()
                        || external.sableCompatibilityGround(),
                parentWorldState.minorHorizontalCollision()
                        || external.subLevelMinorHorizontalCollision(),
                parentWorldState.mainSupportingBlockPos()
        );
    }

    public static Vec3 preserveExternalContactVelocity(
            Vec3 currentVelocity,
            ExternalSubLevelMoveEvidence external,
            Vec3 gravityUp
    ) {
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        Objects.requireNonNull(gravityUp, "gravityUp");

        if (external == null || !external.worldYVelocitySuppressed()) {
            return currentVelocity;
        }

        double upLengthSquared = gravityUp.lengthSqr();

        if (!Double.isFinite(upLengthSquared)
                || !(upLengthSquared > AXIS_EPSILON_SQUARED)) {
            throw new IllegalArgumentException(
                    "gravityUp must be finite and non-zero: " + gravityUp
            );
        }

        Vec3 up = gravityUp.scale(
                1.0D / Math.sqrt(upLengthSquared)
        );

        Vec3 removed =
                external.preMoveVelocity()
                        .subtract(currentVelocity);

        double removedVertical = removed.dot(up);

        Vec3 tangentRemoved =
                removed.subtract(
                        up.scale(removedVertical)
                );

        if (tangentRemoved.lengthSqr()
                <= AXIS_EPSILON_SQUARED) {
            return currentVelocity;
        }

        return currentVelocity.add(tangentRemoved);
    }

    /**
     * Decides whether Sable's parent-world world-Y comparison removed a
     * tracking body that its own SubLevel solve still selected.
     */
    public static boolean shouldRestoreTracking(
            boolean selectedTrackingPresent,
            boolean selectedVerticalBelow,
            boolean preTrackingPresent,
            boolean currentlyTracking,
            boolean serverPlayer,
            boolean selectedTrackingRemoved
    ) {
        if (currentlyTracking || selectedTrackingRemoved) {
            return false;
        }
        if (selectedTrackingPresent) {
            return selectedVerticalBelow || preTrackingPresent;
        }
        return serverPlayer && preTrackingPresent;
    }

    /**
     * Sable 2.0.5 FirstCollisionInfo is path-contact evidence only.
     *
     * <p>A valid gravity-relative support normal proves that a supporting
     * SubLevel contact occurred during this movement, but it does not prove that
     * the character still touches that surface at the final endpoint.</p>
     */
    public static boolean externalSupportingContactDuringMove(
            ExternalSubLevelMoveEvidence external
    ) {
        return external != null
                && external.supportEvidence()
                .isPresent();
    }
}
