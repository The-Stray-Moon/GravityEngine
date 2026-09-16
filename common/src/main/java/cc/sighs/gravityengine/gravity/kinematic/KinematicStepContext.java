package cc.sighs.gravityengine.gravity.kinematic;

/**
 * Explicit simulation-time contract for one character movement operation.
 *
 * <p>Minecraft velocity/displacement is authored in blocks per tick, so the
 * interval is expressed in ticks rather than seconds. An ordinary per-tick
 * {@code Entity.move} uses {@code intervalTicks = 1.0}; a sub-tick or
 * interval-spanning operation supplies the real interval. The normalized
 * {@code [0, 1]} fractions used internally are always fractions of this
 * interval, never an implicit whole tick.</p>
 */
public record KinematicStepContext(
        long gameTick,
        double intervalTicks,
        long sceneRevision
) {
    public KinematicStepContext {
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be positive and finite: " + intervalTicks
            );
        }
    }

    public static KinematicStepContext fullTick(
            long gameTick,
            long sceneRevision
    ) {
        return new KinematicStepContext(gameTick, 1.0D, sceneRevision);
    }

    /**
     * Absolute elapsed ticks for a normalized {@code [0, 1]} fraction of this
     * interval. The normalized TOI is never re-scaled in the CCD code itself;
     * absolute offsets are computed here only when explicitly needed.
     */
    public double elapsedTicks(double normalizedFraction) {
        if (!Double.isFinite(normalizedFraction)
                || normalizedFraction < 0.0D
                || normalizedFraction > 1.0D) {
            throw new IllegalArgumentException(
                    "normalized fraction must be within [0, 1]: "
                            + normalizedFraction
            );
        }
        return normalizedFraction * this.intervalTicks;
    }
}
