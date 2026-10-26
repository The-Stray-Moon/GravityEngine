package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.field.GravityFieldEvaluationSource;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;

import java.util.Objects;
import java.util.Optional;

/**
 * Derives the physical-evaluation context
 * from committed engine state.
 */
public final class GravityEvaluationContexts {
    private GravityEvaluationContexts() {}

    public static GravityAuthorityState authorityOf(
            GravityEntityState state
    ) {
        Objects.requireNonNull(state, "state");
        return state.assignedAuthority() == GravityAuthorityMode.DIRECT
                ? GravityAuthorityState.direct(state.assignmentSyncRevision())
                : GravityAuthorityState.field(
                        /*
                         * The physical context binds the FIELD reference the
                         * engine currently treats as in force: confirmed
                         * presence, or provisional continuity retained while
                         * reconciliation is unresolved. UNKNOWN without
                         * retained continuity binds as no reference.
                         */
                        state.fieldReferenceInForce(),
                        state.assignmentSyncRevision()
                );
    }

    public static GravityEvaluationContext capture(
            GravityEntityState state,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(registry, "registry");

        long registryRevision = state.committedApplication()
                .plan()
                .accelerationMode() == GravityAccelerationMode.FIELD
                ? registry.publicationRevision()
                : GravityEvaluationSnapshot
                .NO_FIELD_REGISTRY_REVISION;

        return new GravityEvaluationContext(
                authorityOf(state),
                state.applicationEpoch(),
                state.committedApplication(),
                registryRevision
        );
    }

    /** Client consumes an already evaluated remote result; no local registry participates. */
    public static GravityEvaluationContext captureRemoteResult(GravityEntityState state) {
        return new GravityEvaluationContext(authorityOf(state), state.applicationEpoch(),
                state.committedApplication(), GravityEvaluationSnapshot.NO_FIELD_REGISTRY_REVISION);
    }

    /** True when a runtime evaluation still matches current committed state. */
    public static boolean isValidCurrent(
            GravityEvaluationSnapshot evaluation,
            GravityEntityState state,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(evaluation, "evaluation");
        return !evaluation.context().usesFieldRegistry() && evaluation.context().samePhysicalContext(
                capture(state, registry)
        );
    }

    /**
     * The current tick/current evaluation only when its complete physical
     * context is still valid.
     */
    public static Optional<GravityEvaluationSnapshot> validTickEvaluation(
            GravityEntityState state,
            GravityOperationState operation,
            GravityFieldEvaluationSource registry
    ) {
        return operation
                .tickGravityEvaluation()
                .filter(evaluation -> isValidCurrent(
                        evaluation,
                        state,
                        registry
                ));
    }
}
