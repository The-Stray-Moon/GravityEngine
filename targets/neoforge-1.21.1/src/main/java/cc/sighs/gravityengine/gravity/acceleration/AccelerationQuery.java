package cc.sighs.gravityengine.gravity.acceleration;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Immutable inputs for one acceleration resolution at a physics boundary. */
public record AccelerationQuery(
        Entity entity,
        Vec3 samplePoint,
        Vec3 velocity,
        long gameTick,
        double intervalTicks
) {
    public AccelerationQuery {
        Objects.requireNonNull(entity, "entity");
        requireFinite(samplePoint, "samplePoint");
        requireFinite(velocity, "velocity");
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException("intervalTicks must be finite and positive: " + intervalTicks);
        }
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
