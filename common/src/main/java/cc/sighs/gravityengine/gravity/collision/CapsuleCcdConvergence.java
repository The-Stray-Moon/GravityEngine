package cc.sighs.gravityengine.gravity.collision;

/**
 * Bounded one-dimensional fallback for capsule conservative advancement.
 *
 * <p>The signed distance from a convex capsule region to a convex obstacle is
 * a convex scalar function of normalized translation time, so the fallback can
 * locate its minimum with a fixed bounded ternary search and then bracket the
 * earliest root with bounded bisection. It is used only when the normal
 * conservative-advancement loop hits a numerical boundary (tiny remaining
 * interval, sub-epsilon tangent root, or iteration budget).</p>
 *
 * <p>The result is an explicit three-state outcome: {@link Status#HIT},
 * {@link Status#NO_HIT}, or {@link Status#INDETERMINATE}. A NaN/infinite
 * scalar sample is always {@link Status#INDETERMINATE}, never a miss.</p>
 */
final class CapsuleCcdConvergence {
    private static final int MINIMIZATION_ITERATIONS = 40;
    private static final int ROOT_ITERATIONS = 60;
    private static final double INVALID_TOI = -1.0D;

    private CapsuleCcdConvergence() {}

    /**
     * Numerical CCD/current-contact boundary shared by every dedicated capsule pair.
     *
     * <p>This is not strict occupancy and not meaningful penetration. A CCD endpoint
     * accepted inside this convergence band must classify as current TOUCHING when
     * resampled, otherwise endpoint contact can disappear between movement and
     * velocity/contact revalidation.</p>
     */
    static boolean reachedContactBand(double signedGap) {
        return signedGap - CollisionTolerances.CONTACT_SLOP
                <= CollisionTolerances.CCD_CONVERGENCE_GAP;
    }

    /** Samples the signed gap at a normalized translation time. */
    @FunctionalInterface
    interface GapSampler {
        double signedGapAt(double time);
    }

    enum Status {
        HIT,
        NO_HIT,
        INDETERMINATE
    }

    /**
     * Typed fallback outcome. {@code timeOfImpact} is meaningful only for
     * {@link Status#HIT}; for NO_HIT/INDETERMINATE it is an out-of-range
     * sentinel so the states are never conflated.
     */
    record Result(
            Status status,
            double timeOfImpact,
            int minimumSamples,
            int rootSamples,
            String diagnostic
    ) {
        static Result hit(double toi, int minimumSamples, int rootSamples) {
            if (!Double.isFinite(toi) || toi < 0.0D || toi > 1.0D) {
                throw new IllegalArgumentException("HIT TOI must be in [0,1]: " + toi);
            }
            return new Result(Status.HIT, toi, minimumSamples, rootSamples, "");
        }

        static Result noHit(int minimumSamples, int rootSamples) {
            return new Result(Status.NO_HIT, INVALID_TOI, minimumSamples, rootSamples, "");
        }

        static Result indeterminate(String diagnostic, int minimumSamples, int rootSamples) {
            return new Result(
                    Status.INDETERMINATE, INVALID_TOI, minimumSamples, rootSamples, diagnostic
            );
        }

        boolean isHit() { return status == Status.HIT; }
        boolean isNoHit() { return status == Status.NO_HIT; }
        boolean isIndeterminate() { return status == Status.INDETERMINATE; }
    }

    /** Truthful conservative-advancement iteration/fallback reporting. */
    record Diagnostics(
            int conservativeAdvanceIterations,
            boolean usedFallback,
            int fallbackMinimumSamples,
            int fallbackRootSamples
    ) {
        static Diagnostics normal(int iterations) {
            return new Diagnostics(iterations, false, 0, 0);
        }

        static Diagnostics fallback(int iterations, int minimumSamples, int rootSamples) {
            return new Diagnostics(iterations, true, minimumSamples, rootSamples);
        }
    }

