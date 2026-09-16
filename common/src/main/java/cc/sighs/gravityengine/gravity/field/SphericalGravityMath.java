package cc.sighs.gravityengine.gravity.field;

/** Pure physical derivations shared by spherical field construction and evaluation. */
public final class SphericalGravityMath {
    private SphericalGravityMath() {}

    public static double uniformDensityRadius(double absoluteMass, double referenceDensity) {
        requireNonNegative(absoluteMass, "absoluteMass");
        requirePositive(referenceDensity, "referenceDensity");
        if (absoluteMass == 0.0D) return 0.0D;
        double volume = (3.0D / (4.0D * Math.PI)) * absoluteMass / referenceDensity;
        return Double.isFinite(volume) && volume > 0.0D ? Math.cbrt(volume)
                : finiteExp((Math.log(absoluteMass) - Math.log(referenceDensity)
                        + Math.log(3.0D / (4.0D * Math.PI))) / 3.0D);
    }

    public static double uniformDensity(double absoluteMass, double radius) {
        requireNonNegative(absoluteMass, "absoluteMass");
        requirePositive(radius, "radius");
        if (absoluteMass == 0.0D) return 0.0D;
        double density = (3.0D / (4.0D * Math.PI)) * absoluteMass / radius / radius / radius;
        return Double.isFinite(density) && density > 0.0D ? density
                : finiteExp(Math.log(absoluteMass) - 3.0D * Math.log(radius)
                        + Math.log(3.0D / (4.0D * Math.PI)));
    }

    public static double surfaceAccelerationMagnitude(double gravityConstant, double mass, double surfaceRadius) {
        requireNonNegative(gravityConstant, "gravityConstant");
        requireFinite(mass, "mass");
        requirePositive(surfaceRadius, "surfaceRadius");
        if (mass == 0.0D || gravityConstant == 0.0D) return 0.0D;
        double acceleration = gravityConstant * (Math.abs(mass) / surfaceRadius) / surfaceRadius;
        return Double.isFinite(acceleration) && acceleration > 0.0D ? acceleration
                : finiteExp(Math.log(gravityConstant) + Math.log(Math.abs(mass))
                        - 2.0D * Math.log(surfaceRadius));
    }

    /** Exterior inverse-square cutoff; the physical surface always remains included. */
    public static double cutoffRadius(double gravityConstant, double mass, double surfaceRadius,
                                      double minimumFieldAcceleration) {
        requireNonNegative(gravityConstant, "gravityConstant");
        requireFinite(mass, "mass");
        requirePositive(surfaceRadius, "surfaceRadius");
        requirePositive(minimumFieldAcceleration, "minimumFieldAcceleration");
        if (mass == 0.0D || gravityConstant == 0.0D) return surfaceRadius;
        double squared = gravityConstant * Math.abs(mass) / minimumFieldAcceleration;
        double cutoff = Double.isFinite(squared) && squared > 0.0D ? Math.sqrt(squared)
                : finiteExp((Math.log(gravityConstant) + Math.log(Math.abs(mass))
                        - Math.log(minimumFieldAcceleration)) / 2.0D);
        return Math.max(surfaceRadius, cutoff);
    }

    /** Numeric safety for unrepresentable positive derivations: saturate, never emit infinity. */
    private static double finiteExp(double logarithm) {
        return Math.exp(Math.min(Math.log(Double.MAX_VALUE), logarithm));
    }

    public static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite: " + value);
    }

    public static void requirePositive(double value, String name) {
        requireFinite(value, name);
        if (value <= 0.0D) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    public static void requireNonNegative(double value, String name) {
        requireFinite(value, name);
        if (value < 0.0D) throw new IllegalArgumentException(name + " must be non-negative: " + value);
    }
}
