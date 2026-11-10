package cc.sighs.gravityengine.attitude.presentation;

/** Client-only bounds for remote entity tick approach. */
public record BodyAttitudeVisualConfigSnapshot(double remoteInterpolationMinTicks, double remoteInterpolationMaxTicks) {
    public static final BodyAttitudeVisualConfigSnapshot DEFAULT = new BodyAttitudeVisualConfigSnapshot(1, 4);
    public BodyAttitudeVisualConfigSnapshot {
        if (!Double.isFinite(remoteInterpolationMinTicks) || !Double.isFinite(remoteInterpolationMaxTicks)
                || remoteInterpolationMinTicks <= 0 || remoteInterpolationMaxTicks < remoteInterpolationMinTicks) {
            throw new IllegalArgumentException("invalid remote attitude lerp bounds");
        }
    }
}
