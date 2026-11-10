package cc.sighs.gravityengine.gravity.kinematic;

/**
 * Operation-local sweep sub-interval, expressed in absolute tick time.
 *
 * <p>One movement operation owns exactly one time interval. Every bump, step,
 * vertical/tangent segment and support probe consumes this same time cursor:
 * no segment may restart obstacle time at the operation start or pretend it
 * has the whole remaining tick available.</p>
 *
 * <p>{@code startTicks} and {@code durationTicks} are absolute elapsed ticks
 * from the operation start; normalized TOI fractions returned by the narrow
 * phase are converted through {@link #remainingAfter(double)} so obstacle
 * motion between bumps stays continuous.</p>
 */
public record SweepTimeWindow(
        double startTicks,
        double durationTicks
) {
    public SweepTimeWindow {
        if (!Double.isFinite(startTicks) || startTicks < 0.0D) {
            throw new IllegalArgumentException(
                    "startTicks must be finite and non-negative: "
                            + startTicks
            );
        }
        if (!Double.isFinite(durationTicks) || durationTicks < 0.0D) {
            throw new IllegalArgumentException(
                    "durationTicks must be finite and non-negative: "
                            + durationTicks
            );
        }
    }

    /** The whole operation interval starting at zero elapsed ticks. */
    public static SweepTimeWindow full(
            KinematicStepContext context
    ) {
        return new SweepTimeWindow(
                0.0D,
                context.intervalTicks()
        );
    }

    public double endTicks() {
        return startTicks + durationTicks;
    }

    /** Window remaining after advancing by a normalized TOI fraction. */
    public SweepTimeWindow remainingAfter(
            double normalizedToi
    ) {
        if (!Double.isFinite(normalizedToi)
                || normalizedToi < 0.0D
                || normalizedToi > 1.0D) {
            throw new IllegalArgumentException(
                    "normalizedToi must be in [0,1]: "
                            + normalizedToi
            );
        }

        double elapsed = durationTicks * normalizedToi;

        return new SweepTimeWindow(
                startTicks + elapsed,
                durationTicks - elapsed
        );
    }

    /** Explicit normalized sub-segment of this window. */
    public SweepTimeWindow segment(
            double normalizedStart,
            double normalizedEnd
    ) {
        if (normalizedStart < 0.0D
                || normalizedEnd < normalizedStart
                || normalizedEnd > 1.0D) {
            throw new IllegalArgumentException(
                    "invalid normalized segment"
            );
        }

        return new SweepTimeWindow(
                startTicks + durationTicks * normalizedStart,
                durationTicks
                        * (normalizedEnd - normalizedStart)
        );
    }

    /** Zero-duration window at the current start; used by instant probes. */
    public SweepTimeWindow instantAtStart() {
        return new SweepTimeWindow(startTicks, 0.0D);
    }

    /** Zero-duration window at the current end; used by final contact rebuilds. */
    public SweepTimeWindow instantAtEnd() {
        return new SweepTimeWindow(endTicks(), 0.0D);
    }

    /**
     * Normalized start fraction of this window inside a scene whose operation
     * interval is {@code intervalTicks} ticks.
     */
    public double normalizedStartTicks(
            double intervalTicks
    ) {
        if (!Double.isFinite(intervalTicks)
                || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be positive and finite: "
                            + intervalTicks
            );
        }
        double end = endTicks();
        if (end > intervalTicks + 1.0E-9D) {
            throw new IllegalArgumentException(
                    "sweep window exceeds the operation interval: window="
                            + this
                            + ", intervalTicks="
                            + intervalTicks
            );
        }
        return Math.min(
                1.0D,
                Math.max(
                        0.0D,
                        startTicks / intervalTicks
                )
        );
    }

    /**
     * Normalized duration fraction of this window inside a scene whose
     * operation interval is {@code intervalTicks} ticks.
     */
    public double normalizedDurationTicks(
            double intervalTicks
    ) {
        if (!Double.isFinite(intervalTicks)
                || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be positive and finite: "
                            + intervalTicks
            );
        }
        double end = endTicks();
        if (end > intervalTicks + 1.0E-9D) {
            throw new IllegalArgumentException(
                    "sweep window exceeds the operation interval: window="
                            + this
                            + ", intervalTicks="
                            + intervalTicks
            );
        }
        double duration = durationTicks / intervalTicks;
        if (duration < 0.0D) {
            duration = 0.0D;
        }
        return duration;
    }
}
