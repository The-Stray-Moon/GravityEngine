package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * The single centralized policy for installing replicated or persisted
 * attitude state.
 *
 * <p>The receiving boundary decides ownership before calling this class. A
 * kinematic install carries pose only. A dynamic install carries the same
 * {@code q + L_world} pair that the angular solver owns and binds it to the
 * receiving profile's effective inertia. No install path reconstructs
 * momentum from quaternion deltas or manufactures a writable angular velocity.
 * </p>
 */
public final class AttitudeDynamicHandoff {
    private AttitudeDynamicHandoff() {}

    /** Installs an explicitly kinematic pose-only owner. */
    public static BodyAttitudeState installKinematic(
            Quatd worldFromBody,
            long tick,
            long revision,
            boolean initialized
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        return BodyAttitudeState.installed(
                worldFromBody,
                null,
                tick,
                revision,
                initialized
        );
    }

    /**
     * Installs an explicitly dynamic owner.
     *
     * @param angularMomentumWorld authoritative world-space angular momentum,
     *                             in inertia-unit * rad/s
     * @param inertia              effective inertia resolved by the receiving
     *                             authority/profile, never supplied by an
     *                             untrusted pose sender
     */
    public static BodyAttitudeState installDynamic(
            Quatd worldFromBody,
            Vec3d angularMomentumWorld,
            EffectiveAngularInertia inertia,
            long tick,
            long revision,
            boolean initialized
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
        Objects.requireNonNull(inertia, "inertia");
        return BodyAttitudeState.installed(
                worldFromBody,
                new AngularMomentumState(angularMomentumWorld, inertia),
                tick,
                revision,
                initialized
        );
    }
}