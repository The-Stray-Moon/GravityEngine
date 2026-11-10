package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.ScalarMath;
import java.util.Objects;

/**
 * Immutable spherical enclosed-mass acceleration field for one physical
 * core.
 *
 * <p>Persistent physical authority remains with the owning core/source
 * ({@code mass}, {@code referenceDensity}, {@code surfaceRadius}); this
 * evaluator captures those values in an immutable descriptor. Outside the
 * physical surface, acceleration follows {@code G*M/r^2}; inside, it follows
 * {@code G*M_enclosed(r)/r^2}. The uniform-density special case therefore
 * has acceleration proportional to radius, with finite zero acceleration at
 * the exact center.</p>
 *
 * <p>{@code referenceDensity} is the profile's shape reference, not a
 * pointwise material density and not necessarily the sphere's mean density.
 * Passing {@link SphericalGravityMath#uniformDensity(double, double)} for the
 * configured mass and surface radius selects the uniform profile. Other
 * positive values change the interior enclosed-mass distribution while the
 * exterior field still converges to {@code G*M/r^2} for the same total signed
 * mass.</p>
 *
 * <p>The evaluator only maps a query to acceleration. Activation and the
 * threshold-derived finite influence volume belong to the registered
 * instance/source descriptor.</p>
 */
public final class SphericalMassGravityField implements GravityField {
    private static final double CENTER_EPSILON_SQUARED = 1.0E-24D;
    private static final double[] SERIES_COEFFICIENTS = seriesCoefficients();
    private final Vec3d center;
    private final double mass;
    private final double referenceDensity;
    private final double surfaceRadius;
    private final double gravityConstant;
    private final double signedGravitationalParameter;
    private final double surfaceRadiusSquared;
    private final double profileK;
    private final double profileKCubed;
    private final double profileInverseNormalization;

    public SphericalMassGravityField(Vec3d center, double mass, double referenceDensity,
                                     double surfaceRadius, double gravityConstant) {
        Objects.requireNonNull(center, "center");
        SphericalGravityMath.requireFinite(center.x(), "center.x");
        SphericalGravityMath.requireFinite(center.y(), "center.y");
        SphericalGravityMath.requireFinite(center.z(), "center.z");
        SphericalGravityMath.requireFinite(mass, "mass");
        SphericalGravityMath.requirePositive(referenceDensity, "referenceDensity");
        SphericalGravityMath.requirePositive(surfaceRadius, "surfaceRadius");
        SphericalGravityMath.requireNonNegative(gravityConstant, "gravityConstant");
        this.center = center;
        this.mass = mass;
        this.referenceDensity = referenceDensity;
        this.surfaceRadius = surfaceRadius;
        this.gravityConstant = gravityConstant;
        this.signedGravitationalParameter = gravityConstant * mass;
        this.surfaceRadiusSquared = surfaceRadius * surfaceRadius;
        /*
         * Normalized radial density is proportional to exp(-k*x), where k is
         * log(reference/mean). Reference density controls shape; it is not a
         * pointwise material density. Uniform density is the k == 0 branch.
         */
        double shape = mass == 0.0D ? 0.0D
                : Math.log(referenceDensity) - Math.log(Math.abs(mass))
                + 3.0D * Math.log(surfaceRadius)
                + Math.log(4.0D * Math.PI / 3.0D);
        this.profileK = Math.abs(shape) < 1.0E-12D
                ? 0.0D : ScalarMath.clamp(shape, -64.0D, 64.0D);
        this.profileKCubed = this.profileK * this.profileK * this.profileK;
        this.profileInverseNormalization = 1.0D / profileIntegral(1.0D);
        // Reject unrepresentable field scales at construction, never in ordinary sampling.
        SphericalGravityMath.requireFinite(this.signedGravitationalParameter, "G*mass");
    }

    @Override
    public GravityFieldSample sample(GravityFieldQuery query) {
        Objects.requireNonNull(query, "query");
        if (mass == 0.0D || gravityConstant == 0.0D) return GravityFieldSample.ZERO;
        Vec3d radial = center.subtract(query.position());
        double r2 = radial.lengthSquared();
        if (r2 <= CENTER_EPSILON_SQUARED) return GravityFieldSample.ZERO;
        // Finite game positions normally take the squared-length path. Hypot avoids
        // overflow for unusually distant queries without changing world-space direction.
        double r = Double.isFinite(r2) ? Math.sqrt(r2)
                : Math.hypot(Math.hypot(radial.x(), radial.y()), radial.z());
        if (!Double.isFinite(r)) return GravityFieldSample.ZERO;
        double fraction = r2 >= surfaceRadiusSquared && r >= surfaceRadius ? 1.0D
                : enclosedMassFraction(r / surfaceRadius);
        double magnitude = signedGravitationalParameter * fraction / r / r;
        // Saturate only unrepresentable acceleration; avoid 0 * infinity on axis samples.
        magnitude = ScalarMath.clamp(magnitude, -Double.MAX_VALUE, Double.MAX_VALUE);
        return new GravityFieldSample(
                radial.multiply(1.0D / r).multiply(magnitude));
    }

    public Vec3d center() { return center; }

    /**
     * Normalized enclosed-mass fraction at {@code x = r / surfaceRadius}.
     * Package-visible for focused kernel tests; production consumers use
     * {@link #sample(GravityFieldQuery)}.
     */
    double enclosedMassFraction(double x) {
        if (Double.isNaN(x)) throw new IllegalArgumentException("x must not be NaN");
        if (x <= 0.0D) return 0.0D;
        if (x >= 1.0D) return 1.0D;
        if (this.profileK == 0.0D) return x * x * x;
        return ScalarMath.clamp(
                profileIntegral(x) * this.profileInverseNormalization,
                0.0D,
                1.0D
        );
    }

    private double profileIntegral(double x) {
        double t = profileK * x;
        /*
         * Cancellation depends on k*x, not just k: protect near-center
         * samples too. Analytic Taylor polynomial sum (-t)^n / (n! * (n+3)),
         * through n=16. At |t| < 0.5 the omitted terms are below double
         * precision.
         */
        if (Math.abs(t) < 0.5D) {
            double sum = SERIES_COEFFICIENTS[16];
            for (int n = 15; n >= 0; n--) {
                sum = sum * t + SERIES_COEFFICIENTS[n];
            }
            return x * x * x * sum;
        }
        return (2.0D - Math.exp(-t) * (t * t + 2.0D * t + 2.0D))
                / this.profileKCubed;
    }

    private static double[] seriesCoefficients() {
        double[] coefficients = new double[17];
        double signedInverseFactorial = 1.0D;
        coefficients[0] = 1.0D / 3.0D;
        for (int n = 1; n < coefficients.length; n++) {
            signedInverseFactorial /= -n;
            coefficients[n] = signedInverseFactorial / (n + 3.0D);
        }
        return coefficients;
    }

    // Exact input equality preserves source-index idempotent reconstruction semantics.
    @Override
    public boolean equals(Object other) {
        return other instanceof SphericalMassGravityField field
                && center.equals(field.center)
                && Double.compare(mass, field.mass) == 0
                && Double.compare(referenceDensity, field.referenceDensity) == 0
                && Double.compare(surfaceRadius, field.surfaceRadius) == 0
                && Double.compare(gravityConstant, field.gravityConstant) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(center, mass, referenceDensity, surfaceRadius, gravityConstant);
    }
}
