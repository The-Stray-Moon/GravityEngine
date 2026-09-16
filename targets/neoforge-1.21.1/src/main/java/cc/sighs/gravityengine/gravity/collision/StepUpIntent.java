package cc.sighs.gravityengine.gravity.collision;

/**
 * Immutable, operation-local traversal intent captured at the collision
 * boundary.
 *
 * <p>Two rise budgets are deliberately distinct:</p>
 *
 * <ul>
 *   <li>{@code maxStepHeight}: the entity-authored vanilla-style step height;</li>
 *   <li>{@code terrainRiseBudget}: the maximum rise available to the bounded
 *       custom-gravity static-voxel terrain traversal route.</li>
 * </ul>
 *
 * <p>No entity, level, previous-support, or cross-tick state is retained.
 * Whether a step may start is decided by the collision operation's own
 * initial support probe ({@code GravityGroundProbe} over the same
 * {@code CollisionScene}), never by a persisted vanilla-style
 * {@code onGround} flag.</p>
 */
public record StepUpIntent(
        double maxStepHeight,
        double terrainRiseBudget
) {
    public StepUpIntent {
        requireNonNegativeFinite(
                maxStepHeight,
                "maxStepHeight"
        );
        requireNonNegativeFinite(
                terrainRiseBudget,
                "terrainRiseBudget"
        );
    }

    /**
     * Authored-step intent without an explicit terrain traversal budget:
     * terrain rise stays bounded by the authored step height.
     */
    public StepUpIntent(double maxStepHeight) {
        this(maxStepHeight, maxStepHeight);
    }

    /**
     * Explicit non-default-gravity static-voxel allowance.
     *
     * <p>The collision integration selects authored-height intent for world-down
     * gravity; exact-body ownership alone does not grant this allowance.</p>
     */
    public static StepUpIntent customGravity(
            double authoredStepHeight
    ) {
        double authored =
                Math.max(0.0D, authoredStepHeight);

        return new StepUpIntent(
                authored,
                TerrainTraversalPolicy.customGravityTerrainRiseBudget(
                        authored
                )
        );
    }

    public static StepUpIntent disabled() {
        return new StepUpIntent(
                0.0D,
                0.0D
        );
    }

    private static void requireNonNegativeFinite(
            double value,
            String name
    ) {
        if (!Double.isFinite(value)
                || value < 0.0D) {
            throw new IllegalArgumentException(
                    name
                            + " must be finite and non-negative: "
                            + value
            );
        }
    }
}
