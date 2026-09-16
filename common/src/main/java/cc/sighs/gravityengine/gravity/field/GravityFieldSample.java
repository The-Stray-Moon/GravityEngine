package cc.sighs.gravityengine.gravity.field;

import org.joml.Vector3d;

import java.util.Objects;

/**
 * Pure acceleration result of one field evaluation.
 *
 * <p>The stored {@code Vector3d} is a private defensive copy; the accessor
 * returns a fresh copy so callers can never mutate evaluator state.</p>
 */
public record GravityFieldSample(Vector3d acceleration) {

    public GravityFieldSample {
        Objects.requireNonNull(acceleration, "acceleration");
        if (!Double.isFinite(acceleration.x)
                || !Double.isFinite(acceleration.y)
                || !Double.isFinite(acceleration.z)) {
            throw new IllegalArgumentException(
                    "acceleration must be finite: " + acceleration);
        }
        acceleration = new Vector3d(acceleration);
    }

    public static final GravityFieldSample ZERO =
            new GravityFieldSample(new Vector3d());

    @Override
    public Vector3d acceleration() {
        return new Vector3d(acceleration);
    }
}
