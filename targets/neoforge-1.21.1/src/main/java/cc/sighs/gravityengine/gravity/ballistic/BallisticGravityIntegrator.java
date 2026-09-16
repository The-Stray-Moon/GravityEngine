package cc.sighs.gravityengine.gravity.ballistic;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Pure acceleration integration. Drag, collision and control are external. */
public final class BallisticGravityIntegrator {
    private BallisticGravityIntegrator() {}

    public static Vec3 integrate(Vec3 velocity, Vec3 acceleration, double intervalTicks) {
        requireFinite(velocity, "velocity");
        requireFinite(acceleration, "acceleration");
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException("intervalTicks must be finite and positive: " + intervalTicks);
        }
        return velocity.add(acceleration.scale(intervalTicks));
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
