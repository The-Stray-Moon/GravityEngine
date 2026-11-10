package cc.sighs.gravityengine.gravity.collision;

/** Default-gravity energy scale. Speeds are blocks/tick, not displacements. */
public final class ImpactDamage {
    private ImpactDamage() {}

    public static float equivalentFallDistance(double approachSpeed) {
        if (!Double.isFinite(approachSpeed) || approachSpeed < 0)
            throw new IllegalArgumentException("invalid impact speed");
        double distance = 6.25D * approachSpeed * approachSpeed;
        if (distance > Float.MAX_VALUE) throw new IllegalArgumentException("impact exceeds finite damage range");
        return (float) distance;
    }
}
