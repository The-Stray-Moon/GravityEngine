package cc.sighs.gravityengine.math;

/**
 * Internal scalar arithmetic helpers used by loader-neutral kernels.
 *
 * <p>This type is not part of the supported public API. The double clamp
 * behavior mirrors the Java 21 standard-library primitive while remaining
 * compilable against the Java 17 API baseline.</p>
 */
public final class ScalarMath {
    private ScalarMath() {}

    /**
     * Clamps {@code value} to the inclusive range {@code [min, max]}.
     *
     * @throws IllegalArgumentException when {@code min} or {@code max} is
     *         {@code NaN}, or when ordinary comparison does not put
     *         {@code min} before {@code max}
     */
    public static double clamp(double value, double min, double max) {
        /*
         * Match Math.clamp's validation order and signed-zero comparison
         * semantics, including its behavior when min and max are equal.
         */
        if (!(min < max)) {
            if (Double.isNaN(min)) {
                throw new IllegalArgumentException("min is NaN");
            }
            if (Double.isNaN(max)) {
                throw new IllegalArgumentException("max is NaN");
            }
            if (Double.compare(min, max) > 0) {
                throw new IllegalArgumentException(min + " > " + max);
            }
        }
        return Math.min(max, Math.max(min, value));
    }
}
