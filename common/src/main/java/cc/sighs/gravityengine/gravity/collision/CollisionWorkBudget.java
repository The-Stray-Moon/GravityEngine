package cc.sighs.gravityengine.gravity.collision;

/**
 * Operation-local upper bounds for one collision operation.
 *
 * <p>A fresh budget is created for every outer movement operation and shared
 * by all query phases of that operation. Exceeding a bound is signaled one of
 * two ways, and both are handled by the owning boundary:</p>
 *
 * <ul>
 *   <li>tracker record methods return {@code false} and the owning route stops
 *       at the last proven legal body, never tunneling and never rewinding
 *       translation that an earlier stage already proved;</li>
 *   <li>recovery/voxel work throws {@link CollisionComplexityLimitException},
 *       which the geometry-transition boundary converts to
 *       {@code Result.FAILED} (and application-time transitions additionally
 *       roll back the entity).</li>
 * </ul>
 *
 * <p>The tracker itself never produces a fallback; the failure contract
 * belongs to the owning operation boundary.</p>
 */
public record CollisionWorkBudget(
        long maxBlockPositions,
        int maxObstaclePrimitives,
        int maxNarrowPhaseTests,
        int maxCollectedContacts,
        int maxRecoveryPasses,
        int maxVoxelReducerChecks,
        int maxVoxelIndexRegistrations
) {
    private static final int DEFAULT_VOXEL_REDUCER_CHECKS = 1_000_000;
    private static final int DEFAULT_VOXEL_INDEX_REGISTRATIONS = 262_144;

    public CollisionWorkBudget {
        if (maxBlockPositions <= 0
                || maxObstaclePrimitives <= 0
                || maxNarrowPhaseTests <= 0
                || maxCollectedContacts <= 0
                || maxRecoveryPasses <= 0
                || maxVoxelReducerChecks <= 0
                || maxVoxelIndexRegistrations <= 0) {
            throw new IllegalArgumentException(
                    "collision work budget bounds must be positive"
            );
        }
    }

    /**
     * Convenience constructor applying the default voxel-reducer bounds.
     */
    public CollisionWorkBudget(
            long maxBlockPositions,
            int maxObstaclePrimitives,
            int maxNarrowPhaseTests,
            int maxCollectedContacts,
            int maxRecoveryPasses
    ) {
        this(
                maxBlockPositions,
                maxObstaclePrimitives,
                maxNarrowPhaseTests,
                maxCollectedContacts,
                maxRecoveryPasses,
                DEFAULT_VOXEL_REDUCER_CHECKS,
                DEFAULT_VOXEL_INDEX_REGISTRATIONS
        );
    }

    /**
     * Production defaults. Deliberately generous but finite: they protect
     * against pathological worlds while never throttling ordinary movement.
     */
    public static CollisionWorkBudget defaults() {
        return new CollisionWorkBudget(
                262_144L,
                65_536,
                262_144,
                2_048,
                8,
                DEFAULT_VOXEL_REDUCER_CHECKS,
                DEFAULT_VOXEL_INDEX_REGISTRATIONS
        );
    }
}
