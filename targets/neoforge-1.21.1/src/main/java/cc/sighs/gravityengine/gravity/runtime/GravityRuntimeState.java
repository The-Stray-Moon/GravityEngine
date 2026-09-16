package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.collision.PassiveGravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.CollisionScene;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.ObbQueryContext;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.presentation.CompletedGravityFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-entity transient state for gravity-sensitive operations.
 *
 * <p>This owner retains installed geometry, completed presentation, contact
 * continuity and tick-scoped movement provenance. MoveScope owns the
 * operation result and pending publication. A completed move publishes its locomotion
 * displacement and same-tick ground continuity for later consumers; the
 * character collision result itself remains operation-scoped; the passive result
 * has a separate same-tick Vanilla callback lifetime. There is deliberately no
 * persistent support state machine, no jump eligibility, no coyote, no detach
 * intent, and no scene-revision ground ownership: ground is a current-move
 * result. Only a minimal completed endpoint-ground publication crosses ticks for attitude ownership. Movement provenance
 * (pending self-walk, pending external push and the
 * reconciled move request) is tick/operation transient state and is cleared
 * at every position/body discontinuity boundary through
 * {@link #invalidateMovementContinuity()}.</p>
 */
public final class GravityRuntimeState {
    /**
     * Operation-owned collision resources for exactly one outer gravity
     * operation. This is intentionally a small nested value, not a separate
     * lifecycle layer: the owning {@link MoveScope} is the lifetime authority.
     */
    public record CollisionOperationContext(
            GravityFrame frame,
            KinematicStepContext time,
            CollisionScene scene,
            ObbQueryContext geometryContext,
            CollisionWorkTracker workTracker
    ) {
        public CollisionOperationContext {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(geometryContext, "geometryContext");
            Objects.requireNonNull(workTracker, "workTracker");
            // The geometry scratch must share the operation's single tracker so
            // every query phase consumes one shared budget.
            geometryContext.setWorkTracker(workTracker);
        }
    }

    private MovementEvidence movementEvidence;
    private KinematicMoveRequest currentMoveRequest;
    private Vec3 pendingSelfWalk = Vec3.ZERO;
    private Vec3 pendingExternalPush = Vec3.ZERO;
    private long selfEvidenceTick = Long.MIN_VALUE;
    private long externalEvidenceTick = Long.MIN_VALUE;
    private long movementContinuity;

    public void recordSelfWalk(long tick, Vec3 movement) {
        OwnedMotion.requireFinite(movement);
        if (selfEvidenceTick != tick) pendingSelfWalk = Vec3.ZERO;
        selfEvidenceTick = tick;
        pendingSelfWalk = pendingSelfWalk.add(movement);
    }

    /** Explicit impulse evidence, consumed once by the next move, never a second velocity write. */
    public void recordExternalPush(long tick, Vec3 impulse) {
        OwnedMotion.requireFinite(impulse);
        if (externalEvidenceTick != tick) pendingExternalPush = Vec3.ZERO;
        externalEvidenceTick = tick;
        pendingExternalPush = pendingExternalPush.add(impulse);
    }

    public OwnedMotion consumeMovementEvidence(long tick) {
        Vec3 self = selfEvidenceTick == tick ? pendingSelfWalk : Vec3.ZERO;
        // A push delivered after this actor's travel is consumed by its next
        // logical tick. Already consumed pushes can never be replayed by packets.
        Vec3 external = externalEvidenceTick == tick || externalEvidenceTick == tick - 1L
                ? pendingExternalPush : Vec3.ZERO;
        pendingSelfWalk = Vec3.ZERO;
        pendingExternalPush = Vec3.ZERO;
        return new OwnedMotion(self, external, Vec3.ZERO);
    }

    public MovementEvidenceScope openMovementEvidence(MovementEvidence evidence) {
        if (!isInMove()) throw new IllegalStateException("movement evidence requires an operation");
        MovementEvidenceScope scope = new MovementEvidenceScope(this.movementEvidence,
                this.currentMoveRequest, movementContinuity);
        this.movementEvidence = Objects.requireNonNull(evidence);
        this.currentMoveRequest = null;
        return scope;
    }

    public KinematicMoveRequest reconcileActualMovement(Vec3 actual) {
        if (movementEvidence == null) throw new IllegalStateException("collide has no wrapper evidence");
        this.currentMoveRequest = movementEvidence.reconcile(actual);
        return this.currentMoveRequest;
    }

    public KinematicMoveRequest currentMoveRequest() { return currentMoveRequest; }

    private void clearMovementProvenance() {
        movementEvidence = null;
        currentMoveRequest = null;
        pendingSelfWalk = Vec3.ZERO;
        movementContinuity++;
    }

    /**
     * Position/body discontinuity lifecycle boundary (teleport, respawn,
     * dimension transfer, body-geometry invalidation, discontinuous
     * {@code setPos}/{@code setPosRaw} replacement, or an application
     * transition that destroys movement continuity).
     *
     * <p>This is deliberately narrower than
     * {@link #clearInfluenceTransientState()}: it clears every transient
     * movement-provenance value that cannot be inherited across a
     * discontinuous position/body boundary, but it does not discard the
     * environmental frame/presentation continuity. A teleport inside the
     * same influence does not resample or discard gravity evidence here;
     * the next outer operation's frame policy still applies.</p>
     *
     * <p>Normal {@code Entity.move} commits must not call this method: the
     * owning operation consumes/closes its provenance. The production
     * lifecycle entry points guard against invoking it inside an active move
     * or geometry mutation scope.</p>
     */
    public void invalidateMovementContinuity() {
        clearMovementProvenance();
        pendingExternalPush = Vec3.ZERO;
        selfEvidenceTick = Long.MIN_VALUE;
        externalEvidenceTick = Long.MIN_VALUE;
        if (isInMove()) activeTransaction.invalidateMovement();
        this.lastPassiveMoveResult = null;
        this.lastPassiveMoveTick = Long.MIN_VALUE;
        this.lastCommittedLocomotionDisplacement = null;
        this.lastCommittedMovementGameTick = Long.MIN_VALUE;
        this.movementGroundContinuity = null;
        this.completedEndpointGround = null;
        this.restingContactSnapshot = null;
        this.pendingSoftPositionSupportRevalidation = null;
    }

    /**
     * Monotonic movement-continuity revision.  Incremented once per
     * movement-provenance clear (a discontinuity boundary, or the owning
     * operation's own close).  One logical position replacement must add no
     * more than one clear of its own, and a same-position Vanilla position
     * write must not advance it at all.  Exposed read-only for focused
     * position-write ownership tests.
     */
    public long movementContinuity() {
        return this.movementContinuity;
    }

    public final class MovementEvidenceScope implements AutoCloseable {
        private final MovementEvidence parent;
        private final KinematicMoveRequest parentRequest;
        private final long continuity;
        private boolean closed;
        private MovementEvidenceScope(MovementEvidence parent, KinematicMoveRequest request,
                                      long continuity) {
            this.parent = parent;
            this.parentRequest = request;
            this.continuity = continuity;
        }
        @Override public void close() {
            if (closed) throw new IllegalStateException("movement evidence already closed");
            closed = true;
            if (continuity != movementContinuity) return;
            movementEvidence = parent;
            if (parent != null) {
                currentMoveRequest = parentRequest;
            }
            // The enclosing travel may inspect the request until outer close.
        }
    }
    // ------------------------------------------------------------------
    // Persistent frame / presentation state
    // ------------------------------------------------------------------
    /** Installed character symmetry axis; no environmental tangent gauge. */
    private Vec3 installedCollisionUp;
    private long bodyShapeRevision;

    /** Shape/ownership epoch, independent of translation and presentation. */
    public long bodyShapeRevision() { return bodyShapeRevision; }
    public void markBodyDimensionsChanged() {
        bodyShapeRevision++;
        completedEndpointGround = null;
    }

    private GravityFrame lastCompletedFrame;
    private CompletedGravityFrame completedPresentationFrame;
    private long presentationRevision;
    private long lastPresentationDiscontinuityRevision;
    private long lastPresentationTick;
    private long sceneRevisionCounter;
    private int geometryMutationDepth;

    /*
     * One-tick, post-move locomotion evidence. This is not a support state or
     * a reusable collision result: recovery and preparation have already been
     * excluded by GravityMoveResult. GravityBodyTurnMath subsequently takes
     * only the gravity-tangent component, so support-follow rise cannot become
     * heading evidence either.
     */
    private Vec3 lastCommittedLocomotionDisplacement;
    private long lastCommittedMovementGameTick = Long.MIN_VALUE;
    private MovementGroundContinuity movementGroundContinuity;
    /** Latest completed determinate endpoint, retained across ticks until superseded/invalidated.
     * No contact, gameplay-ground, attitude or scene state is retained here. */
    public record CompletedEndpointGround(long revision, boolean terminalSupported) {}
    private CompletedEndpointGround completedEndpointGround;
    private long endpointGroundRevision;

    public Optional<CompletedEndpointGround> completedEndpointGround() {
        return Optional.ofNullable(completedEndpointGround);
    }

    /*
     * One-tick resting-support continuity authority captured at the close of
     * the immediately preceding authoritative move.  It owns no obstacle and
     * never sets ground; the next operation validates it against the current
     * body before a PRESERVE_SUPPORT geometry reanchor may use it.
     */
    private RestingContactSnapshot restingContactSnapshot;

    /**
     * Previous authoritative support retained only across a numerically small
     * external position correction.
     *
     * This is a one-shot reacquisition hint, not a grounded publication.
     */
    private RestingContactSnapshot pendingSoftPositionSupportRevalidation;

    /*
     * Completed passive result from the latest outer move only.
     * This is a short-lived post-move publication for vanilla drag/bounce hooks,
     * never persistent support/controller state.
     */
    private PassiveGravityMoveResult lastPassiveMoveResult;
    private long lastPassiveMoveTick = Long.MIN_VALUE;
    /** First authoritative velocity write after an in-move position discontinuity. */
    private Vec3 discontinuityVelocity;


    // ------------------------------------------------------------------
    // Active transaction pointer
    // ------------------------------------------------------------------
    private MoveScope activeTransaction;

    /**
     * Opens one gravity operation.
     *
     * The outermost scope owns frame/sample lifetime. Nested scopes borrow the
     * same frozen physical context.
     *
     * Movement-result publication is intentionally shared by the enclosing
     * operation chain: a physical MOVE publishes its GravityMoveResult to an
     * enclosing TRAVEL scope, which consumes that result after Entity.move
     * returns. A reentrant callback MOVE has its own publication; its parent's
     * remaining seams must retain their original result.
     * Each new physical invocation clears its publication, and an independent
     * position discontinuity invalidates the entire chain.
     */
    public MoveScope openMove(GravityState state, Vec3 samplePoint) {
        return openMove(GravityFrame.fromState(state, samplePoint));
    }

    /** Opens one gravity operation using an explicit frame. */
    public MoveScope openMove(GravityFrame frame) {
        long logicalTick = this.completedPresentationFrame == null
                ? this.lastPresentationTick
                : this.completedPresentationFrame.tick() + 1L;
        return openMove(frame, logicalTick);
    }

    /**
     * Opens an operation with the game tick that will label its completed
     * presentation publication. Nested scopes always retain the outer tick.
     */
    public MoveScope openMove(GravityFrame frame, long tick) {
        return openMove(frame, tick, null);
    }

    public MoveScope openMove(
            GravityFrame frame,
            long tick,
            GravityOperationType operationType
    ) {
        return openMove(frame, cc.sighs.gravityengine.gravity.model.GravitySample.fromFrame(frame), tick, operationType);
    }

    /** Opens an operation while preserving its exact live acceleration sample. */
    public MoveScope openMove(
            GravityFrame frame,
            GravitySample sample,
            long tick,
            GravityOperationType operationType
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(sample, "sample");
        boolean outermost = this.activeTransaction == null;

        /*
         * A completed passive result belongs only to the immediately preceding outer
         * move. Starting another outer operation invalidates it before the new move
         * can choose vanilla or passive collision.
         *
         * This also prevents stale reuse when two Entity.move calls happen in the
         * same game tick.
         */
        if (outermost) {
            this.lastPassiveMoveResult = null;
            this.lastPassiveMoveTick = Long.MIN_VALUE;
            this.discontinuityVelocity = null;
        }

        long operationTick = outermost
                ? Math.max(this.lastPresentationTick, Math.max(0L, tick))
                : this.lastPresentationTick;
        MoveScope parent = this.activeTransaction;
        GravityFrame selectedFrame = outermost ? frame : parent.frame();
        GravityOperationType selectedType = outermost
                ? operationType
                : (operationType != null
                ? operationType
                : parent.operationType);
        MoveScope child = new MoveScope(
                this,
                parent,
                selectedFrame,
                outermost ? sample : parent.sample,
                operationTick,
                selectedType,
                outermost
        );
        this.activeTransaction = child;
        return child;
    }

    public GravityFrame frameOrSample(GravityState state, Vec3 samplePoint) {
        if (this.isInMove()) return this.activeFrame();
        if (this.installedCollisionUp != null) return frameForInstalledAxis(lastCompletedFrame == null
                ? GravityFrame.fromState(state, samplePoint) : lastCompletedFrame);
        return this.lastCompletedFrame != null
                ? this.lastCompletedFrame
                : GravityFrame.fromState(state, samplePoint);
    }

    /**
     * Installed physical symmetry axis. Environmental tangent axes and strength
     * belong to operation/completed evidence, never this collision descriptor.
     */
    public Vec3 installedCollisionUp() { return installedCollisionUp; }

    /** Environmental evidence adapted to the installed axis. This is derived,
     * never a cached collision tangent frame. */
    public GravityFrame geometryReferenceFrame() {
        if (installedCollisionUp == null) return null;
        return frameForInstalledAxis(lastCompletedFrame == null ? GravityFrame.DEFAULT : lastCompletedFrame);
    }

    public GravityFrame frameForInstalledAxis(GravityFrame evidence) {
        if (installedCollisionUp == null) throw new IllegalStateException("no installed collision axis");
        if (evidence.up().distanceToSqr(installedCollisionUp) <= 1e-28) return evidence;
        var aligned = GravityFrame.fromAcceleration(evidence.samplePoint(), installedCollisionUp.reverse(),
                installedCollisionUp.reverse(), evidence);
        var basis = new cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d(
                aligned.orientation().axisX(new org.joml.Vector3d()),
                new org.joml.Vector3d(installedCollisionUp.x, installedCollisionUp.y, installedCollisionUp.z),
                aligned.orientation().axisZ(new org.joml.Vector3d()));
        return new GravityFrame(evidence.samplePoint(), basis, evidence.strength());
    }

    public void setInstalledCollisionAxisFromFrame(GravityFrame frame) {
        Vec3 up = frame.up();
        if (installedCollisionUp == null || installedCollisionUp.distanceToSqr(up) > 1e-28) {
            bodyShapeRevision++;
            completedEndpointGround = null;
        }
        installedCollisionUp = up;
    }

    public void clearInstalledCollisionAxis() {
        if (installedCollisionUp != null) bodyShapeRevision++;
        installedCollisionUp = null;
        completedEndpointGround = null;
    }

    /**
     * The last completed outer frame provides continuity between independent
     * operations without extending the lifetime of the active operation scope.
     */
    public GravityFrame lastCompletedFrame() {
        return this.lastCompletedFrame;
    }

    /** Latest fully completed frame publication, or {@code null} after invalidation. */
    public CompletedGravityFrame completedPresentationFrame() {
        return this.completedPresentationFrame;
    }

    /** Monotonic presentation order; invalidation advances it. */
    public long presentationRevision() {
        return this.presentationRevision;
    }

    public void clearFrameContinuity() {
        this.lastCompletedFrame = null;
        this.completedPresentationFrame = null;
        this.restingContactSnapshot = null;
        this.completedEndpointGround = null;
        this.lastPresentationDiscontinuityRevision = advancePresentationRevision();
    }

    /** Durable watermark: consumers may skip any number of completed publications. */
    public long lastPresentationDiscontinuityRevision() {
        return this.lastPresentationDiscontinuityRevision;
    }

    public GravityFrame activeFrame() {
        if (this.activeTransaction == null) {
            throw new IllegalStateException("No active GravityFrame");
        }
        return this.activeTransaction.frame;
    }

    /** Exact operation-owned physical sample; nested operations borrow it. */
    public GravitySample activeSample() {
        if (this.activeTransaction == null) {
            throw new IllegalStateException("No active GravitySample");
        }
        return this.activeTransaction.sample;
    }

    public boolean isInMove() {
        return this.activeTransaction != null;
    }

    /** Latest independent destination wins; the owning scope performs the
     * geometry handoff only after all borrowed scopes have closed. */
    public void supersedeMovement(Vec3 destination) {
        var current = requireMove();
        current.outer.discontinuityDestination = Objects.requireNonNull(destination);
        this.discontinuityVelocity = null;
        for (; current != null; current = current.parent) current.invalidateMovement();
        this.lastPassiveMoveResult = null;
        this.lastPassiveMoveTick = Long.MIN_VALUE;
    }

    /**
     * Captures the first velocity write made after an in-move independent
     * position replacement. Later stale movement writes cannot replace it;
     * the outer operation restores this value after committing geometry.
     */
    public void recordPostDiscontinuityVelocity(Vec3 velocity) {
        Objects.requireNonNull(velocity, "velocity");
        if (!isInMove()
                || discontinuityDestination() == null
                || discontinuityVelocity != null) {
            return;
        }
        discontinuityVelocity = new Vec3(velocity.x, velocity.y, velocity.z);
    }

    /** Consumes the captured callback velocity at the outer handoff. */
    public Vec3 consumePostDiscontinuityVelocity() {
        Vec3 captured = discontinuityVelocity;
        discontinuityVelocity = null;
        return captured;
    }

    public Vec3 discontinuityDestination() {
        return isInMove() ? activeTransaction.outer.discontinuityDestination : null;
    }

    public GravityOperationType activeOperationType() {
        return this.isInMove()
                ? this.activeTransaction.operationType
                : null;
    }

    /** Monotonic per-entity collision-scene revision. */
    public long nextSceneRevision() {
        if (this.sceneRevisionCounter == Long.MAX_VALUE) {
            throw new IllegalStateException("scene revision overflow");
        }
        return ++this.sceneRevisionCounter;
    }

    private void closeMove(MoveScope scope) {
        if (scope.owner != this) {
            throw new IllegalStateException(
                    "Move scope belongs to another runtime state"
            );
        }

        if (scope.closed) {
            throw new IllegalStateException(
                    "Move scope already closed"
            );
        }

        if (this.activeTransaction != scope) {
            throw new IllegalStateException(
                    "Gravity move scopes must close in LIFO order"
            );
        }

        scope.closed = true;
        MoveScope closing = scope;

        if (scope.outermost && closing.discontinuityDestination != null) {
            this.activeTransaction = null;
            // The integration owner's immediate discontinuity handoff clears
            // continuity once, after live movement ownership has ended.
            return;
        }

        if (scope.outermost) {
            if (closing.operationType == GravityOperationType.MOVE
                    || closing.operationType == GravityOperationType.TRAVEL) {

                /*
                 * Ordinary Entity.move remains the sole locomotion/path authority.
                 */
                GravityMoveResult locomotionResult =
                        closing.publication.moveResult;

                boolean locomotionCommitted =
                        locomotionResult != null
                                && !locomotionResult.indeterminate();

                this.lastCommittedLocomotionDisplacement =
                        locomotionCommitted
                                ? MinecraftGeometryAdapter.toMinecraft(
                                locomotionResult
                                        .locomotionMovement()
                        )
                                : Vec3.ZERO;

                this.lastCommittedMovementGameTick =
                        scope.presentationTick;

                /*
                 * An accepted Sable inherited support transport may have moved the
                 * final body after the ordinary Entity.move endpoint.
                 *
                 * Its result owns endpoint support only; it does not become the
                 * locomotion result.
                 */
                GravityMoveResult endpointResult =
                        closing.externalSupportTransportEndpointResult != null
                                ? closing.externalSupportTransportEndpointResult
                                : locomotionResult;

                boolean endpointDeterminate =
                        endpointResult != null
                                && !endpointResult.indeterminate();

                /*
                 * Sable FirstCollisionInfo is movement-path contact evidence.
                 *
                 * It may preserve same-commit gameplay grounding after a valid supporting
                 * collision, exactly as GravityEngine's own supportingContactDuringMove can.
                 *
                 * It is NOT endpoint support. Terminal support remains owned exclusively by
                 * the actual final endpoint result (ordinary move endpoint or accepted
                 * external support-transport endpoint).
                 */
                ExternalSubLevelMoveEvidence external =
                        closing.publication
                                .externalSubLevelMoveEvidence;

                boolean externalSupportingContactDuringMove =
                        SubLevelMovementPolicy
                                .externalSupportingContactDuringMove(
                                        external
                                );

                if (locomotionCommitted && endpointDeterminate) {
                    boolean terminalSupported =
                            endpointResult
                                    .terminalGrounded();

                    this.completedEndpointGround =
                            new CompletedEndpointGround(
                                    endpointGroundRevision =
                                            Math.incrementExact(
                                                    endpointGroundRevision
                                            ),
                                    terminalSupported
                            );

                    this.movementGroundContinuity =
                            new MovementGroundContinuity(
                                    scope.presentationTick,

                                    /*
                                     * Path-contact evidence from either geometry owner may
                                     * establish supporting contact during this movement.
                                     */
                                    locomotionResult
                                            .supportingContactDuringMove()
                                            || externalSupportingContactDuringMove,

                                    /*
                                     * Vanilla-style same-commit ground continuity may survive
                                     * a real supporting collision even when the character has
                                     * just walked off the endpoint.
                                     */
                                    locomotionResult
                                            .gameplayGrounded()
                                            || externalSupportingContactDuringMove,

                                    /*
                                     * Endpoint support is never inferred from Sable's first
                                     * collision. Only the actual final endpoint result owns it.
                                     */
                                    terminalSupported
                            );
                } else {
                    this.movementGroundContinuity = null;
                    this.completedEndpointGround = null;
                }

                updateRestingContactSnapshot(
                        scope.presentationTick,
                        endpointDeterminate
                                ? endpointResult
                                : null
                );
            }
            this.lastCompletedFrame = closing.frame;
            this.lastPresentationTick = scope.presentationTick;

            this.completedPresentationFrame =
                    new CompletedGravityFrame(
                            closing.frame,
                            scope.presentationTick,
                            advancePresentationRevision()
                    );

            this.activeTransaction = null;

            clearMovementProvenance();
        } else {
            this.activeTransaction = closing.parent;
            if (closing.publication == closing) {
                // Passive material hooks have a same-tick handoff in addition
                // to current-result access. Callback re-entry must restore that
                // handoff to the resumed invocation too.
                this.lastPassiveMoveResult = discontinuityDestination() == null
                        ? activeTransaction.publication.passiveResult : null;
                this.lastPassiveMoveTick = this.lastPassiveMoveResult == null
                        ? Long.MIN_VALUE : activeTransaction.collisionOperation.time().gameTick();
            }
        }

    }

    public GeometryScope openGeometryMutation() {
        this.geometryMutationDepth++;
        return new GeometryScope(this, this.geometryMutationDepth);
    }

    public boolean isApplyingGeometry() {
        return this.geometryMutationDepth > 0;
    }

    private void closeGeometry(GeometryScope scope) {
        if (scope.owner != this || scope.closed) {
            throw new IllegalStateException("Invalid geometry scope");
        }
        if (this.geometryMutationDepth != scope.depthAtOpen) {
            throw new IllegalStateException(
                    "Geometry scopes must close in LIFO order"
            );
        }
        scope.closed = true;
        this.geometryMutationDepth--;
    }

    // ------------------------------------------------------------------
    // Current move result (one Entity.move lifecycle only)
    // ------------------------------------------------------------------

    /**
     * The {@link GravityMoveResult} owned by the executing physical invocation,
     * or its completed child when consumed by enclosing travel/validation.
     * Callback re-entry cannot replace the caller's result. Null outside a scope.
     */
    public GravityMoveResult currentMoveResult() {
        return isInMove() ? activeTransaction.publication.moveResult : null;
    }

    /** Called after frame/geometry preparation, before the native move body executes. */
    public void beginMovement(cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.CollisionRoute route) {
        requireMove().beginMovement(route);
    }

    public cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.CollisionRoute movementCollisionRoute() {
        return isInMove() ? activeTransaction.publication.collisionRoute : null;
    }

    /** Vanilla API projection formed once by the solver publication; never a new authority. */
    public VanillaCollisionState movementCollisionState() {
        return isInMove() ? activeTransaction.publication.collisionState : null;
    }

    /**
     * Stable support selected by the previous completed movement and carried
     * through this logical step's geometry preparation.
     *
     * <p>This is the exact pre-move support evidence needed to suppress a new
     * gravity increment before it enters persistent velocity. It is not an
     * alternate terminal-support authority after the current movement
     * commits.</p>
     */
    public void setStepStartSupport(RestingContactSnapshot support) {
        requireMove().setStepStartSupport(support);
    }

    public Optional<RestingContactSnapshot> stepStartSupport() {
        return isInMove()
                ? activeTransaction.stepStartSupport()
                : Optional.empty();
    }

    /**
     * Completed evidence from the optional external SubLevel solve for this
     * physical move. It is operation-local and never retained across moves.
     */
    public void setExternalSubLevelMoveEvidence(
            ExternalSubLevelMoveEvidence evidence
    ) {
        requireMove().setExternalSubLevelMoveEvidence(evidence);
    }

    public ExternalSubLevelMoveEvidence externalSubLevelMoveEvidence() {
        return isInMove()
                ? activeTransaction.publication.externalSubLevelMoveEvidence
                : null;
    }

    public void stageExternalSupportTransport(
            Vec3 startPosition,
            Vec3 resolvedTransport
    ) {
        stageExternalSupportTransport(
                startPosition,
                resolvedTransport,
                null
        );
    }

    /**
     * Stages one externally owned support/reference-space translation.
     *
     * <p>{@code endpointResult} is auxiliary endpoint evidence only. It never
     * replaces the ordinary Entity.move result or its locomotion displacement.</p>
     */
    public void stageExternalSupportTransport(
            Vec3 startPosition,
            Vec3 resolvedTransport,
            GravityMoveResult endpointResult
    ) {
        Objects.requireNonNull(startPosition, "startPosition");
        Objects.requireNonNull(resolvedTransport, "resolvedTransport");

        OwnedMotion.requireFinite(startPosition);
        OwnedMotion.requireFinite(resolvedTransport);

        requireMove().stageExternalSupportTransport(
                startPosition,
                startPosition.add(resolvedTransport),
                endpointResult
        );
    }

    public boolean consumeExternalSupportTransportPositionWrite(
            Vec3 beforePosition,
            Vec3 afterPosition
    ) {
        Objects.requireNonNull(beforePosition, "beforePosition");
        Objects.requireNonNull(afterPosition, "afterPosition");

        return isInMove()
                && activeTransaction
                .consumeExternalSupportTransportPositionWrite(
                        beforePosition,
                        afterPosition
                );
    }

    public void setCurrentMoveResult(GravityMoveResult result) { requireMove().acceptMove(result); }

    public void clearCurrentMoveResult() {
        if (isInMove()) activeTransaction.clearResult();
    }

    private MoveScope requireMove() {
        if (!isInMove()) throw new IllegalStateException("move publication requires an active operation");
        return activeTransaction;
    }

    /**
     * Locomotion-only displacement committed by a custom MOVE/TRAVEL operation
     * in the requested game tick. The optional is empty rather than stale
     * when no same-tick operation published evidence.
     */
    /** Same-tick handoff only; stale movement cannot grant another tick of ground. */
    public Optional<MovementGroundContinuity> lastCommittedMovementGroundContinuity(long gameTick) {
        return movementGroundContinuity != null && movementGroundContinuity.gameTick() == gameTick
                ? Optional.of(movementGroundContinuity) : Optional.empty();
    }

    /** Restores the immutable handoff as part of an owning physical transaction rollback. */
    public void restoreMovementGroundContinuity(MovementGroundContinuity snapshot) {
        this.movementGroundContinuity = snapshot;
    }

    public Optional<Vec3> lastCommittedLocomotionDisplacement(long gameTick) {
        return this.lastCommittedMovementGameTick == gameTick
                ? Optional.of(this.lastCommittedLocomotionDisplacement)
                : Optional.empty();
    }

    /**
     * Resting-support authority captured from the previous authoritative
     * move, or {@code null} when the previous move was airborne,
     * indeterminate, manifold/untrusted-witness, moving-support, or
     * unsupported.
     */
    public RestingContactSnapshot restingContactSnapshot() {
        return this.restingContactSnapshot;
    }

    /** Explicit lifecycle override used by focused continuity tests. */
    public void setRestingContactSnapshot(
            RestingContactSnapshot snapshot
    ) {
        this.restingContactSnapshot = snapshot;
    }

    public void clearRestingContactSnapshot() {
        this.restingContactSnapshot = null;
        this.pendingSoftPositionSupportRevalidation = null;
    }

    public void stageSoftPositionSupportRevalidation(
            RestingContactSnapshot snapshot
    ) {
        this.pendingSoftPositionSupportRevalidation =
                Objects.requireNonNull(
                        snapshot,
                        "snapshot"
                );
    }

    public Optional<RestingContactSnapshot>
    consumeSoftPositionSupportRevalidation() {

        RestingContactSnapshot snapshot =
                this.pendingSoftPositionSupportRevalidation;

        this.pendingSoftPositionSupportRevalidation = null;

        return Optional.ofNullable(snapshot);
    }

    /** Captures a selected face address for the next query. This does not retain
     * an obstacle or scene; discontinuity/detach and absent endpoint support
     * clear it. Static plane preservation has its own stricter eligibility. */
    public static RestingContactSnapshot captureRestingContactSnapshot(
            Optional<GravitySupportContact> support,
            Optional<BlockPos> supportBlock,
            boolean grounded,
            long gameTick
    ) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(supportBlock, "supportBlock");
        if (!grounded || support.isEmpty()) {
            return null;
        }
        GravitySupportContact contact = support.get();
        if (contact.faceIdentity() == null && (!contact.planePreservationEligible()
                || contact.surfaceVelocity().length() > RestingContactSnapshot.STATIC_SUPPORT_MAX_SPEED
                || supportBlock.isEmpty())) return null;
        return new RestingContactSnapshot(
                MinecraftGeometryAdapter.toMinecraft(contact.normal()),
                MinecraftGeometryAdapter.toMinecraft(
                        contact.surfaceVelocity()),
                MinecraftGeometryAdapter.toMinecraft(contact.contactPoint()),
                contact.geometryKind(),
                supportBlock,
                gameTick,
                contact.faceIdentity()
        );
    }

    private void updateRestingContactSnapshot(
            long gameTick,
            GravityMoveResult result
    ) {
        if (result == null || result.indeterminate() || !result.terminalGrounded()) {
            if (this.restingContactSnapshot != null
                    && GravityDebugLog.ENABLED) {
                GravityDebugLog.log(
                        "geometry-resting",
                        "snapshot=CLEARED reason=%s gameTick=%d",
                        result == null
                                ? "NO_MOVE_RESULT"
                                : result.indeterminate()
                                        ? "INDETERMINATE"
                                        : "NOT_GROUNDED",
                        gameTick
                );
            }
            this.restingContactSnapshot = null;
            return;
        }
        RestingContactSnapshot captured =
                captureRestingContactSnapshot(
                        result.supportContact(),
                        result.supportBlock(),
                        result.terminalGrounded(),
                        gameTick
                );
        if (captured == null) {
            if (this.restingContactSnapshot != null
                    && GravityDebugLog.ENABLED) {
                GravityDebugLog.log(
                        "geometry-resting",
                        "snapshot=CLEARED "
                                + "reason=UNSUPPORTED_OR_UNTRUSTED gameTick=%d",
                        gameTick
                );
            }
            this.restingContactSnapshot = null;
            return;
        }
        if (GravityDebugLog.ENABLED) {
            GravityDebugLog.log(
                    "geometry-resting",
                    "snapshot=CAPTURED normal=%s witness=%s surfaceVelocity=%s "
                            + "supportBlock=%s gameTick=%d",
                    GravityDebugLog.vec(captured.normal()),
                    GravityDebugLog.vec(captured.contactPoint()),
                    GravityDebugLog.vec(captured.surfaceVelocity()),
                    captured.supportBlock()
                            .map(BlockPos::toString)
                            .orElse("none"),
                    gameTick
            );
        }
        this.restingContactSnapshot = captured;
    }

    public PassiveGravityMoveResult currentPassiveMoveResult() {
        return isInMove() ? activeTransaction.publication.passiveResult : null;
    }

    public void setCurrentPassiveMoveResult(PassiveGravityMoveResult result) {
        Objects.requireNonNull(result, "result");
        if (discontinuityDestination() != null) return;

        CollisionOperationContext operation = this.collisionOperation();
        if (operation == null) {
            throw new IllegalStateException(
                    "Cannot publish passive move result without active collision operation"
            );
        }

        requireMove().acceptPassive(result);
        this.lastPassiveMoveResult = result;
        this.lastPassiveMoveTick = operation.time().gameTick();
    }

    /**
     * Returns the completed passive move only when it belongs to the requested
     * game tick.
     */
    public PassiveGravityMoveResult lastPassiveMoveResult(long gameTick) {
        return this.lastPassiveMoveTick == gameTick
                ? this.lastPassiveMoveResult
                : null;
    }

    /** Same-input collide guard for a repeated identical vanilla re-entry. */
    public Optional<Vec3> preResolvedTranslation(Vec3 actualInput) {
        if (!this.isInMove()) return Optional.empty();
        return this.activeTransaction.preResolvedTranslation(actualInput);
    }

    public void setPreResolved(Vec3 input, Vec3 translation) {
        if (!this.isInMove()) {
            throw new IllegalStateException(
                    "Cannot record a pre-resolved translation outside a move"
            );
        }
        this.activeTransaction.setPreResolved(input, translation);
    }

    /** The single operation-owned collision context, or {@code null} outside an outer move. */
    public CollisionOperationContext collisionOperation() {
        return this.isInMove()
                ? this.activeTransaction.collisionOperation
                : null;
    }

    public void setCollisionOperation(CollisionOperationContext operation) {
        if (!this.isInMove()) {
            throw new IllegalStateException(
                    "Cannot install a collision operation outside an active move"
            );
        }
        this.activeTransaction.setCollisionOperation(operation);
    }

    /**
     * Clears values that are meaningful only while custom gravity influence is
     * active. Operation-scope bookkeeping remains intact so transitions can be
     * safely deferred to the next boundary.
     */
    public void clearInfluenceTransientState() {
        invalidateMovementContinuity();
        if (this.isInMove()) {
            this.activeTransaction.clearTransient();
        }
        this.clearFrameContinuity();
    }

    private long advancePresentationRevision() {
        if (this.presentationRevision == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Gravity presentation revision overflow"
            );
        }
        return ++this.presentationRevision;
    }

    // ------------------------------------------------------------------
    // Scopes
    // ------------------------------------------------------------------

    public static final class MoveScope implements AutoCloseable {
        private final GravityRuntimeState owner;
        private final MoveScope parent;
        private final MoveScope outer;
        private MoveScope publication;
        private boolean executingMovement;
        private cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.CollisionRoute collisionRoute;
        private VanillaCollisionState collisionState;
        private GravityMoveResult moveResult;
        private PassiveGravityMoveResult passiveResult;
        private ExternalSubLevelMoveEvidence externalSubLevelMoveEvidence;
        private Optional<RestingContactSnapshot> stepStartSupport;
        private Vec3 pendingExternalSupportTransportStart;
        private Vec3 pendingExternalSupportTransportDestination;

        /*
         * Endpoint authority produced by the latest accepted external support
         * transport. This is deliberately NOT publication.moveResult: ordinary
         * Entity.move still owns locomotion/path facts.
         */
        private GravityMoveResult externalSupportTransportEndpointResult;

        private Vec3 discontinuityDestination;
        private final GravityFrame frame;
        private final GravitySample sample;
        private final long presentationTick;
        private final GravityOperationType operationType;
        private CollisionOperationContext collisionOperation;
        private Vec3 preResolvedInput;
        private Vec3 preResolvedTranslation;
        private final boolean outermost;
        private boolean closed;

        private MoveScope(
                GravityRuntimeState owner,
                MoveScope parent,
                GravityFrame frame,
                GravitySample sample,
                long presentationTick,
                GravityOperationType operationType,
                boolean outermost
        ) {
            this.owner = owner;
            this.parent = parent;
            this.outer = parent == null ? this : parent.outer;
            this.publication = parent == null ? this : parent.publication;
            this.frame = frame;
            this.sample = sample;
            this.presentationTick = presentationTick;
            this.operationType = operationType;
            this.collisionOperation = parent == null
                    ? null
                    : parent.collisionOperation;
            this.stepStartSupport = parent == null
                    ? Optional.empty()
                    : parent.stepStartSupport;
            this.outermost = outermost;
        }

        public GravityFrame frame() {
            return this.frame;
        }

        public boolean outermost() {
            return this.outermost;
        }

        private void beginMovement(
                cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.CollisionRoute route
        ) {
            if (executingMovement) {
                throw new IllegalStateException(
                        "physical move already started in this scope"
                );
            }
            for (MoveScope ancestor = parent;
                    ancestor != null;
                    ancestor = ancestor.parent) {
                if (ancestor.executingMovement) {
                    publication = this;
                    break;
                }
            }
            executingMovement = true;
            clearResult();

            publication.externalSubLevelMoveEvidence = null;

            outer.pendingExternalSupportTransportStart = null;
            outer.pendingExternalSupportTransportDestination = null;
            outer.externalSupportTransportEndpointResult = null;

            publication.collisionRoute =
                    Objects.requireNonNull(route, "route");
        }

        private void acceptMove(GravityMoveResult result) {
            if (outer.discontinuityDestination != null) {
                return;
            }

            publication.moveResult =
                    Objects.requireNonNull(result, "result");
            publication.passiveResult = null;

            boolean accepted =
                    !result.indeterminate();

            boolean onGround =
                    accepted
                            && result.gameplayGrounded();

            /*
             * Vanilla mainSupportingBlockPos describes the stable block beneath an
             * on-ground entity. A physical steep contact must not enter this field.
             *
             * supportingContactDuringMove may keep onGround true for this commit after
             * the terminal body has already left the face; in that case the block is
             * intentionally empty and Vanilla's onGroundNoBlocks semantics apply.
             */
            Optional<BlockPos> mainSupportingBlock =
                    accepted && result.terminalGrounded()
                            ? result.supportBlock()
                            : Optional.empty();

            VanillaCollisionState parentWorldState =
                    new VanillaCollisionState(
                            onGround,
                            accepted && result.blockedTangent(),
                            accepted
                                    && (result.blockedDown()
                                    || result.blockedUp()),
                            accepted
                                    && result.supportingContactDuringMove(),
                            false,
                            mainSupportingBlock
                    );
            publication.collisionState =
                    SubLevelMovementPolicy.mergeParentWorldState(
                            parentWorldState,
                            publication.externalSubLevelMoveEvidence
                    );
        }

        private void acceptPassive(PassiveGravityMoveResult result) {
            if (outer.discontinuityDestination != null) return;
            clearResult();
            publication.passiveResult = Objects.requireNonNull(result, "result");
            boolean accepted = !result.indeterminate();
            VanillaCollisionState parentWorldState =
                    new VanillaCollisionState(
                    accepted && result.supported(),
                    accepted && result.blockedTangent(),
                    accepted && (result.blockedDown() || result.blockedUp()),
                    accepted && result.blockedDown(),
                    false,
                    accepted && result.supported()
                            ? result.supportBlock()
                            : Optional.empty()
            );
            publication.collisionState =
                    SubLevelMovementPolicy.mergeParentWorldState(
                            parentWorldState,
                            publication.externalSubLevelMoveEvidence
                    );
        }

        private void clearResult() {
            publication.moveResult = null;
            publication.passiveResult = null;
            publication.collisionState = null;
        }

        private void invalidateMovement() {
            clearResult();
            owner.completedEndpointGround = null;

            outer.pendingExternalSupportTransportStart = null;
            outer.pendingExternalSupportTransportDestination = null;
            outer.externalSupportTransportEndpointResult = null;
        }

        private Optional<Vec3> preResolvedTranslation(Vec3 actualInput) {
            Objects.requireNonNull(actualInput, "actualInput");
            if (outer.discontinuityDestination != null) return Optional.empty();
            if (this.preResolvedInput == null
                    || this.preResolvedTranslation == null) {
                return Optional.empty();
            }
            double scale = Math.max(1.0D, actualInput.length());
            if (this.preResolvedInput.subtract(actualInput).lengthSqr()
                    > 1.0E-18D * scale * scale) {
                return Optional.empty();
            }
            return Optional.of(this.preResolvedTranslation);
        }

        private void setPreResolved(Vec3 input, Vec3 translation) {
            if (outer.discontinuityDestination != null) return;
            this.preResolvedInput = Objects.requireNonNull(input, "input");
            this.preResolvedTranslation = Objects.requireNonNull(
                    translation,
                    "translation"
            );
        }

        private void setCollisionOperation(CollisionOperationContext operation) {
            this.collisionOperation = Objects.requireNonNull(
                    operation,
                    "operation"
            );
        }

        private void setStepStartSupport(RestingContactSnapshot support) {
            this.stepStartSupport = Optional.ofNullable(support);
        }

        public Optional<RestingContactSnapshot> stepStartSupport() {
            return stepStartSupport;
        }

        private void setExternalSubLevelMoveEvidence(
                ExternalSubLevelMoveEvidence evidence
        ) {
            if (outer.discontinuityDestination != null) {
                return;
            }
            publication.externalSubLevelMoveEvidence =
                    Objects.requireNonNull(evidence, "evidence");
        }

        private void stageExternalSupportTransport(
                Vec3 start,
                Vec3 destination,
                GravityMoveResult endpointResult
        ) {
            MoveScope target = outer;

            if (target.pendingExternalSupportTransportStart != null
                    || target.pendingExternalSupportTransportDestination != null) {
                throw new IllegalStateException(
                        "external support transport already pending"
                );
            }

            target.pendingExternalSupportTransportStart =
                    new Vec3(
                            start.x,
                            start.y,
                            start.z
                    );

            target.pendingExternalSupportTransportDestination =
                    new Vec3(
                            destination.x,
                            destination.y,
                            destination.z
                    );

            /*
             * Replacing a previously consumed transport endpoint is valid: a later
             * transport in the same logical operation owns the final endpoint.
             */
            target.externalSupportTransportEndpointResult =
                    endpointResult;
        }

        private boolean consumeExternalSupportTransportPositionWrite(
                Vec3 before,
                Vec3 after
        ) {
            MoveScope target = outer;

            if (target.pendingExternalSupportTransportStart == null
                    || target.pendingExternalSupportTransportDestination == null) {
                return false;
            }

            boolean matches =
                    target.pendingExternalSupportTransportStart.equals(before)
                            && target.pendingExternalSupportTransportDestination
                            .equals(after);

            if (!matches) {
                return false;
            }

            target.pendingExternalSupportTransportStart = null;
            target.pendingExternalSupportTransportDestination = null;
            return true;
        }

        private void clearTransient() {
            this.preResolvedInput = null;
            this.preResolvedTranslation = null;
        }

        @Override
        public void close() {
            this.owner.closeMove(this);
        }
    }

    public static final class GeometryScope implements AutoCloseable {
        private final GravityRuntimeState owner;
        private final int depthAtOpen;
        private boolean closed;

        private GeometryScope(GravityRuntimeState owner, int depthAtOpen) {
            this.owner = owner;
            this.depthAtOpen = depthAtOpen;
        }

        @Override
        public void close() {
            this.owner.closeGeometry(this);
        }
    }

}
