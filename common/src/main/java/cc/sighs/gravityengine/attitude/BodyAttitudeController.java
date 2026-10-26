package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestration facade for one logical actor-attitude step.
 *
 * <p>The dependency direction is fixed:</p>
 *
 * <pre>
 * flight/environment evidence
 *   -> flight intent
 *   -> attitude policy
 *   -> world-space torque contributions
 *   -> common angular dynamics solver
 *   -> body attitude
 *
 * motion/translation model ----------------------------------> world-space movement
 * </pre>
 *
 * <p>This class owns <em>no</em> integrator. Torque policies contribute
 * through {@link AngularTorqueAccumulator} and {@link AngularDynamicsSolver}
 * is the only writer of a dynamic {@code q + L_world}. There is no gravity
 * restoring term, no target roll angle or rate, no ordinary hard angular-rate
 * clamp and no Elytra-specific momentum state.</p>
 *
 * <p>{@code REFERENCE_ALIGNED} and {@code FREE_ATTITUDE} run no angular
 * dynamics here at all: they are explicit KINEMATIC ownership paths. For
 * FREE_ATTITUDE the semantic view and the body-local view joint follow mutate
 * the body orientation through {@link BodyViewConstraintSolver} after this
 * step, and the last GE-owned angular momentum is consumed exactly once at the
 * mode handoff.</p>
 *
 * <p>Actor quaternion changes never rotate the kinematic character collider or
 * translate its position, and this stage never touches the Elytra
 * translation/aerodynamics path.</p>
 */
public final class BodyAttitudeController {

    /**
     * The attitude policies whose world-space torques compose the Elytra
     * dynamic step. They are separate contributions from separate owners:
     * held player roll, heading control (including heading-only derivative
     * damping) and the generic configurable game angular damping.
     */
    private static final List<AttitudeTorquePolicy> ELYTRA_TORQUE_POLICIES =
            List.of(
                    ElytraPlayerRollTorquePolicy.INSTANCE,
                    ElytraHeadingTorquePolicy.INSTANCE,
                    GlobalAngularDampingTorquePolicy.INSTANCE
            );

    private BodyAttitudeController() {}

    public static BodyAttitudeStepResult step(
            BodyAttitudeState state,
            BodyAttitudeStepContext context,
            BodyAttitudeConfigSnapshot config
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");

        if (!state.initialized()) {
            throw new IllegalStateException(
                    "BodyAttitudeController.step requires an initialized state; "
                            + "the integration layer must capture the currently "
                            + "displayed body orientation first"
            );
        }

        return switch (context.controlProfile().constraint()) {
            case REFERENCE_ALIGNED, FREE_ATTITUDE -> kinematicStep(state);
            case ELYTRA_ALIGNED -> dynamicElytraStep(state, context, config);
        };
    }

    /**
     * Kinematic ownership step: the mode-owned writer owns {@code q} and no
     * angular momentum exists. A previously dynamic actor is handed over here
     * and its GE-owned momentum is consumed exactly once.
     */
    private static BodyAttitudeStepResult kinematicStep(BodyAttitudeState state) {
        Quatd body = state.currentWorldFromBody();
        BodyAttitudeState next = BodyAttitudeState.kinematic(
                body,
                Math.incrementExact(state.tick()),
                Math.incrementExact(state.revision())
        );
        return new BodyAttitudeStepResult(
                next,
                Vec3d.ZERO,
                0.0D,
                0,
                false,
                AngularTrajectory.install(body)
        );
    }

