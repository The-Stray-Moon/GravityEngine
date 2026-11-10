package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, transient attitude state carried between fixed simulation steps.
 *
 * <p>There is exactly one current body attitude authority: {@code
 * currentWorldFromBody}, the body-local to world rotation {@code q}. {@code
 * previousWorldFromBody} is interpolation history only and never a second
 * physics state.</p>
 *
 * <p>Two ownership kinds exist and are distinguished structurally rather than
 * by convention:</p>
 *
 * <ul>
 *   <li>{@code KINEMATIC}: no angular dynamics are present. A mode-owned
 *       kinematic owner (for example the FREE_ATTITUDE body/view joint
 *       follow) may rewrite {@code q}. No momentum is fabricated for it.</li>
 *   <li>{@code DYNAMIC}: {@link AngularMomentumState} carries the durable
 *       world-space angular momentum {@code L_world} and body-space effective
 *       inertia. {@code q} and {@code L_world} advance atomically through
 *       {@link AngularDynamicsSolver} exactly once per fixed logical step, and
 *       the derived {@code omega_world} is never stored beside them.</li>
 * </ul>
 *
 * <p>{@link #angularVelocityWorld()} is a pure derived accessor. There is no
 * constructor, setter or factory that accepts an authoritative angular
 * velocity; a mode handoff either preserves {@code L_world}, consumes it
 * exactly once, or explicitly initializes zero momentum.</p>
 */
public final class BodyAttitudeState {
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-24D;

    private final Quatd previousWorldFromBody;
    private final Quatd currentWorldFromBody;
    private final AngularMomentumState dynamics;
    private final long tick;
    private final long revision;
    private final boolean initialized;

    private BodyAttitudeState(
            Quatd previousWorldFromBody,
            Quatd currentWorldFromBody,
            AngularMomentumState dynamics,
            long tick,
            long revision,
            boolean initialized
    ) {
        this.previousWorldFromBody = normalizedCopy(previousWorldFromBody,
                "previousWorldFromBody");
        this.currentWorldFromBody = normalizedCopy(currentWorldFromBody,
                "currentWorldFromBody");
        this.dynamics = dynamics;
        if (tick < 0L) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.tick = tick;
        this.revision = revision;
        this.initialized = initialized;
    }

    /**
     * Fresh kinematic ownership: {@code q} is owned by a mode-owned kinematic
     * writer until an explicit dynamic handoff occurs.
     */
    public static BodyAttitudeState kinematic(
            Quatd worldFromBody,
            long tick,
            long revision
    ) {
        return new BodyAttitudeState(
                worldFromBody, worldFromBody, null, tick, revision, true);
    }

    /**
     * Fresh dynamic ownership with an explicit momentum state. Callers that
     * have no authoritative momentum must pass
     * {@link AngularMomentumState#rest(EffectiveAngularInertia)} rather than an
     * angular velocity.
     */
    public static BodyAttitudeState dynamic(
            Quatd worldFromBody,
            AngularMomentumState dynamics,
            long tick,
            long revision
    ) {
        Objects.requireNonNull(dynamics, "dynamics");
        return new BodyAttitudeState(
                worldFromBody, worldFromBody, dynamics, tick, revision, true);
    }

    /**
     * Package-private construction seam used only by
     * {@link AttitudeDynamicHandoff}. Replication and persistence must decide
     * ownership before crossing that handoff; external callers cannot install
     * q and angular state independently.
     */
    static BodyAttitudeState installed(
            Quatd worldFromBody,
            AngularMomentumState dynamics,
            long tick,
            long revision,
            boolean initialized
    ) {
        return new BodyAttitudeState(
                worldFromBody,
                worldFromBody,
                dynamics,
                tick,
                revision,
                initialized
        );
    }

    public static BodyAttitudeState uninitialized() {
        Quatd identity = Quatd.IDENTITY;
        return new BodyAttitudeState(
                identity, identity, null, 0L, 0L, false);
    }

    /** Tick history copy only: preserve current physics bit-for-bit, including on a rejected step. */
    public BodyAttitudeState withCurrentAsPrevious() {
        return previousWorldFromBody.equals(currentWorldFromBody)
                ? this
                : new BodyAttitudeState(
                        currentWorldFromBody,
                        currentWorldFromBody,
                        dynamics,
                        tick,
                        revision,
                        initialized);
    }

    /**
     * DYNAMIC -> KINEMATIC handoff.
     *
     * <p>GE-owned angular momentum is consumed and cleared exactly once here;
     * no exit path may each clear a private copy, and the claim "momentum was
     * preserved because the old angular velocity vector survived" is not
     * expressible in this model.</p>
     */
    public BodyAttitudeState asKinematic() {
        if (dynamics == null) {
            return this;
        }
        return new BodyAttitudeState(
                previousWorldFromBody,
                currentWorldFromBody,
                null,
                tick,
                revision,
                initialized);
    }

    /**
     * KINEMATIC/inactive -> DYNAMIC handoff.
     *
     * <p>Angular momentum is explicitly initialized to zero with the supplied
     * profile inertia. An authoritative persisted or replicated handoff must
     * instead install its own momentum through
     * {@link #withAuthoritativeDynamics(AngularMomentumState)}.</p>
     */
    public BodyAttitudeState asDynamic(EffectiveAngularInertia inertia) {
        Objects.requireNonNull(inertia, "inertia");
        if (dynamics != null) {
            return dynamics.inertia().equals(inertia)
                    ? this
                    : withAuthoritativeDynamics(dynamics.withInertia(inertia));
        }
        return withAuthoritativeDynamics(AngularMomentumState.rest(inertia));
    }

    /**
     * DYNAMIC -> DYNAMIC handoff that installs a supplied momentum state,
     * preserving or replacing the authoritative {@code L_world} in exactly one
     * step.
     */
    public BodyAttitudeState withAuthoritativeDynamics(
            AngularMomentumState next
    ) {
        Objects.requireNonNull(next, "next");
        return new BodyAttitudeState(
                previousWorldFromBody,
                currentWorldFromBody,
                next,
                tick,
                revision,
                initialized);
    }

    /**
     * Kinematic owner rewrite of {@code q}.
     *
     * @throws IllegalStateException when dynamic ownership is active, because a
     *         post-solver view constraint must never silently overwrite the
     *         physical attitude of a dynamic body
     */
    public BodyAttitudeState withKinematicOrientation(Quatd next) {
        if (dynamics != null) {
            throw new IllegalStateException(
                    "a dynamic body attitude cannot be rewritten by a "
                            + "kinematic owner; perform an explicit handoff "
                            + "first");
        }
        return new BodyAttitudeState(
                previousWorldFromBody,
                next,
                null,
                tick,
                revision,
                initialized);
    }

    /**
     * Atomic dynamic solver commit: the current {@code q} and {@code L_world}
     * advance together for one logical step.
     */
    public BodyAttitudeState withDynamicStep(
            Quatd nextWorldFromBody,
            AngularMomentumState nextDynamics
    ) {
        Objects.requireNonNull(nextDynamics, "nextDynamics");
        if (dynamics == null) {
            throw new IllegalStateException(
                    "dynamic step requires dynamic ownership");
        }
        return new BodyAttitudeState(
                currentWorldFromBody,
                nextWorldFromBody,
                nextDynamics,
                Math.incrementExact(tick),
                Math.incrementExact(revision),
                true);
    }

    /** True when this actor's attitude is advanced by the angular solver. */
    public boolean hasAngularDynamics() {
        return dynamics != null;
    }

    /** Durable world-space angular momentum, empty for kinematic ownership. */
    public Optional<AngularMomentumState> angularMomentum() {
        return Optional.ofNullable(dynamics);
    }

    /** Body-space effective inertia, empty for kinematic ownership. */
    public Optional<EffectiveAngularInertia> effectiveInertia() {
        return dynamics == null
                ? Optional.empty()
                : Optional.of(dynamics.inertia());
    }

    /**
     * Pure derived {@code omega_world} in rad/s. Zero for kinematic
     * ownership: a kinematic body has no physical rotation rate to report, and
     * this accessor never becomes writable state.
     */
    public Vec3d angularVelocityWorld() {
        return dynamics == null
                ? Vec3d.ZERO
                : dynamics.angularVelocityWorld(currentWorldFromBody);
    }

    /** Returns a defensive mutable copy. */
    public Quatd previousWorldFromBody() {
        return new Quatd(this.previousWorldFromBody);
    }

    /** Returns a defensive mutable copy. */
    public Quatd currentWorldFromBody() {
        return new Quatd(this.currentWorldFromBody);
    }

    public long tick() { return this.tick; }
    public long revision() { return this.revision; }
    public boolean initialized() { return this.initialized; }

    private static Quatd normalizedCopy(Quatd value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y())
                || !Double.isFinite(value.z()) || !Double.isFinite(value.w())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(name + " must be non-degenerate");
        }
        return value.normalized();
    }
}
