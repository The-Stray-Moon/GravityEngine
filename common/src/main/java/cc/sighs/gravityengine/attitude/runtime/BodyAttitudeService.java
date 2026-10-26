package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.gravity.GravityFrame;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure, side-effect-free body-attitude logical preparation.
 *
 * <p>This service computes the next logical attitude state from one immutable
 * captured tick. It never reads a platform entity, level, config provider or
 * live system, and it never publishes the candidate it returns: the target
 * transaction coordinator owns the component publication seam.</p>
 *
 * <p>Targets remain responsible for capturing {@link BodyAttitudeTickInput},
 * acquiring configuration, resolving player/entity access, persisting state
 * and replicating it over the network.</p>
 */
public final class BodyAttitudeService {
    public static final double GAME_TICK_SECONDS = 1.0D / 20.0D;

    private BodyAttitudeService() {}

    /**
     * Computes the next logical component state from one immutable captured
     * tick. This method never reads a platform entity or provider and never
     * publishes the returned candidate. The candidate carries the single
     * transaction precondition it was built from.
     */
    public static BodyAttitudePreparation prepare(
            BodyAttitudeComponent component,
            BodyAttitudeTickInput tickInput,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(tickInput, "tickInput");
        Objects.requireNonNull(precondition, "precondition");

        synchronized (component) {
            BodyAttitudeComponent.Snapshot before = precondition.component();
            if (component.snapshot() != before) {
                return new BodyAttitudePreparation(
                        UpdateOutcome.DUPLICATE_TICK, null);
            }
            long logicalStep = tickInput.logicalStep();
            if (before.lastLocalSimulationStep() == logicalStep) {
                return new BodyAttitudePreparation(
                        UpdateOutcome.DUPLICATE_TICK, null);
            }

            BodyAttitudeDecision decision = tickInput.decision();
            BodyAttitudeOwnership ownership = tickInput.ownership();

            if (ownership == BodyAttitudeOwnership.INACTIVE) {
                BodyAttitudeDecision suspension = decision.active()
                        ? BodyAttitudeDecision.suspended(
                                BodyAttitudeSuspensionReason.INACTIVE)
                        : decision;
                return new BodyAttitudePreparation(
                        UpdateOutcome.SUSPENDED,
                        suspensionCandidate(
                                before, logicalStep, suspension,
                                BodyAttitudeOwnership.INACTIVE,
                                tickInput, precondition));
            }

            return prepareActiveStep(
                    tickInput, before, precondition);
        }
    }

    private static BodyAttitudePreparation prepareActiveStep(
            BodyAttitudeTickInput tickInput,
            BodyAttitudeComponent.Snapshot before,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        long logicalStep = tickInput.logicalStep();
        BodyAttitudeDecision decision = tickInput.decision();
        BodyAttitudeOwnership ownership = tickInput.ownership();
        BodyAttitudeState state = before.state();
        boolean needsBootstrap = !state.initialized()
                || before.continuity() != BodyAttitudeContinuity.CONTINUOUS;
        boolean bootstrapped = false;
        BodyRelativeViewState view;
        Vec3d desiredWorldForward;

        if (needsBootstrap) {
            Vec3d lookForward = tickInput.bootstrapLookForward();
            Optional<BodyAttitudeBootstrap> bootstrap =
                    tickInput.bootstrap();
            if (bootstrap.isEmpty()) {
                return new BodyAttitudePreparation(
                        UpdateOutcome.PENDING_BOOTSTRAP,
                        pendingBootstrapCandidate(
                                before, logicalStep, decision,
                                tickInput, precondition));
            }
            BodyAttitudeBootstrap value = bootstrap.get();
            /*
             * Bootstrap supplies the displayed orientation only. Ownership
             * remains kinematic until the resolved constraint either keeps it
             * kinematic (FREE_ATTITUDE) or performs the explicit dynamic
             * handoff with zero momentum (ELYTRA_ALIGNED). No angular velocity
             * is carried across this boundary.
             */
            state = BodyAttitudeState.kinematic(
                    value.worldFromBody(),
                    state.tick(),
                    state.revision());
            view = BodyRelativeViewState.activate(
                    value.worldFromBody(),
                    lookForward,
                    tickInput.lookScalars().yawDegrees(),
                    tickInput.lookScalars().pitchDegrees());
            desiredWorldForward =
                    AttitudeSpaceTransform.worldLookFromBodyAngles(
                            state.currentWorldFromBody(),
                            view.localLook());
            bootstrapped = true;
        } else {
            view = before.view();
        }
        BodyLookControlSample look = BodyLookResolver.resolve(view,
                bootstrapped
                        ? BodyAttitudeInput.rollOnly(
                                tickInput.input().rollAxis())
                        : tickInput.input(),
                state.currentWorldFromBody(), tickInput.config(),
                tickInput.attitudeContract().viewBodyJointFollow()
                        ? GAME_TICK_SECONDS : 0);
        desiredWorldForward = look.requestedWorldForward();

        BodyAttitudeStepContext context = new BodyAttitudeStepContext(
                tickInput.frame(),
                decision.profile(),
                tickInput.input(),
                desiredWorldForward,
                tickInput.velocity(),
                GAME_TICK_SECONDS);
        BodyAttitudeStepResult result =
                BodyAttitudeController.step(
                        state, context, tickInput.config());
        BodyViewConstraintSolver.Result constrained =
                BodyViewConstraintSolver.solve(
                        result,
                        look,
                        tickInput.maxHeadRotationRadians(),
                        tickInput.attitudeContract().viewBodyJointFollow());
        result = constrained.step();
        BodyRelativeViewState rebasedView = constrained.view();
        AngularTrajectory trajectory = result.trajectory();
        return new BodyAttitudePreparation(
                UpdateOutcome.STEPPED,
                stepCandidate(
                        before,
                        logicalStep,
                        decision,
                        result,
                        rebasedView,
                        ownership,
                        bootstrapped,
                        trajectory,
                        tickInput, precondition));
    }

