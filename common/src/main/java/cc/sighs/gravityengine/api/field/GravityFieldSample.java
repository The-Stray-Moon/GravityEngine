package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Pure acceleration result of one field evaluation.
 *
 * <p>The stored {@link Vec3d} is immutable, so callers can never mutate
 * evaluator state through the accessor.</p>
 *
 * <p>{@link #ZERO} is a valid field result. It means "this evaluator produced
 * no acceleration here", not "this field is absent". Whether the field
 * participates in composition is decided by the registered instance and its
 * influence volume.</p>
 */
public record GravityFieldSample(Vec3d acceleration) {

    public GravityFieldSample {
        Objects.requireNonNull(acceleration, "acceleration");
        if (!acceleration.isFinite()) {
            throw new IllegalArgumentException(
                    "acceleration must be finite: " + acceleration);
        }
    }

    public static final GravityFieldSample ZERO =
            new GravityFieldSample(Vec3d.ZERO);
}
