package cc.sighs.gravityengine.protocol;

/** Fixed body-attitude wire limits, independent of transport and local defaults. */
public final class BodyAttitudeProtocolLimits {
    public static final int HARD_MAX_DIMENSION_ID_CHARACTERS = 256;

    /**
     * Hard protocol sanity bound on {@code |L_world|} in
     * {@code inertia-unit * rad / s}.
     *
     * <p>This is a transport sanity bound, not a gameplay rate limit: ordinary
     * control must reach its terminal rate through finite torque, damping or an
     * explicit rate-limit controller rather than by clamping total angular
     * speed.</p>
     */
    public static final double HARD_MAX_ANGULAR_MOMENTUM_MAGNITUDE = 1.0E6D;
    
    private BodyAttitudeProtocolLimits() {}
}