    /**
     * Builds the suspension candidate for an inactive ownership outcome.
     *
     * <p>Package-visible because the target lifecycle/persistence seam reuses
     * exactly this candidate shape when a load boundary must release old
     * ownership without running another ordinary tick.</p>
     */
    static BodyAttitudeLogicalCandidate suspensionCandidate(
            BodyAttitudeComponent.Snapshot before,
            long logicalStep,
            BodyAttitudeDecision decision,
            BodyAttitudeOwnership ownership,
            BodyAttitudeTickInput tickInput,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        BodyAttitudeLookRebase rebase = null;
        if (before.ownership() != BodyAttitudeOwnership.INACTIVE
                && before.view().initialized()
                && before.state().initialized()) {
            var view = BodyLookResolver.resolve(
                    before.view(),
                    tickInput.input(),
                    before.state().currentWorldFromBody(),
                    tickInput.config(),
                    before.decision().controllerRoll()
                            ? GAME_TICK_SECONDS : 0).nextViewState();
            var release = AttitudeSpaceTransform.releaseLookRebase(
                    tickInput.referenceRelativeLook()
                            ? tickInput.frame()
                            : GravityFrame.DEFAULT,
                    before.state().currentWorldFromBody(),
                    view,
                    tickInput.lookScalars().yawDegrees(),
                    tickInput.lookScalars().pitchDegrees());
            rebase = new BodyAttitudeLookRebase(
                    release.sourceYaw(), release.sourcePitch());
        }
        return new BodyAttitudeLogicalCandidate(
                /*
                 * Leaving dynamic ownership consumes GE-owned angular
                 * momentum exactly once. Re-entry re-initializes explicit
                 * zero momentum unless an authoritative handoff supplies it.
                 */
                before.state().asKinematic(),
                before.view(),
                null,
                decision,
                BodyAttitudeContinuity.INVALID,
                ownership,
                logicalStep,
                false,
                null,
                BodyAttitudeLogicalCandidate.Kind.SUSPENSION,
                precondition,
                rebase);
    }

    private static BodyAttitudeLogicalCandidate pendingBootstrapCandidate(
            BodyAttitudeComponent.Snapshot before,
            long logicalStep,
            BodyAttitudeDecision decision,
            BodyAttitudeTickInput tickInput,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        return new BodyAttitudeLogicalCandidate(
                before.state(),
                before.view(),
                null,
                decision,
                BodyAttitudeContinuity.PENDING_BOOTSTRAP,
                decision.active()
                        ? BodyAttitudeOwnership.ACTIVE
                        : BodyAttitudeOwnership.INACTIVE,
                logicalStep,
                false,
                null,
                BodyAttitudeLogicalCandidate.Kind.PENDING_BOOTSTRAP,
                precondition,
                null);
    }

    private static BodyAttitudeLogicalCandidate stepCandidate(
            BodyAttitudeComponent.Snapshot before,
            long logicalStep,
            BodyAttitudeDecision decision,
            BodyAttitudeStepResult result,
            BodyRelativeViewState view,
            BodyAttitudeOwnership ownership,
            boolean bootstrapped,
            AngularTrajectory trajectory,
            BodyAttitudeTickInput tickInput,
            BodyAttitudeTransactionPrecondition precondition
    ) {
        return new BodyAttitudeLogicalCandidate(
                result.nextState(),
                view,
                result,
                decision,
                BodyAttitudeContinuity.CONTINUOUS,
                ownership,
                logicalStep,
                bootstrapped,
                trajectory,
                BodyAttitudeLogicalCandidate.Kind.STEP,
                precondition,
                null);
    }

    public enum UpdateOutcome {
        STEPPED,
        SUSPENDED,
        PENDING_BOOTSTRAP,
        DUPLICATE_TICK,
        REJECTED_COMMIT,
        CONFIG_UNAVAILABLE;

        public boolean requiresCommit() {
            return this == STEPPED
                    || this == SUSPENDED
                    || this == PENDING_BOOTSTRAP;
        }
    }
}
