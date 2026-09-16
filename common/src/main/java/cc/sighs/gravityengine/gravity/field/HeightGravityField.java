package cc.sighs.gravityengine.gravity.field;

import org.joml.Vector3d;

/**
 * Pure absolute world-space acceleration field aligned with world -Y.
 *
 * <p>Samples at or below {@code fullGravityY} return
 * {@code (0, -accelerationMagnitude, 0)}; samples in the open interval
 * between {@code fullGravityY} and {@code zeroGravityY} scale the magnitude
 * with a cubic smoothstep fade; samples at or above {@code zeroGravityY}
 * return a zero vector.</p>
 *
 * <p>The canonical Overworld registration uses magnitude 0.08 with
 * {@code fullGravityY = 200} and {@code zeroGravityY = 300}. A zero-valued
 * high-altitude sample is still an active contribution because field
 * presence belongs to the registered instance and its influence volume, not
 * to evaluator magnitude. This evaluator is not an entity-specific Vanilla
 * gravity multiplier; registration and activation are handled externally.</p>
 */
public final class HeightGravityField implements GravityField {
    private final double fullGravityY;
    private final double zeroGravityY;
    private final double accelerationMagnitude;

    public HeightGravityField(double fullGravityY, double zeroGravityY,
                              double accelerationMagnitude) {
        if (!Double.isFinite(fullGravityY) || !Double.isFinite(zeroGravityY)
                || zeroGravityY <= fullGravityY) {
            throw new IllegalArgumentException("heights must be finite with zeroGravityY > fullGravityY");
        }
        if (!Double.isFinite(accelerationMagnitude) || accelerationMagnitude < 0.0D) {
            throw new IllegalArgumentException("accelerationMagnitude must be finite and non-negative");
        }
        this.fullGravityY = fullGravityY;
        this.zeroGravityY = zeroGravityY;
        this.accelerationMagnitude = accelerationMagnitude;
    }

    @Override
    public GravityFieldSample sample(GravityFieldQuery query) {
        double y = query.position().y();
        if (y <= fullGravityY) {
            return new GravityFieldSample(new Vector3d(0.0D, -accelerationMagnitude, 0.0D));
        }
        if (y >= zeroGravityY) {
            return GravityFieldSample.ZERO;
        }
        double t = (y - fullGravityY) / (zeroGravityY - fullGravityY);
        t = Math.clamp(t, 0.0D, 1.0D);
        double smooth = t * t * (3.0D - 2.0D * t);
        double magnitude = accelerationMagnitude * (1.0D - smooth);
        return new GravityFieldSample(new Vector3d(0.0D, -magnitude, 0.0D));
    }
}
