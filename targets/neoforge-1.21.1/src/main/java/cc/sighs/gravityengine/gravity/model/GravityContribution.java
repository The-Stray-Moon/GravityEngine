package cc.sighs.gravityengine.gravity.model;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * One field instance's immutable contribution to a composed gravity sample.
 *
 * <p>The contribution describes the field only: source identity, the actual
 * world-space acceleration vector produced by that instance's evaluator, and
 * the instance revision.  It never carries a collision primitive; the source
 * sphere is registered by the collision scene independently.</p>
 */
public record GravityContribution(
        GravityFieldKey source,
        Vec3 accelerationVector,
        long revision
) {
    public GravityContribution {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(accelerationVector, "accelerationVector");
        if (!Double.isFinite(accelerationVector.x)
                || !Double.isFinite(accelerationVector.y)
                || !Double.isFinite(accelerationVector.z)) {
            throw new IllegalArgumentException(
                    "accelerationVector must be finite: "
                            + accelerationVector);
        }
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "revision must be non-negative: " + revision);
        }
    }
}
