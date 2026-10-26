package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContext;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.presentation.CompletedGravityFrame;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-entity transient state for gravity-sensitive operations.
 *
 * <p>This owner retains installed geometry, completed presentation, contact
 * continuity and tick-scoped movement provenance. {@link MoveScope} owns the
 * operation result and pending publication. A completed move publishes its
 * locomotion displacement and same-tick ground continuity for later
 * consumers; the character collision result itself remains
 * operation-scoped; the passive result has a separate same-tick caller
 * callback lifetime. Persistent support identity is retained as runtime
 * evidence and revalidated against each operation's frozen scene; it is not a
 * second ground state machine and never replaces the current-move result.
 * There is no jump eligibility, coyote, detach intent or scene-revision ground
 * ownership. Only a minimal completed endpoint-ground publication crosses
 * ticks for attitude ownership. Movement provenance (pending self-walk,
 * pending external push and the reconciled move request) is tick/operation
 * transient state and is cleared at every position/body discontinuity boundary through
 * {@link #invalidateMovementContinuity()}.</p>
 *
 * <p>Everything here is loader-neutral. The commit projection that turns a
 * {@link MovementCommitFacts} record into a platform collision state, the
 * platform position bookkeeping and any target-only compatibility evidence
 * live in the target's own interop state.</p>
 */
public final class GravityOperationState {
    /** Locomotion lifecycle, independent of gravity assignment and installed body geometry. */
    public enum MovementMode { GROUND_AIR, SWIMMING, CLIMBING, ELYTRA, NATIVE_FALLBACK }
    private MovementMode movementMode = MovementMode.GROUND_AIR;

    public MovementMode movementMode() { return movementMode; }

    /** Consumes a mode handoff once. Live velocity is world-space: replace only its
     * previously credited platform component with a freshly validated release sample.
     * Missing release evidence preserves physical velocity, never invents a new impulse. */
    public Vec3d transitionMovementMode(MovementMode next, Optional<Vec3d> releaseVelocity) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(releaseVelocity, "releaseVelocity");
        if (next == movementMode) return Vec3d.ZERO;
        if (isInMove()) throw new IllegalStateException("movement mode handoff inside active operation");
        Vec3d increment = persistentSupportState != null && !persistentSupportState.staticSupport()
                ? releaseVelocity.map(v -> v.subtract(supportVelocityContribution)).orElse(Vec3d.ZERO)
                : Vec3d.ZERO;
        OwnedMotion.requireFinite(increment);
        invalidateMovementContinuity();
        movementMode = next;
        return increment;
    }
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
    private Vec3d pendingSelfWalk = Vec3d.ZERO;
    private Vec3d pendingExternalPush = Vec3d.ZERO;
    private Vec3d pendingSupportMotion = Vec3d.ZERO;
    private long selfEvidenceTick = Long.MIN_VALUE;
    private long externalEvidenceTick = Long.MIN_VALUE;
    private long supportEvidenceTick = Long.MIN_VALUE;
    private long movementContinuity;

    public void recordSelfWalk(long tick, Vec3d movement) {
        OwnedMotion.requireFinite(movement);
        if (selfEvidenceTick != tick) pendingSelfWalk = Vec3d.ZERO;
        selfEvidenceTick = tick;
        pendingSelfWalk = pendingSelfWalk.add(movement);
    }

    /** Explicit impulse evidence, consumed once by the next move, never a second velocity write. */
    public void recordExternalPush(long tick, Vec3d impulse) {
        OwnedMotion.requireFinite(impulse);
        if (externalEvidenceTick != tick) pendingExternalPush = Vec3d.ZERO;
        externalEvidenceTick = tick;
        pendingExternalPush = pendingExternalPush.add(impulse);
    }

    /**
     * Explicit support-material velocity inherited at release (jump or
     * deliberate departure). It is world displacement/velocity evidence, not
     * actor input or an external impulse.
     */
    public void recordSupportMotion(long tick, Vec3d motion) {
        OwnedMotion.requireFinite(motion);
        if (supportEvidenceTick != tick) pendingSupportMotion = Vec3d.ZERO;
        supportEvidenceTick = tick;
        pendingSupportMotion = pendingSupportMotion.add(motion);
    }

    public OwnedMotion consumeMovementEvidence(long tick) {
        Vec3d self = selfEvidenceTick == tick ? pendingSelfWalk : Vec3d.ZERO;
        // A push delivered after this actor's travel is consumed by its next
        // logical tick. Already consumed pushes can never be replayed by packets.
        Vec3d external = externalEvidenceTick == tick || externalEvidenceTick == tick - 1L
                ? pendingExternalPush : Vec3d.ZERO;
        Vec3d support = supportEvidenceTick == tick
                || supportEvidenceTick == tick - 1L
                ? pendingSupportMotion : Vec3d.ZERO;
        pendingSelfWalk = Vec3d.ZERO;
        pendingExternalPush = Vec3d.ZERO;
        pendingSupportMotion = Vec3d.ZERO;
        return new OwnedMotion(
                self,
                external,
                Vec3d.ZERO,
                support
        );
    }

    public MovementEvidenceScope openMovementEvidence(MovementEvidence evidence) {
        if (!isInMove()) throw new IllegalStateException("movement evidence requires an operation");
        MovementEvidenceScope scope = new MovementEvidenceScope(this.movementEvidence,
                this.currentMoveRequest, movementContinuity);
        this.movementEvidence = Objects.requireNonNull(evidence);
        this.currentMoveRequest = null;
        return scope;
    }

    public KinematicMoveRequest reconcileActualMovement(Vec3d actual) {
        if (movementEvidence == null) throw new IllegalStateException("collide has no wrapper evidence");
        this.currentMoveRequest = movementEvidence.reconcile(actual);
        return this.currentMoveRequest;
    }

    /**
     * Reconciles a request that already contains the operation's engine-owned
     * support transport. The transport is attributed to
     * {@link OwnedMotion#supportMotion()} exactly once.
     */
    public KinematicMoveRequest reconcileActualMovement(
            Vec3d actual,
            Vec3d supportTransport
    ) {
        if (movementEvidence == null) {
            throw new IllegalStateException("collide has no wrapper evidence");
        }
        this.currentMoveRequest =
                movementEvidence.reconcile(actual, supportTransport);
        return this.currentMoveRequest;
    }

    public KinematicMoveRequest currentMoveRequest() { return currentMoveRequest; }

    public KinematicMoveRequest.Channel currentMovementChannel() {
        if (movementEvidence == null) throw new IllegalStateException("no movement evidence");
        return movementEvidence.channel();
    }

    private void clearMovementProvenance() {
        movementEvidence = null;
        currentMoveRequest = null;
        pendingSelfWalk = Vec3d.ZERO;
        pendingSupportMotion = Vec3d.ZERO;
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
     * <p>Normal platform move commits must not call this method: the
     * owning operation consumes/closes its provenance. The production
     * lifecycle entry points guard against invoking it inside an active move
     * or geometry mutation scope.</p>
     */
    public void invalidateMovementContinuity() {
        clearMovementProvenance();
        pendingExternalPush = Vec3d.ZERO;
        pendingSupportMotion = Vec3d.ZERO;
        selfEvidenceTick = Long.MIN_VALUE;
        externalEvidenceTick = Long.MIN_VALUE;
        supportEvidenceTick = Long.MIN_VALUE;
        if (isInMove()) activeTransaction.invalidateMovement();
        this.lastPassiveMoveResult = null;
        this.lastPassiveMoveTick = Long.MIN_VALUE;
        this.lastCommittedLocomotionDisplacement = null;
        this.lastCommittedMovementGameTick = Long.MIN_VALUE;
        this.movementGroundContinuity = null;
        this.completedEndpointGround = null;
        this.restingContactSnapshot = null;
        this.persistentSupportState = null;
        this.supportVelocityContribution = Vec3d.ZERO;
        this.pendingSoftPositionSupportRevalidation = null;
    }

    /**
     * Monotonic movement-continuity revision.  Incremented once per
     * movement-provenance clear (a discontinuity boundary, or the owning
     * operation's own close).  One logical position replacement must add no
     * more than one clear of its own, and a same-position position write must
     * not advance it at all.  Exposed read-only for focused position-write
     * ownership tests.
     */
    public long movementContinuity() {
        return this.movementContinuity;
    }

    public final class MovementEvidenceScope implements AutoCloseable {
        private final MovementEvidence parent;
        private final KinematicMoveRequest parentRequest;
        private final long continuity;
        private boolean closed;

        private MovementEvidenceScope(
                MovementEvidence parent,
                KinematicMoveRequest request,
                long continuity
        ) {
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
    private Vec3d installedCollisionUp;
    private long bodyShapeRevision;

    /** Shape/ownership epoch, independent of translation and presentation. */
    public long bodyShapeRevision() { return bodyShapeRevision; }

    public void markBodyDimensionsChanged() {
        bodyShapeRevision++;
        completedEndpointGround = null;
        pendingSoftPositionSupportRevalidation = null;
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
     * excluded by GravityMoveResult.
     */
    private Vec3d lastCommittedLocomotionDisplacement;
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

    /*
     * Stable persistent support identity derived from the completed terminal
     * endpoint query. Runtime-only: it is never serialized and is revalidated
     * against each operation's captured scene before it can transport the
     * actor.
     */
    private PersistentSupportState persistentSupportState;

    /** World-space velocity already contributed by the selected support, in blocks/tick.
     * This is provenance, never inferred from the magnitude of actor velocity. */
    private Vec3d supportVelocityContribution = Vec3d.ZERO;

    public Vec3d supportVelocityContribution() { return supportVelocityContribution; }

    public void setSupportVelocityContribution(Vec3d velocity) {
        OwnedMotion.requireFinite(velocity);
        supportVelocityContribution = velocity;
    }

    /** Receipt for a completed native normal-velocity write. The exact result token
     * prevents another/nested move from claiming it. Conversion alone is not a write. */
    public void recordLandingVelocityResponse(GravityMoveResult expected, GravitySupportContact contact) {
        if (expected == null || !isInMove() || currentMoveResult() != expected || !expected.isAuthoritative()) return;
        requireMove().publication.landingVelocityResponse = Objects.requireNonNull(contact);
    }

    /** One owner reconciles platform momentum after landing and before step damping.
     * The native normal response already installed n*(surface dot n). Tangential
     * carry still replaces the previous credit. This returns velocity, never transport. */
    public Vec3d claimSupportVelocity(GravityMoveResult expected) {
        if (expected == null || !isInMove() || currentMoveResult() != expected || !expected.isAuthoritative()) return Vec3d.ZERO;
        var publication = requireMove().publication;
        var response = publication.landingVelocityResponse;
        if (response != null) {
            var normal = response.normal();
            supportVelocityContribution = supportVelocityContribution.add(normal.multiply(
                    response.surfaceVelocity().subtract(supportVelocityContribution).dot(normal)));
            publication.landingVelocityResponse = null;
        }
        var support = expected.terminalGrounded() ? expected.supportContact().orElse(null) : null;
        if (support == null) return Vec3d.ZERO;
        if (support.faceIdentity() == null || !support.faceIdentity().dynamicSupport()) {
            supportVelocityContribution = Vec3d.ZERO;
            return Vec3d.ZERO;
        }
        var missing = support.surfaceVelocity().subtract(supportVelocityContribution);
        supportVelocityContribution = support.surfaceVelocity();
        return missing;
    }

    /** Called by the outer MOVE/TRAVEL owner after the last physical velocity write.
     * Revocation is sticky until that operation closes. Transient collision/landing
     * evidence and accepted translation remain intact; no release impulse is generated. */
    public boolean finalizeSupportVelocity(Vec3d worldVelocity) {
        OwnedMotion.requireFinite(worldVelocity);
        var outer = requireMove().outer;
        if (outer.supportRetentionRejected) return true;
        var result = outer.externalSupportTransportEndpointResult != null
                ? outer.externalSupportTransportEndpointResult : outer.publication.moveResult;
        var passive = outer.publication.passiveResult;
        var endpointOverride = outer.publication.endpointSupportOverride;
        if (result == null && (passive == null || passive.indeterminate()) && endpointOverride == null) return false;
        if (result != null && !result.isAuthoritative()) return false;
        var contact = result != null ? result.supportContact().or(result::movementSupportContact)
                : passive != null ? passive.endpointContact() : endpointOverride.supportContact();
        if (contact.isEmpty() || worldVelocity.subtract(contact.get().surfaceVelocity()).dot(contact.get().normal())
                <= CollisionTolerances.ENTERING_PLANE_EPSILON) return false;
        outer.supportRetentionRejected = true;
        if (passive != null) {
            outer.publication.passiveResult = passive.withEndpoint(Optional.empty(), passive.endpointFrame());
            lastPassiveMoveResult = outer.publication.passiveResult;
        }
        restingContactSnapshot = null;
        clearPersistentSupportState();
        clearEngineSupportTransport();
        return true;
    }

    /*
     * Latest tick/assignment gravity evaluation. This is the single physical
     * gravity truth of the current tick unless a movement operation changes
     * its query inputs, in which case the operation performs exactly one new
     * authoritative evaluation.
     */
    private GravityEvaluationSnapshot tickGravityEvaluation;

    /**
     * Previous authoritative support retained only across a numerically small
     * external position correction.
     *
     * This is a one-shot reacquisition hint, not a grounded publication.
     */
    private RestingContactSnapshot pendingSoftPositionSupportRevalidation;

    /*
     * Completed passive result from the latest outer move only.
     * This is a short-lived post-move publication for caller drag/bounce hooks,
     * never persistent support/controller state.
     */
    private PassiveGravityMoveResult lastPassiveMoveResult;
    private long lastPassiveMoveTick = Long.MIN_VALUE;
    /** First authoritative velocity write after an in-move position discontinuity. */
    private Vec3d discontinuityVelocity;

    // ------------------------------------------------------------------
    // Active transaction pointer
    // ------------------------------------------------------------------
    private MoveScope activeTransaction;

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
        return openMove(
                frame,
                GravitySample.fromFrame(frame),
                tick,
                operationType
        );
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
         * This also prevents stale reuse when two platform moves happen in the
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

    public GravityFrame frameOrSample(GravityState state, Vec3d samplePoint) {
        if (this.isInMove()) return activeReferenceFrame();
        return this.lastCompletedFrame != null
                ? this.lastCompletedFrame
                : GravityFrame.fromState(state, samplePoint);
    }

    /**
     * Installed physical symmetry axis. Environmental tangent axes and strength
     * belong to operation/completed evidence, never this collision descriptor.
     */
    public Vec3d installedCollisionUp() { return installedCollisionUp; }

    /** Environmental evidence adapted to the installed axis. This is derived,
     * never a cached collision tangent frame. */
    public GravityFrame geometryReferenceFrame() {
        if (installedCollisionUp == null) return null;
        return frameForInstalledAxis(lastCompletedFrame == null ? GravityFrame.DEFAULT : lastCompletedFrame);
    }

    public GravityFrame frameForInstalledAxis(GravityFrame evidence) {
        if (installedCollisionUp == null) throw new IllegalStateException("no installed collision axis");
        return GravityFrame.completedOnUpAxis(
                installedCollisionUp,
                evidence
        );
    }

    /** Geometry commit/rollback only. Reference and attitude are separate owners. */
    public void setInstalledCollisionAxis(Vec3d up) {
        Objects.requireNonNull(up, "up");
        if (!up.isFinite() || Math.abs(up.lengthSquared() - 1.0D) > 1.0E-10D) {
            throw new IllegalArgumentException("installed collision axis must be finite and unit length");
        }
        if (installedCollisionUp == null || installedCollisionUp.distanceSquared(up) > 1e-28) {
            bodyShapeRevision++;
            completedEndpointGround = null;
            pendingSoftPositionSupportRevalidation = null;
        }
        installedCollisionUp = up;
    }

    public void clearInstalledCollisionAxis() {
        if (installedCollisionUp != null) bodyShapeRevision++;
        installedCollisionUp = null;
        completedEndpointGround = null;
        pendingSoftPositionSupportRevalidation = null;
    }

    /**
     * The last completed outer frame (or authoritative correction reference)
     * provides continuity without extending an active operation's lifetime.
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
        this.persistentSupportState = null;
        this.supportVelocityContribution = Vec3d.ZERO;
        this.completedEndpointGround = null;
        this.pendingSoftPositionSupportRevalidation = null;
        this.lastPresentationDiscontinuityRevision = advancePresentationRevision();
    }

    /**
     * Establishes a full authoritative reference after a correction has invalidated
     * transient state and installed its collision geometry. No movement, support,
     * collision axis or local look is changed. This marks physical reference
     * discontinuity; client visual history has an independent convergence owner.
     */
    public void resetFrameContinuityTo(GravityFrame frame, long tick) {
        Objects.requireNonNull(frame, "frame");
        if (isInMove()) {
            throw new IllegalStateException("cannot replace frame continuity inside a movement");
        }
        if (tick < 0L) throw new IllegalArgumentException("tick must be non-negative");
        long revision = advancePresentationRevision();
        this.lastCompletedFrame = frame;
        this.lastPresentationTick = Math.max(this.lastPresentationTick, tick);
        this.completedPresentationFrame = new CompletedGravityFrame(
                frame, this.lastPresentationTick, revision);
        this.lastPresentationDiscontinuityRevision = revision;
        // A cached evaluation includes the old tangent completion even when its
        // physical query/context still matches. The next operation must complete
        // fresh evidence from this authoritative reference.
        this.tickGravityEvaluation = null;
    }

    /**
     * Accepts an authoritative reference endpoint as the newest completed
     * publication without declaring a presentation discontinuity.
     *
     * <p>A complete body transaction publishes its current environmental basis
     * here after installation. Presentation independently decides how to converge
     * from the displayed sample; this publication never integrates visual state.</p>
     *
     * <p>Only {@link #resetFrameContinuityTo(GravityFrame, long)} (a committed
     * explicitly discontinuous reference reset) and {@link #clearFrameContinuity()} (the
     * reference genuinely disappeared) may declare that discontinuity. This
     * method therefore never advances
     * {@link #lastPresentationDiscontinuityRevision()}.</p>
     */
    public void acceptFrameEndpoint(GravityFrame frame, long tick) {
        if (frame == null) {
            return;
        }
        if (tick < 0L) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        this.lastCompletedFrame = frame;
        this.lastPresentationTick = Math.max(this.lastPresentationTick, tick);
        this.completedPresentationFrame = new CompletedGravityFrame(
                frame, this.lastPresentationTick, advancePresentationRevision());
        // A cached evaluation includes the old tangent completion even when its
        // physical query/context still matches; the next operation must complete
        // fresh evidence from this authoritative endpoint.
        this.tickGravityEvaluation = null;
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

    /** Captured environmental evidence; the locomotion frame may use a different up axis. */
    public GravityFrame activeReferenceFrame() {
        GravityFrame operationFrame = activeFrame();
        return activeOperationEvaluation().map(GravityEvaluationSnapshot::frame).orElse(operationFrame);
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
    public void supersedeMovement(Vec3d destination) {
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
    public void recordPostDiscontinuityVelocity(Vec3d velocity) {
        Objects.requireNonNull(velocity, "velocity");
        if (!isInMove()
                || discontinuityDestination() == null
                || discontinuityVelocity != null) {
            return;
        }
        discontinuityVelocity = new Vec3d(velocity.x(), velocity.y(), velocity.z());
    }

    /** Consumes the captured callback velocity at the outer handoff. */
    public Vec3d consumePostDiscontinuityVelocity() {
        Vec3d captured = discontinuityVelocity;
        discontinuityVelocity = null;
        return captured;
    }

    public Vec3d discontinuityDestination() {
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
                    "Move scope belongs to another operation state"
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
        Optional<GravitySupportContact> terminalContact = Optional.empty();

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
                 * The platform move remains the sole locomotion/path authority.
                 */
                GravityMoveResult locomotionResult =
                        closing.publication.moveResult;

                boolean locomotionCommitted =
                        locomotionResult != null
                                && !locomotionResult.indeterminate();

                this.lastCommittedLocomotionDisplacement =
                        locomotionCommitted
                                ? locomotionResult.locomotionMovement()
                                : Vec3d.ZERO;

                this.lastCommittedMovementGameTick =
                        scope.presentationTick;

                /*
                 * An accepted external inherited-support transport may have
                 * moved the final body after the ordinary move endpoint.
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
                 * External path-contact evidence may preserve same-commit
                 * gameplay grounding after a valid supporting collision, exactly
                 * as GravityEngine's own supportingContactDuringMove can.
                 *
                 * It is NOT endpoint support. Terminal support remains owned
                 * exclusively by the actual final endpoint result.
                 */
                boolean externalSupportingContactDuringMove =
                        closing.publication
                                .externalSupportingContactDuringMove;

                if (locomotionCommitted && endpointDeterminate) {
                    boolean terminalSupported =
                            endpointResult
                                    .terminalGrounded() && !closing.supportRetentionRejected;

                    terminalContact = terminalSupported ? endpointResult.supportContact() : Optional.empty();
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
                                     * Same-commit ground continuity may survive a real
                                     * supporting collision even when the character has just
                                     * walked off the endpoint.
                                     */
                                    locomotionResult
                                            .gameplayGrounded()
                                            || externalSupportingContactDuringMove,

                                    /*
                                     * Endpoint support is never inferred from external
                                     * first-collision evidence. Only the actual final
                                     * endpoint result owns it.
                                     */
                                    terminalSupported
                            );
                } else {
                    this.movementGroundContinuity = null;
                    this.completedEndpointGround = null;
                }

                updateRestingContactSnapshot(
                        scope.presentationTick,
                        endpointDeterminate && !closing.supportRetentionRejected
                                ? endpointResult
                                : null
                );

                /*
                 * Only the real terminal endpoint result may establish
                 * persistent support identity. Path/first-contact evidence is
                 * deliberately insufficient.
                 */
                updatePersistentSupportState(
                        scope.presentationTick,
                        endpointDeterminate && !closing.supportRetentionRejected
                                ? endpointResult
                                : null,
                        closing.collisionOperation
                );
                var passive = closing.publication.passiveResult;
                if (passive != null && !passive.indeterminate()) {
                    boolean supported = passive.supported() && !closing.supportRetentionRejected;
                    this.completedEndpointGround = new CompletedEndpointGround(
                            endpointGroundRevision = Math.incrementExact(endpointGroundRevision), supported);
                    if (supported && passive.endpointContact().isPresent()) {
                        var witness = new EndpointSupportWitness(passive.endpointContact().get(), scope.presentationTick,
                                closing.collisionOperation.time().sceneRevision());
                        this.persistentSupportState = PersistentSupportState.from(witness, closing.collisionOperation.scene()).orElse(null);
                    }
                }
            }
            var endpointOverride = closing.publication.endpointSupportOverride;
            if (endpointOverride != null && !endpointOverride.indeterminate()) {
                boolean supported = endpointOverride.stableGround() && !closing.supportRetentionRejected;
                terminalContact = supported ? endpointOverride.supportContact() : Optional.empty();
                this.completedEndpointGround = new CompletedEndpointGround(
                        endpointGroundRevision = Math.incrementExact(endpointGroundRevision), supported);
                if (supported && endpointOverride.supportContact().isPresent()) {
                    this.persistentSupportState = PersistentSupportState.from(new EndpointSupportWitness(
                            endpointOverride.supportContact().get(), scope.presentationTick,
                            closing.collisionOperation.time().sceneRevision()), closing.collisionOperation.scene()).orElse(null);
                }
            }
            if (closing.publication.endpointSupportIndeterminate) {
                this.completedEndpointGround = null;
                this.restingContactSnapshot = null;
                clearPersistentSupportState();
            }
            GravityFrame reference = closing.publication.evaluation == null
                    ? closing.frame : closing.publication.evaluation.frame();
            this.lastCompletedFrame = reference;
            this.lastPresentationTick = scope.presentationTick;

            this.completedPresentationFrame =
                    new CompletedGravityFrame(
                            reference,
                            scope.presentationTick,
                            advancePresentationRevision()
                    );

            this.activeTransaction = null;

            clearMovementProvenance();
            var locomotion = closing.publication.moveResult;
            if (closing.captureCompletion && locomotion != null && locomotion.isAuthoritative()
                    && movementGroundContinuity != null && completedEndpointGround != null
                    && !closing.publication.endpointSupportIndeterminate
                    && (endpointOverride == null || !endpointOverride.indeterminate())) {
                boolean supported = completedEndpointGround.terminalSupported();
                closing.completedMovementFacts = new CompletedMovementFacts(
                        reference, locomotion.fallDistanceVertical(reference.up()),
                        movementGroundContinuity.gameplayGrounded(), supported,
                        locomotion.landedOnStableSupport()
                                || closing.publication.externalSupportingContactDuringMove,
                        supported ? terminalContact : Optional.empty());
            }
        } else {
            this.activeTransaction = closing.parent;
            if (closing.publication == closing) {
                // Passive material hooks have a same-tick handoff in addition
                // to current-result access. Callback re-entry must restore that
                // handoff to the resumed invocation too.
                this.lastPassiveMoveResult = discontinuityDestination() == null
                        ? activeTransaction.publication.passiveResult : null;
                this.lastPassiveMoveTick = this.lastPassiveMoveResult == null
                        ? Long.MIN_VALUE
                        : activeTransaction.collisionOperation.time().gameTick();
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
    // Current move result (one physical move lifecycle only)
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
    public void beginMovement(GravityCollisionRoute route) {
        requireMove().beginMovement(route);
    }

    public GravityCollisionRoute movementCollisionRoute() {
        return isInMove() ? activeTransaction.publication.collisionRoute : null;
    }

    /**
     * Loader-neutral projection of the committed move for the platform commit
     * boundary. The target turns these facts into its own vanilla collision
     * state; the common runtime never builds a platform value.
     */
    public MovementCommitFacts currentMovementCommitFacts() {
        return isInMove()
                ? activeTransaction.publication.movementCommitFacts
                : null;
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
                ? activeTransaction.publication.stepStartSupport
                : Optional.empty();
    }

    /**
     * Records the generic fact that target-only external movement evidence
     * proved a supporting contact somewhere on this movement path.
     *
     * <p>The target keeps the full external evidence; the common runtime only
     * consumes this platform-neutral boolean.</p>
     */
    public void setExternalSupportingContactDuringMove(boolean value) {
        requireMove().setExternalSupportingContactDuringMove(value);
    }

    public boolean externalSupportingContactDuringMove() {
        return isInMove()
                && activeTransaction
                .publication
                .externalSupportingContactDuringMove;
    }

    public void stageExternalSupportTransport(
            Vec3d startPosition,
            Vec3d resolvedTransport
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
     * replaces the ordinary move result or its locomotion displacement.</p>
     */
    public void stageExternalSupportTransport(
            Vec3d startPosition,
            Vec3d resolvedTransport,
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
            Vec3d beforePosition,
            Vec3d afterPosition
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

    /**
     * Stages the engine-owned persistent-support transport for this outer
     * operation. The displacement is consumed at most once.
     */
    public void stageEngineSupportTransport(
            SupportTransport transport
    ) {
        requireMove().stageEngineSupportTransport(
                Objects.requireNonNull(transport, "transport")
        );
    }

    /** The staged or already-applied engine support transport, or empty. */
    public Optional<SupportTransport> engineSupportTransport() {
        return isInMove()
                ? activeTransaction.outer.engineSupportTransport()
                : Optional.empty();
    }

    /** The engine support transport that has not yet affected a solve, or empty. */
    public Optional<SupportTransport> pendingEngineSupportTransport() {
        return isInMove()
                ? activeTransaction.outer.pendingEngineSupportTransport()
                : Optional.empty();
    }

    /**
     * Consumes the engine support transport exactly once for this operation.
     * Nested scopes share the outer operation's single transport.
     */
    public Optional<SupportTransport> consumeEngineSupportTransport() {
        return isInMove()
                ? activeTransaction.outer.consumeEngineSupportTransport()
                : Optional.empty();
    }

    public void clearEngineSupportTransport() {
        if (isInMove()) {
            activeTransaction.outer.clearEngineSupportTransport();
        }
    }

    public void setCurrentMoveResult(GravityMoveResult result) {
        requireMove().acceptMove(result);
    }

    public void clearCurrentMoveResult() {
        if (isInMove()) activeTransaction.clearResult();
    }

    private MoveScope requireMove() {
        if (!isInMove()) throw new IllegalStateException("move publication requires an active operation");
        return activeTransaction;
    }

    /** Same-tick handoff only; stale movement cannot grant another tick of ground. */
    public Optional<MovementGroundContinuity> lastCommittedMovementGroundContinuity(long gameTick) {
        return movementGroundContinuity != null && movementGroundContinuity.gameTick() == gameTick
                ? Optional.of(movementGroundContinuity) : Optional.empty();
    }

    /** Restores the immutable handoff as part of an owning physical transaction rollback. */
    public void restoreMovementGroundContinuity(MovementGroundContinuity snapshot) {
        this.movementGroundContinuity = snapshot;
    }

    public Optional<Vec3d> lastCommittedLocomotionDisplacement(long gameTick) {
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
                Objects.requireNonNull(snapshot, "snapshot");
    }

    public Optional<RestingContactSnapshot>
    consumeSoftPositionSupportRevalidation() {
        RestingContactSnapshot snapshot =
                this.pendingSoftPositionSupportRevalidation;

        this.pendingSoftPositionSupportRevalidation = null;

        return Optional.ofNullable(snapshot);
    }

    // ------------------------------------------------------------------
    // Gravity truth ownership
    // ------------------------------------------------------------------

    /**
     * Publishes the single gravity evaluation of this tick.
     *
     * <p>The assignment/refresh lifecycle calls this after one full-query
     * evaluation. A stationary entity therefore still owns a current physical
     * gravity truth even though no {@code move()} happens.</p>
     *
     * <p>Publishing while a movement operation is open is an architectural
     * error: it would introduce a competing "current gravity" value inside one
     * logical operation.</p>
     */
    public void publishTickEvaluation(GravityEvaluationSnapshot evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (isInMove()) {
            throw new IllegalStateException(
                    "cannot publish a tick gravity evaluation inside an "
                            + "active movement operation"
            );
        }
        this.tickGravityEvaluation = evaluation;
    }

    /** Latest tick/assignment evaluation, or empty when none was published. */
    public Optional<GravityEvaluationSnapshot> tickGravityEvaluation() {
        return Optional.ofNullable(this.tickGravityEvaluation);
    }

    /**
     * Publishes the operation result as the current committed physical truth,
     * but only when its authority still describes the committed assignment.
     *
     * <p>An assignment may legally change while a movement operation is open;
     * in that case the operation snapshot is no longer a coherent committed
     * truth and this method refuses to publish it.</p>
     */
    public boolean publishCommittedTickEvaluation(
            GravityEvaluationSnapshot evaluation,
            GravityEvaluationContext committedContext
    ) {
        return publishCommittedTickEvaluation(
                evaluation,
                committedContext,
                evaluation.query()
        );
    }

    /**
     * Publishes an operation evaluation as the current tick truth only when both
     * its authority/context and its physical query still describe the entity's
     * current state.
     *
     * <p>A movement operation normally evaluates gravity at operation start.
     * After the entity has moved, that snapshot remains the authoritative truth
     * of that completed operation, but it must not automatically become the
     * current post-operation gravity snapshot when position or velocity changed.</p>
     */
    public boolean publishCommittedTickEvaluation(
            GravityEvaluationSnapshot evaluation,
            GravityEvaluationContext committedContext,
            GravityFieldQuery currentQuery
    ) {
        Objects.requireNonNull(
                evaluation,
                "evaluation"
        );
        Objects.requireNonNull(
                committedContext,
                "committedContext"
        );
        Objects.requireNonNull(
                currentQuery,
                "currentQuery"
        );

        if (isInMove()) {
            throw new IllegalStateException(
                    "cannot publish a committed gravity evaluation inside an "
                            + "active movement operation"
            );
        }

        if (!evaluation.matchesContext(committedContext)
                || !evaluation.matchesQuery(currentQuery)) {
            /*
             * Do not leave either the just-completed operation snapshot or an
             * older snapshot visible as the current physical truth.
             *
             * The normal entity/tick lifecycle will publish the next coherent
             * evaluation.
             */
            this.tickGravityEvaluation = null;
            return false;
        }

        this.tickGravityEvaluation = evaluation;
        return true;
    }

    /**
     * Tick/assignment evaluation that already covered exactly these query
     * inputs, or empty when the operation must evaluate once for itself.
     */
    public Optional<GravityEvaluationSnapshot> gravityEvaluationFor(
            GravityFieldQuery query
    ) {
        return this.tickGravityEvaluation != null
                && this.tickGravityEvaluation.matchesQuery(query)
                ? Optional.of(this.tickGravityEvaluation)
                : Optional.empty();
    }

    /**
     * Installs the one authoritative evaluation of the currently open
     * operation.
     *
     * <p>A second install with different query inputs or a different authority
     * binding fails fast: one operation owns exactly one gravity truth, and
     * collision, locomotion, frame selection, support and publication must all
     * consume it.</p>
     */
    public void installOperationEvaluation(
            GravityEvaluationSnapshot evaluation
    ) {
        Objects.requireNonNull(evaluation, "evaluation");
        requireMove().installEvaluation(evaluation);
    }

    /**
     * Gravity truth of the active operation, falling back to the current tick
     * evaluation outside a move.
     */
    public Optional<GravityEvaluationSnapshot> activeGravityEvaluation() {
        if (isInMove()) {
            GravityEvaluationSnapshot installed =
                    activeTransaction.publication.evaluation;
            if (installed != null) {
                return Optional.of(installed);
            }
        }
        return Optional.ofNullable(this.tickGravityEvaluation);
    }

    /** The installed evaluation of the active operation, never a tick fallback. */
    public Optional<GravityEvaluationSnapshot> activeOperationEvaluation() {
        if (!isInMove()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                activeTransaction.publication.evaluation
        );
    }

    // ------------------------------------------------------------------
    // Persistent support identity / transport
    // ------------------------------------------------------------------

    /** Stable terminal support identity carried across ticks, or {@code null}. */
    public PersistentSupportState persistentSupportState() {
        return this.persistentSupportState;
    }

    /** Explicit lifecycle override used by focused transport tests. */
    public void setPersistentSupportState(
            PersistentSupportState support
    ) {
        this.persistentSupportState = support;
    }

    public void clearPersistentSupportState() {
        this.persistentSupportState = null;
        this.supportVelocityContribution = Vec3d.ZERO;
    }

    /**
     * Revalidates persistent support against one captured scene and resolves
     * its transport for that operation.
     *
     * <p>A missing/replaced/discontinuous obstacle clears persistent support
     * safely and returns empty; it never fabricates grounding.</p>
     */
    public Optional<SupportTransport> resolveSupportTransport(
            CollisionScene scene
    ) {
        Objects.requireNonNull(scene, "scene");
        if (this.persistentSupportState == null) {
            return Optional.empty();
        }

        Optional<SupportTransport> transport =
                SupportTransportResolver.resolve(
                        this.persistentSupportState,
                        scene
                );

        if (transport.isEmpty()) {
            this.persistentSupportState = null;
            this.supportVelocityContribution = Vec3d.ZERO;
        }
        return transport;
    }

    /** Captures a selected face address for the next query. This does not retain
     * an obstacle or scene; discontinuity/detach and absent endpoint support
     * clear it. Static plane preservation has its own stricter eligibility. */
    public static RestingContactSnapshot captureRestingContactSnapshot(
            Optional<GravitySupportContact> support,
            Optional<CellPos> supportBlock,
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
                contact.normal(),
                contact.surfaceVelocity(),
                contact.contactPoint(),
                contact.geometryKind(),
                supportBlock,
                gameTick,
                contact.faceIdentity()
        );
    }

    /**
     * Re-derives the cross-tick resting-support snapshot from the completed
     * endpoint result.
     *
     * @return what happened, so a target boundary can emit the matching
     *         platform diagnostic without the common runtime depending on a
     *         platform logger
     */
    public RestingContactUpdate updateRestingContactSnapshot(
            long gameTick,
            GravityMoveResult result
    ) {
        if (result == null || result.indeterminate() || !result.terminalGrounded()) {
            boolean hadSnapshot = this.restingContactSnapshot != null;
            this.restingContactSnapshot = null;
            if (!hadSnapshot) {
                return RestingContactUpdate.UNCHANGED;
            }
            return result == null
                    ? RestingContactUpdate.CLEARED_NO_MOVE_RESULT
                    : result.indeterminate()
                    ? RestingContactUpdate.CLEARED_INDETERMINATE
                    : RestingContactUpdate.CLEARED_NOT_GROUNDED;
        }
        RestingContactSnapshot captured =
                captureRestingContactSnapshot(
                        result.supportContact(),
                        result.supportBlock(),
                        result.terminalGrounded(),
                        gameTick
                );
        if (captured == null) {
            boolean hadSnapshot = this.restingContactSnapshot != null;
            this.restingContactSnapshot = null;
            return hadSnapshot
                    ? RestingContactUpdate.CLEARED_UNSUPPORTED_OR_UNTRUSTED
                    : RestingContactUpdate.UNCHANGED;
        }
        this.restingContactSnapshot = captured;
        return RestingContactUpdate.CAPTURED;
    }

    /** Outcome of one {@link #updateRestingContactSnapshot} call. */
    public enum RestingContactUpdate {
        UNCHANGED,
        CAPTURED,
        CLEARED_NO_MOVE_RESULT,
        CLEARED_INDETERMINATE,
        CLEARED_NOT_GROUNDED,
        CLEARED_UNSUPPORTED_OR_UNTRUSTED
    }

    /**
     * Derives the cross-tick persistent support identity from the completed
     * terminal endpoint result and the scene it was solved against.
     */
    private void updatePersistentSupportState(
            long gameTick,
            GravityMoveResult result,
            CollisionOperationContext operation
    ) {
        if (result == null
                || result.indeterminate()
                || !result.terminalGrounded()
                || result.supportContact().isEmpty()
                || operation == null) {
            this.persistentSupportState = null;
            this.supportVelocityContribution = Vec3d.ZERO;
            return;
        }

        EndpointSupportWitness witness = new EndpointSupportWitness(
                result.supportContact().get(),
                gameTick,
                operation.time().sceneRevision()
        );

        this.persistentSupportState =
                PersistentSupportState.from(
                        witness,
                        operation.scene()
                ).orElse(null);
        if (this.persistentSupportState == null || this.persistentSupportState.staticSupport()) this.supportVelocityContribution = Vec3d.ZERO;
    }

    public PassiveGravityMoveResult currentPassiveMoveResult() {
        return isInMove() ? activeTransaction.publication.passiveResult : null;
    }

    /** Separate endpoint evidence; never replaces the integration query or frozen frame. */
    public Optional<GravityEvaluationSnapshot> endpointGravityEvaluation() {
        return isInMove() ? Optional.ofNullable(activeTransaction.publication.endpointEvaluation) : Optional.empty();
    }

    public void setEndpointGravityEvaluation(GravityEvaluationSnapshot evaluation) {
        requireMove().publication.endpointEvaluation = Objects.requireNonNull(evaluation);
    }

    public boolean endpointSupportIndeterminate() {
        return isInMove() && activeTransaction.publication.endpointSupportIndeterminate;
    }

    public void markEndpointSupportIndeterminate() {
        requireMove().publication.endpointSupportIndeterminate = true;
    }

    public Optional<GravityGroundProbe.Result> endpointSupportOverride() {
        return isInMove() ? Optional.ofNullable(activeTransaction.publication.endpointSupportOverride) : Optional.empty();
    }

    public void setEndpointSupportOverride(GravityGroundProbe.Result result) {
        requireMove().publication.endpointSupportOverride = Objects.requireNonNull(result);
        if(result.indeterminate()) markEndpointSupportIndeterminate();
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

    /** Same-input collide guard for a repeated identical re-entry. */
    public Optional<Vec3d> preResolvedTranslation(Vec3d actualInput, CollisionBody body) {
        if (!this.isInMove()) return Optional.empty();
        return this.activeTransaction.preResolvedTranslation(actualInput, body);
    }

    public void setPreResolved(Vec3d input, CollisionBody body, Vec3d translation) {
        if (!this.isInMove()) {
            throw new IllegalStateException(
                    "Cannot record a pre-resolved translation outside a move"
            );
        }
        this.activeTransaction.setPreResolved(input, body, translation);
    }

    /** The single operation-owned collision context, or {@code null} outside an outer move. */
    public CollisionOperationContext collisionOperation() {
        return this.isInMove()
                ? this.activeTransaction.collisionOperation
                : null;
    }

    /**
     * The operation scope that currently owns movement publication, or
     * {@code null} outside a move.
     *
     * <p>This is a pure identity handle used to key target-owned, per-move
     * interop evidence to the exact publication it belongs to. It exposes no
     * platform type and no mutable state.</p>
     */
    public MoveScope activePublicationScope() {
        return this.activeTransaction == null
                ? null
                : this.activeTransaction.publication;
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
     * Clears movement/collision/support transients without touching the
     * environmental gravity reference or presentation frame continuity.
     */
    public void clearMovementTransientState() {
        invalidateMovementContinuity();
        if (this.isInMove()) {
            this.activeTransaction.clearTransient();
        }
    }

    /**
     * Clears every influence-scoped transient. This is reserved for true
     * authority/world/reference discontinuity, not ordinary body fallback.
     */
    public void clearInfluenceTransientState() {
        clearMovementTransientState();
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
        private final GravityOperationState owner;
        private final MoveScope parent;
        private final MoveScope outer;
        private MoveScope publication;
        private boolean executingMovement;
        private GravityCollisionRoute collisionRoute;
        private MovementCommitFacts movementCommitFacts;
        private CompletedMovementFacts completedMovementFacts;
        private boolean captureCompletion;
        private GravityMoveResult moveResult;
        private PassiveGravityMoveResult passiveResult;
        private GravityEvaluationSnapshot endpointEvaluation;
        private boolean endpointSupportIndeterminate;
        private GravityGroundProbe.Result endpointSupportOverride;
        private GravityEvaluationSnapshot evaluation;
        private boolean externalSupportingContactDuringMove;
        private Optional<RestingContactSnapshot> stepStartSupport;
        private GravitySupportContact landingVelocityResponse;
        private boolean supportRetentionRejected;
        private Vec3d pendingExternalSupportTransportStart;
        private Vec3d pendingExternalSupportTransportDestination;

        /*
         * Endpoint authority produced by the latest accepted external support
         * transport. This is deliberately NOT publication.moveResult: the
         * ordinary platform move still owns locomotion/path facts.
         */
        private GravityMoveResult externalSupportTransportEndpointResult;

        /*
         * Engine-owned persistent-support transport for this outer operation.
         * It is separate from the external compatibility transport slot and is
         * consumed at most once before the authoritative collision solve.
         */
        private SupportTransport engineSupportTransport;
        private boolean engineSupportTransportConsumed;

        private Vec3d discontinuityDestination;
        private final GravityFrame frame;
        private final GravitySample sample;
        private final long presentationTick;
        private final GravityOperationType operationType;
        private CollisionOperationContext collisionOperation;
        private Vec3d preResolvedInput;
        private CollisionBody preResolvedBody;
        private MovementEvidence preResolvedEvidence;
        private CollisionOperationContext preResolvedOperation;
        private Vec3d preResolvedTranslation;
        private final boolean outermost;
        private boolean closed;

        private MoveScope(
                GravityOperationState owner,
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

        /**
         * Whether this scope has already been closed.
         *
         * <p>Target-owned per-publication interop evidence uses this read-only
         * fact to drop entries for scopes that can no longer become the active
         * publication again, without introducing a second lifecycle owner.</p>
         */
        public boolean closed() {
            return this.closed;
        }

        /** Opt in only for a consumer that must outlive this scope. */
        public void captureCompletedMovementFacts() {
            if (closed || !outermost || operationType != GravityOperationType.MOVE) {
                throw new IllegalStateException("completion capture requires an open outer MOVE");
            }
            captureCompletion = true;
        }

        /** Only a normal outer close produces these detached facts. */
        public CompletedMovementFacts completedMovementFacts() {
            if (!closed) throw new IllegalStateException("movement has not closed");
            return completedMovementFacts;
        }

        public boolean outermost() {
            return this.outermost;
        }

        /**
         * Installs the one authoritative gravity evaluation of this
         * publication. A conflicting second install fails fast.
         */
        private void installEvaluation(
                GravityEvaluationSnapshot candidate
        ) {
            if (this.evaluation != null
                    && !this.evaluation.matchesInputs(
                            candidate.samplePoint(),
                            candidate.velocity(),
                            candidate.gameTick(),
                            candidate.intervalTicks()
                    )) {
                throw new IllegalStateException(
                        "operation gravity truth changed inside one movement "
                                + "operation: previous="
                                + this.evaluation.query()
                                + " candidate=" + candidate.query()
                );
            }
            if (this.evaluation != null
                    && !this.evaluation.authority()
                    .sameBinding(candidate.authority())) {
                throw new IllegalStateException(
                        "operation gravity authority changed inside one "
                                + "movement operation"
                );
            }
            if (this.evaluation != null
                    && !this.evaluation.matchesContext(
                            candidate.context()
                    )) {
                throw new IllegalStateException(
                        "operation gravity application context changed "
                                + "inside one movement operation"
                );
            }
            if (this.evaluation == null) {
                this.evaluation = candidate;
            }
        }

        private void beginMovement(GravityCollisionRoute route) {
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
            publication.endpointEvaluation = null;
            publication.endpointSupportIndeterminate = false;
            publication.endpointSupportOverride = null;

            publication.externalSupportingContactDuringMove = false;

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

            publication.landingVelocityResponse = null;
            publication.moveResult =
                    Objects.requireNonNull(result, "result");
            publication.passiveResult = null;

            boolean accepted =
                    !result.indeterminate();

            boolean grounded =
                    accepted
                            && result.gameplayGrounded();

            /*
             * The supporting block describes the stable cell beneath a grounded
             * entity. A physical steep contact must not enter this field.
             *
             * supportingContactDuringMove may keep ground true for this commit
             * after the terminal body has already left the face; in that case
             * the block is intentionally empty and the platform's
             * grounded-without-blocks semantics apply.
             */
            Optional<CellPos> mainSupportingBlock =
                    accepted && result.terminalGrounded()
                            ? result.supportBlock()
                            : Optional.empty();

            publication.movementCommitFacts =
                    new MovementCommitFacts(
                            grounded,
                            accepted && result.blockedTangent(),
                            accepted
                                    && (result.blockedDown()
                                    || result.blockedUp()),
                            accepted
                                    && result.supportingContactDuringMove(),
                            mainSupportingBlock
                    );
        }

        private void acceptPassive(PassiveGravityMoveResult result) {
            if (outer.discontinuityDestination != null) return;
            clearResult();
            publication.passiveResult = Objects.requireNonNull(result, "result");
            boolean accepted = !result.indeterminate();
            publication.movementCommitFacts =
                    new MovementCommitFacts(
                            accepted && result.supported(),
                            accepted && result.blockedTangent(),
                            accepted
                                    && (result.blockedDown()
                                    || result.blockedUp()),
                            accepted && result.blockedDown(),
                            accepted && result.supported()
                                    ? result.supportBlock()
                                    : Optional.empty()
                    );
        }

        private void clearResult() {
            publication.landingVelocityResponse = null;
            publication.moveResult = null;
            publication.passiveResult = null;
            publication.movementCommitFacts = null;
        }

        private void invalidateMovement() {
            clearResult();
            owner.completedEndpointGround = null;

            outer.pendingExternalSupportTransportStart = null;
            outer.pendingExternalSupportTransportDestination = null;
            outer.externalSupportTransportEndpointResult = null;
        }

        private Optional<Vec3d> preResolvedTranslation(Vec3d actualInput, CollisionBody body) {
            Objects.requireNonNull(actualInput, "actualInput");
            if (outer.discontinuityDestination != null) return Optional.empty();
            if (this.preResolvedInput == null
                    || this.preResolvedTranslation == null) {
                return Optional.empty();
            }
            if (owner.movementEvidence == null
                    || preResolvedEvidence != owner.movementEvidence
                    || preResolvedOperation != collisionOperation
                    || !Objects.equals(preResolvedBody, body)
                    || !preResolvedInput.equals(actualInput)) {
                return Optional.empty();
            }
            return Optional.of(this.preResolvedTranslation);
        }

        private void setPreResolved(Vec3d input, CollisionBody body, Vec3d translation) {
            if (outer.discontinuityDestination != null) return;
            this.preResolvedBody = Objects.requireNonNull(body, "body");
            this.preResolvedEvidence = owner.movementEvidence;
            this.preResolvedOperation = collisionOperation;
            this.preResolvedInput = Objects.requireNonNull(input, "input");
            this.preResolvedTranslation = Objects.requireNonNull(
                    translation,
                    "translation"
            );
        }

        private void setCollisionOperation(CollisionOperationContext operation) {
            Objects.requireNonNull(operation, "operation");
            if (this.collisionOperation != null
                    && this.collisionOperation != operation) {
                throw new IllegalStateException(
                        "collision scene already captured for this operation"
                );
            }
            this.collisionOperation = operation;
        }

        private void setStepStartSupport(RestingContactSnapshot support) {
            this.stepStartSupport = Optional.ofNullable(support);
        }

        public Optional<RestingContactSnapshot> stepStartSupport() {
            return stepStartSupport;
        }

        private void setExternalSupportingContactDuringMove(
                boolean value
        ) {
            if (outer.discontinuityDestination != null) {
                return;
            }
            publication.externalSupportingContactDuringMove = value;
        }

        private void stageExternalSupportTransport(
                Vec3d start,
                Vec3d destination,
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
                    new Vec3d(
                            start.x(),
                            start.y(),
                            start.z()
                    );

            target.pendingExternalSupportTransportDestination =
                    new Vec3d(
                            destination.x(),
                            destination.y(),
                            destination.z()
                    );

            /*
             * Replacing a previously consumed transport endpoint is valid: a later
             * transport in the same logical operation owns the final endpoint.
             */
            target.externalSupportTransportEndpointResult =
                    endpointResult;
        }

        private boolean consumeExternalSupportTransportPositionWrite(
                Vec3d before,
                Vec3d after
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
            this.preResolvedBody = null;
            this.preResolvedEvidence = null;
            this.preResolvedOperation = null;
            this.preResolvedInput = null;
            this.preResolvedTranslation = null;
        }

        private void stageEngineSupportTransport(
                SupportTransport transport
        ) {
            MoveScope target = outer;
            if (target.engineSupportTransport != null
                    && !target.engineSupportTransportConsumed) {
                throw new IllegalStateException(
                        "engine support transport already staged"
                );
            }
            target.engineSupportTransport = transport;
            target.engineSupportTransportConsumed = false;
        }

        private Optional<SupportTransport> engineSupportTransport() {
            return Optional.ofNullable(outer.engineSupportTransport);
        }

        private Optional<SupportTransport> pendingEngineSupportTransport() {
            return outer.engineSupportTransportConsumed
                    ? Optional.empty()
                    : Optional.ofNullable(outer.engineSupportTransport);
        }

        private Optional<SupportTransport> consumeEngineSupportTransport() {
            if (outer.engineSupportTransportConsumed
                    || outer.engineSupportTransport == null) {
                return Optional.empty();
            }
            outer.engineSupportTransportConsumed = true;
            return Optional.of(outer.engineSupportTransport);
        }

        private void clearEngineSupportTransport() {
            outer.engineSupportTransport = null;
            outer.engineSupportTransportConsumed = false;
        }

        @Override
        public void close() {
            this.owner.closeMove(this);
        }
    }

    public static final class GeometryScope implements AutoCloseable {
        private final GravityOperationState owner;
        private final int depthAtOpen;
        private boolean closed;

        private GeometryScope(GravityOperationState owner, int depthAtOpen) {
            this.owner = owner;
            this.depthAtOpen = depthAtOpen;
        }

        @Override
        public void close() {
            this.owner.closeGeometry(this);
        }
    }
}
