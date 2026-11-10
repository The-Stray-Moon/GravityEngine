package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Explicit displayed-orientation sample used to initialize attitude state.
 *
 * <p>A bootstrap supplies {@code q} only. It is a one-time displayed-orientation
 * handoff, never a per-step recovery target, and it carries no angular
 * momentum: entering dynamic ownership either preserves an authoritative
 * replicated/persisted {@code L_world} or explicitly initializes zero
 * momentum at the mode handoff. This type exists so the removed behaviour of
 * seeding a private Elytra angular velocity from the displayed pose cannot
 * reappear.</p>
 */
public final class BodyAttitudeBootstrap {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;

    private final Quatd worldFromBody;

    public BodyAttitudeBootstrap(Quatd worldFromBody) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        double lengthSquared = worldFromBody.lengthSquared();
        if (!worldFromBody.isFinite()
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "worldFromBody must be finite and non-degenerate");
        }
        this.worldFromBody = new Quatd(worldFromBody).normalized();
    }

    /** Immutable GravityEngine-owned orientation value. */
    public Quatd worldFromBody() {
        return this.worldFromBody;
    }

    public static BodyAttitudeBootstrap stationary(Quatd worldFromBody) {
        return new BodyAttitudeBootstrap(worldFromBody);
    }
}