    /**
     * Returns the earliest normalized time in {@code [from, to]} at which the
     * signed gap reaches the contact boundary, with an explicit typed outcome.
     */
    static Result findEarliestContact(GapSampler sampler, double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to)) {
            return Result.indeterminate(
                    "non-finite interval; from=" + from + " to=" + to, 0, 0
            );
        }
        final double fixedFrom = clamp(from, 0.0D, 1.0D);
        final double fixedTo = clamp(to, 0.0D, 1.0D);
        if (fixedTo < fixedFrom) {
            return Result.indeterminate(
                    "reversed interval [" + from + ", " + to + "]", 0, 0
            );
        }

        if (fixedTo == fixedFrom) {
            double gap = sampler.signedGapAt(fixedFrom);
            if (!Double.isFinite(gap)) {
                return Result.indeterminate(
                        nonFinite("initial", fixedFrom, gap, fixedFrom, fixedTo), 1, 0
                );
            }
            return reachedContactBand(gap)
                    ? Result.hit(fixedFrom, 1, 0)
                    : Result.noHit(1, 0);
        }

        MinimumSearch minimum = locateMinimum(sampler, fixedFrom, fixedTo);
        if (minimum.indeterminate() != null) {
            return Result.indeterminate(minimum.indeterminate(), minimum.samples(), 0);
        }
        double minimumGap = sampler.signedGapAt(minimum.time());
        if (!Double.isFinite(minimumGap)) {
            return Result.indeterminate(
                    nonFinite("minimum", minimum.time(), minimumGap, fixedFrom, fixedTo),
                    minimum.samples() + 1, 0
            );
        }
        if (!reachedContactBand(minimumGap)) {
            return Result.noHit(minimum.samples(), 0);
        }
        double minimumSafeGap = minimumGap - CollisionTolerances.CONTACT_SLOP;
        if (minimumSafeGap > CollisionTolerances.CCD_CONVERGENCE_GAP) {
            return Result.noHit(minimum.samples(), 0);
        }

        double startGap = sampler.signedGapAt(fixedFrom);
        if (!Double.isFinite(startGap)) {
            return Result.indeterminate(
                    nonFinite("initial", fixedFrom, startGap, fixedFrom, fixedTo),
                    minimum.samples() + 1, 0
            );
        }
        if (reachedContactBand(startGap)) {
            return Result.hit(fixedFrom, minimum.samples(), 0);
        }
        double startSafeGap = startGap - CollisionTolerances.CONTACT_SLOP;
        if (startSafeGap <= CollisionTolerances.CCD_CONVERGENCE_GAP) {
            return Result.hit(fixedFrom, minimum.samples(), 0);
        }

        int rootSamples = 0;
        double low = fixedFrom;
        double high = minimum.time();
        if (high <= low + CollisionTolerances.TOI_EPSILON) {
            return Result.hit(high, minimum.samples(), rootSamples);
        }
        for (int iteration = 0; iteration < ROOT_ITERATIONS; iteration++) {
            double mid = 0.5D * (low + high);
            double gap = sampler.signedGapAt(mid);
            rootSamples++;
            if (!Double.isFinite(gap)) {
                return Result.indeterminate(
                        nonFinite("root", mid, gap, fixedFrom, fixedTo),
                        minimum.samples(), rootSamples
                );
            }
            if (reachedContactBand(gap)) {
                high = mid;
            } else {
                low = mid;
            }
            double safeGap = gap - CollisionTolerances.CONTACT_SLOP;
            if (safeGap <= CollisionTolerances.CCD_CONVERGENCE_GAP) {
                high = mid;
            } else {
                low = mid;
            }
            if (high - low <= CollisionTolerances.TOI_EPSILON) break;
        }
        return Result.hit(0.5D * (low + high), minimum.samples(), rootSamples);
    }

    /**
     * Bounded ternary search for the minimum of a convex function. Returns an
     * indeterminate marker if any sample is non-finite.
     */
    private static MinimumSearch locateMinimum(GapSampler sampler, double from, double to) {
        double low = from;
        double high = to;
        int samples = 0;
        for (int iteration = 0; iteration < MINIMIZATION_ITERATIONS; iteration++) {
            double first = low + (high - low) / 3.0D;
            double second = high - (high - low) / 3.0D;
            double firstGap = sampler.signedGapAt(first);
            samples++;
            if (!Double.isFinite(firstGap)) {
                return new MinimumSearch(
                        Double.NaN, samples,
                        nonFinite("minimum-first", first, firstGap, from, to)
                );
            }
            double secondGap = sampler.signedGapAt(second);
            samples++;
            if (!Double.isFinite(secondGap)) {
                return new MinimumSearch(
                        Double.NaN, samples,
                        nonFinite("minimum-second", second, secondGap, from, to)
                );
            }
            if (firstGap < secondGap) {
                high = second;
            } else {
                low = first;
            }
        }
        return new MinimumSearch(0.5D * (low + high), samples, null);
    }

    private static String nonFinite(
            String stage,
            double time,
            double value,
            double from,
            double to
    ) {
        return "stage=" + stage + " time=" + time + " value=" + value
                + " interval=[" + from + ", " + to + "]";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record MinimumSearch(double time, int samples, String indeterminate) {}
}