    /**
     * Dynamic ownership step for the Elytra flight-attitude policy.
     *
     * <p>The mode handoff is explicit: a kinematic or inactive actor starts
     * with zero angular momentum, an already dynamic actor keeps its durable
     * {@code L_world} and adopts the current profile inertia. Vanilla/displayed
     * orientation remains a one-time bootstrap only and is never a per-step
     * recovery target.</p>
     */
    private static BodyAttitudeStepResult dynamicElytraStep(
            BodyAttitudeState state,
            BodyAttitudeStepContext context,
            BodyAttitudeConfigSnapshot config
    ) {
        EffectiveAngularInertia inertia = config.elytraEffectiveAngularInertia();
        BodyAttitudeState dynamic = state.hasAngularDynamics()
                ? state.withAuthoritativeDynamics(
                        state.angularMomentum().orElseThrow().withInertia(inertia))
                : state.asDynamic(inertia);

        AngularMomentumState dynamics = dynamic.angularMomentum().orElseThrow();
        Vec3d initialMomentum = dynamics.angularMomentumWorld();

        double inputSampleDuration = context.dtSeconds();
        double simulatedDuration = simulatedDuration(inputSampleDuration, config);
        boolean timeClamped = inputSampleDuration > simulatedDuration;
        int substepCount = substepCount(simulatedDuration, config);
        double substepSeconds = simulatedDuration / substepCount;

        Quatd current = state.currentWorldFromBody();
        List<AngularTrajectory.AngularSegment> segments =
                new ArrayList<>(substepCount);
        Vec3d netTorque = Vec3d.ZERO;
        double rotationErrorRadians = 0.0D;

        for (int i = 0; i < substepCount; i++) {
            Quatd substepStart = current;

            Vec3d angularVelocity = dynamics.angularVelocityWorld(current);
            FlightIntent intent = FlightIntentResolver.resolve(
                    current,
                    context.lookForwardWorld(),
                    context.velocityWorld(),
                    config
            );

            AngularTorqueAccumulator accumulator = new AngularTorqueAccumulator();
            AttitudeTorqueContext torqueContext = new AttitudeTorqueContext(
                    current,
                    angularVelocity,
                    dynamics.angularMomentumWorld(),
                    inertia,
                    intent,
                    context.input(),
                    context.controlProfile(),
                    config,
                    substepSeconds
            );
            for (AttitudeTorquePolicy policy : ELYTRA_TORQUE_POLICIES) {
                policy.accumulate(accumulator, torqueContext);
            }
            netTorque = accumulator.netTorqueWorld();
            rotationErrorRadians = headingErrorRadians(current, intent, config);

            AngularDynamicsStep solved = AngularDynamicsSolver.integrate(
                    current,
                    dynamics,
                    netTorque,
                    substepSeconds,
                    config.quaternionEpsilon()
            );

            current = solved.worldFromBody();
            dynamics = new AngularMomentumState(
                    solved.angularMomentumWorld(),
                    inertia
            );

            segments.add(new AngularTrajectory.AngularSegment(
                    substepStart,
                    current,
                    AngularTrajectory.Kind.DYNAMIC,
                    substepSeconds,
                    dynamics.angularMomentumWorld()
            ));
        }

        BodyAttitudeState next = dynamic.withDynamicStep(current, dynamics);

        return new BodyAttitudeStepResult(
                next,
                netTorque,
                rotationErrorRadians,
                substepCount,
                timeClamped,
                new AngularTrajectory(initialMomentum, segments)
        );
    }

    /** Forward-direction error magnitude in radians; roll and world up never participate. */
    private static double headingErrorRadians(
            Quatd worldFromBody,
            FlightIntent intent,
            BodyAttitudeConfigSnapshot config
    ) {
        Vec3d forward =
                AttitudeSpaceTransform.elytraForwardWorld(worldFromBody);
        Vec3d dorsal =
                AttitudeSpaceTransform.elytraDorsalWorld(worldFromBody);
        return BodyAttitudeMath.directionRotationError(
                forward,
                intent.desiredForwardWorld(),
                dorsal,
                config.vectorEpsilon()
        ).length();
    }

    private static double simulatedDuration(
            double inputSampleDuration,
            BodyAttitudeConfigSnapshot config
    ) {
        double maximumTime =
                config.maxSubstepSeconds() * config.maxSubsteps();
        double simulatedTime =
                Math.min(inputSampleDuration, maximumTime);
        if (!Double.isFinite(simulatedTime) || simulatedTime <= 0.0D) {
            throw new IllegalArgumentException(
                    "simulated time must be finite and positive");
        }
        return simulatedTime;
    }

    private static int substepCount(
            double simulatedDuration,
            BodyAttitudeConfigSnapshot config
    ) {
        int substepCount = Math.min(config.maxSubsteps(),
                Math.max(1, (int) Math.ceil(
                        simulatedDuration / config.maxSubstepSeconds())));
        if (substepCount > config.maxSubsteps()) {
            throw new IllegalStateException(
                    "substep count exceeds configured maximum");
        }
        return substepCount;
    }
}
