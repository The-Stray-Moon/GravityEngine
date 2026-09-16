package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.AngularTrajectory;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyAttitudeStepResult;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable logical state produced by {@link cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Service}. It is
 * prepared from one captured {@link BodyAttitudeTickInput}, the previous
 * component snapshot and the single transaction precondition, but it is never
 * published by the service.
 *
 * <p>The optional look rebase is published with the actor/view state.</p>
 */
public record BodyAttitudeLogicalCandidate(
        BodyAttitudeState state,

        BodyRelativeViewState view,
        BodyAttitudeStepResult lastStepResult,
        BodyAttitudeDecision decision,
        BodyAttitudeContinuity continuity,
        BodyAttitudeOwnership ownership,
        long lastLocalSimulationStep,
        boolean bootstrapped,
        AngularTrajectory trajectory,
        Kind kind,
        BodyAttitudeTransactionPrecondition precondition,
        @Nullable BodyAttitudeLookRebase lookRebase
) {
    public BodyAttitudeLogicalCandidate {
        Objects.requireNonNull(state, "state");

        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(continuity, "continuity");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(precondition, "precondition");
        if (lastStepResult == null) {
            if (continuity == BodyAttitudeContinuity.CONTINUOUS) {
                throw new IllegalArgumentException(
                        "continuous logical candidate requires a step result");
            }
        }
        if (kind != Kind.SUSPENSION
                && kind != Kind.PENDING_BOOTSTRAP
                && trajectory == null) {
            throw new IllegalArgumentException(
                    "stepped actor candidates require an angular trajectory");
        }
    }

    public enum Kind {
        STEP,
        PERSISTENCE_RESTORE,
        LIFECYCLE_BOOTSTRAP,
        SUSPENSION,
        PENDING_BOOTSTRAP
    }

}
