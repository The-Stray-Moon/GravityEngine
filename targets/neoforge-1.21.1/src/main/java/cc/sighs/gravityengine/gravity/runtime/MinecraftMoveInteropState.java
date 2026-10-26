package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-side movement interop state that cannot live in the
 * loader-neutral {@link cc.sighs.gravityengine.gravity.runtime.GravityOperationState}.
 *
 * <p>This owner holds exactly the target-only half of one entity's movement
 * bookkeeping:</p>
 *
 * <ul>
 *   <li>the completed external SubLevel (Sable) evidence of every open
 *       movement publication, keyed to that exact publication;</li>
 *   <li>the projection of the common
 *       {@link MovementCommitFacts} outcome into the Minecraft
 *       {@link VanillaCollisionState} that the vanilla movement seams
 *       consume.</li>
 * </ul>
 *
 * <p>It owns no frame, sample, revision, support continuity or operation
 * lifetime: those belong to the common operation state. Conversely the common
 * runtime owns no {@code BlockPos}, no vanilla collision flags and no third
 * party compatibility evidence.</p>
 */
public final class MinecraftMoveInteropState {
    private static final VanillaCollisionState EMPTY =
            new VanillaCollisionState(
                    false,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty()
            );

    private final GravityOperationState operationState;

    /*
     * External evidence is publication-scoped, not entity-scoped: a nested
     * movement publication owns its own evidence and must never observe,
     * replace or clear the enclosing publication's evidence. Publication
     * scopes have identity equality, so an identity map is the exact
     * per-scope key; closed publications are pruned as they are observed.
     */
    private final Map<
            GravityOperationState.MoveScope,
            ExternalSubLevelMoveEvidence
            > subLevelEvidenceByPublication =
            new IdentityHashMap<>();

    public MinecraftMoveInteropState(
            GravityOperationState operationState
    ) {
        this.operationState = Objects.requireNonNull(
                operationState,
                "operationState"
        );
    }

    /**
     * Records one completed external SubLevel solve for the current movement
     * publication and publishes its single platform-neutral consequence - the
     * existence of path-contact support evidence - to the common operation
     * state.
     */
    public void setSubLevelMoveEvidence(
            ExternalSubLevelMoveEvidence evidence
    ) {
        Objects.requireNonNull(evidence, "evidence");
        GravityOperationState.MoveScope publication =
                requireActivePublication();
        pruneClosedPublications();
        this.subLevelEvidenceByPublication.put(
                publication,
                evidence
        );
        this.operationState.setExternalSupportingContactDuringMove(
                SubLevelMovementPolicy.externalSupportingContactDuringMove(
                        evidence
                )
        );
    }

    /**
     * The external SubLevel evidence of the current movement publication, or
     * {@code null} when this publication has not captured one.
     */
    public ExternalSubLevelMoveEvidence subLevelMoveEvidence() {
        GravityOperationState.MoveScope publication =
                this.operationState.activePublicationScope();
        if (publication == null) {
            return null;
        }
        pruneClosedPublications();
        return this.subLevelEvidenceByPublication.get(publication);
    }

    /**
     * Vanilla collision projection of the committed move.
     *
     * <p>The common facts carry grounding, blocking and the supporting cell.
     * This target boundary adds the two things only a Minecraft integration
     * can know: the {@code BlockPos} representation of the supporting cell and
     * the Sable world-axis compatibility flags.</p>
     */
    public VanillaCollisionState currentCollisionState() {
        MovementCommitFacts facts =
                this.operationState.currentMovementCommitFacts();
        if (facts == null) {
            return null;
        }
        return project(facts, subLevelMoveEvidence());
    }

    /** The current projection, or the all-false projection outside a solve. */
    public VanillaCollisionState currentCollisionStateOrEmpty() {
        VanillaCollisionState state = currentCollisionState();
        return state != null ? state : EMPTY;
    }

    /** Drops interoperability evidence at an entity discontinuity boundary. */
    public void clear() {
        this.subLevelEvidenceByPublication.clear();
    }

    /**
     * Deterministic projection of common movement facts plus target-only
     * SubLevel compatibility evidence into the vanilla collision state.
     */
    public static VanillaCollisionState project(
            MovementCommitFacts facts,
            ExternalSubLevelMoveEvidence external
    ) {
        Objects.requireNonNull(facts, "facts");

        VanillaCollisionState parentWorldState =
                new VanillaCollisionState(
                        facts.grounded(),
                        facts.blockedTangent(),
                        facts.blockedVertical(),
                        facts.supportingContactDuringMove(),
                        false,
                        facts.mainSupportingBlock()
                                .map(MinecraftMathAdapter::toBlockPos)
                );

        return SubLevelMovementPolicy.mergeParentWorldState(
                parentWorldState,
                external
        );
    }

    private GravityOperationState.MoveScope requireActivePublication() {
        GravityOperationState.MoveScope publication =
                this.operationState.activePublicationScope();
        if (publication == null) {
            throw new IllegalStateException(
                    "SubLevel move evidence requires an active movement "
                            + "publication"
            );
        }
        return publication;
    }

    /**
     * Drops evidence for publications that have already closed. Only the
     * currently open publication chain can still be queried, so this keeps the
     * identity map bounded by the nesting depth rather than by the number of
     * movements an entity has ever performed.
     */
    private void pruneClosedPublications() {
        this.subLevelEvidenceByPublication
                .keySet()
                .removeIf(GravityOperationState.MoveScope::closed);
    }
}
