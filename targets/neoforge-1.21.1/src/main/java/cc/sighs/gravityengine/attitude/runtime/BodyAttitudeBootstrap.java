package cc.sighs.gravityengine.attitude.runtime;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.Objects;

/** Explicit displayed-orientation sample used to initialize attitude state. */
public final class BodyAttitudeBootstrap {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;

    private final Quaterniond worldFromBody;
    private final Vec3 angularVelocityWorld;

    public BodyAttitudeBootstrap(
            Quaterniond worldFromBody,
            Vec3 angularVelocityWorld
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularVelocityWorld, "angularVelocityWorld");
        double lengthSquared = worldFromBody.lengthSquared();
        if (!Double.isFinite(worldFromBody.x)
                || !Double.isFinite(worldFromBody.y)
                || !Double.isFinite(worldFromBody.z)
                || !Double.isFinite(worldFromBody.w)
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "worldFromBody must be finite and non-degenerate");
        }
        if (!Double.isFinite(angularVelocityWorld.x)
                || !Double.isFinite(angularVelocityWorld.y)
                || !Double.isFinite(angularVelocityWorld.z)
                || !Double.isFinite(angularVelocityWorld.lengthSqr())) {
            throw new IllegalArgumentException(
                    "angularVelocityWorld must be finite");
        }
        this.worldFromBody = new Quaterniond(worldFromBody).normalize();
        this.angularVelocityWorld = angularVelocityWorld;
    }

    /** Returns a defensive mutable copy. */
    public Quaterniond worldFromBody() {
        return new Quaterniond(this.worldFromBody);
    }

    public Vec3 angularVelocityWorld() {
        return this.angularVelocityWorld;
    }

    public static BodyAttitudeBootstrap stationary(Quaterniond worldFromBody) {
        return new BodyAttitudeBootstrap(worldFromBody, Vec3.ZERO);
    }
}
