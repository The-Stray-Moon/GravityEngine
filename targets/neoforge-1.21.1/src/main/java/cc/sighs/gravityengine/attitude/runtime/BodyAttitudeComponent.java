package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyAttitudeStepResult;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;

import java.util.Objects;

/**
 * Per-player store of locally produced or installed replicated actor state.
 *
 * <p>The component owns the actor-attitude history
 * ({@link BodyAttitudeState}) together with the body-relative view state
 * ({@link BodyRelativeViewState}).  Both are published atomically at the local tick or replication
 * boundary so a render sample always sees one consistent
 * {@code Qbody + body-local view} pair.</p>
 */
public final class BodyAttitudeComponent {
    // The "authoritative" metadata names refer to server-owned stream/config/order only.
    // They do not give the server authority to reproduce or correct client-produced actor physics.
    public static final long NO_LOCAL_SIMULATION_STEP = Long.MIN_VALUE;
    public static final long NO_AUTHORITATIVE_SERVER_GAME_TICK = -1L;
    public static final long NO_AUTHORITATIVE_REVISION = -1L;
    public static final long NO_AUTHORITATIVE_STREAM_EPOCH = 0L;
    public static final long NO_AUTHORITATIVE_CONFIG_GENERATION = 0L;

    private volatile Snapshot snapshot = new Snapshot(
            BodyAttitudeState.uninitialized(),
            BodyRelativeViewState.uninitialized(),
            null,
            BodyAttitudeDecision.suspended(
                    BodyAttitudeSuspensionReason.NOT_EVALUATED),
            BodyAttitudeContinuity.PENDING_BOOTSTRAP,
            BodyAttitudeOwnership.INACTIVE,
            NO_LOCAL_SIMULATION_STEP,
            0L,
            0L,
            NO_AUTHORITATIVE_SERVER_GAME_TICK,
            NO_AUTHORITATIVE_REVISION,
            NO_AUTHORITATIVE_STREAM_EPOCH,
            NO_AUTHORITATIVE_CONFIG_GENERATION,
            false
    );


    public Snapshot snapshot() {
        return this.snapshot;
    }

    /** Client entity tick start, analogous to Entity.setOldPosAndRot; no physical state changes. */
    public synchronized void beginBodyTick() {
        Snapshot current = this.snapshot;
        BodyAttitudeState state = current.state().withCurrentAsPrevious();
        if (state == current.state()) return;
        this.snapshot = new Snapshot(state, current.view(), current.lastStepResult(),
                current.decision(), current.continuity(), current.ownership(),
                current.lastLocalSimulationStep(), current.bootstrapCount(), current.lifecycleEpoch(),
                current.authoritativeServerGameTick(), current.authoritativeRevision(),
                current.authoritativeStreamEpoch(), current.authoritativeConfigGeneration(),
                current.authoritativeStreamOpen());
    }

    /** Rollback of an actor/view state installation. */
    public synchronized void restoreSnapshot(Snapshot previous) {
        this.snapshot = Objects.requireNonNull(previous);
    }

    public BodyAttitudeState state() {
        return this.snapshot.state();
    }

    public BodyRelativeViewState view() {
        return this.snapshot.view();
    }

    public BodyAttitudeStepResult lastStepResult() {
        return this.snapshot.lastStepResult();
    }

    public BodyAttitudeDecision currentDecision() {
        return this.snapshot.decision();
    }

    public BodyAttitudeContinuity continuity() {
        return this.snapshot.continuity();
    }

    /** The only common-side boundary from simulation state to presentation. */
    public RenderableSnapshot renderableSnapshot() {
        Snapshot current = this.snapshot;
        if (current.ownership() == BodyAttitudeOwnership.INACTIVE) {
            return null;
        }
        if (!current.decision().active()
                || current.continuity() != BodyAttitudeContinuity.CONTINUOUS
                || !current.state().initialized()
                || current.state().revision() < 0L) {
            return null;
        }
        return new RenderableSnapshot(
                current.state(), current.view(),
                current.decision(),
                current.lastLocalSimulationStep(),
                current.lifecycleEpoch(),
                current.authoritativeServerGameTick(),
                current.authoritativeRevision(),
                current.authoritativeStreamEpoch(),
                current.authoritativeConfigGeneration()
        );
    }

    public BodyAttitudeOwnership ownership() {
        return this.snapshot.ownership();
    }

    public synchronized void invalidateContinuity() {
        Snapshot current = this.snapshot;
        this.snapshot = new Snapshot(
                current.state(),
                BodyRelativeViewState.uninitialized(),
                null,
                BodyAttitudeDecision.suspended(
                        BodyAttitudeSuspensionReason.LIFECYCLE_INVALIDATED),
                BodyAttitudeContinuity.INVALID,
                BodyAttitudeOwnership.INACTIVE,
                NO_LOCAL_SIMULATION_STEP,
                current.bootstrapCount(),
                Math.incrementExact(current.lifecycleEpoch()),
                current.authoritativeServerGameTick(),
                current.authoritativeRevision(),
                current.authoritativeStreamEpoch(),
                current.authoritativeConfigGeneration(),
                false
        );
    }

    /**
     * Carries the retired authoritative-stream watermark into a replacement
     * client player instance. The retired epoch remains closed: only a
     * strictly newer server stream can establish the replacement baseline.
     */
    public synchronized void retireAuthoritativeStream(long retiredStreamEpoch) {
        if (retiredStreamEpoch < NO_AUTHORITATIVE_STREAM_EPOCH) {
            throw new IllegalArgumentException(
                    "retiredStreamEpoch must be non-negative");
        }
        Snapshot current = this.snapshot;
        long watermark = Math.max(
                current.authoritativeStreamEpoch(), retiredStreamEpoch);
        this.snapshot = new Snapshot(
                current.state(),
                BodyRelativeViewState.uninitialized(),
                null,
                BodyAttitudeDecision.suspended(
                        BodyAttitudeSuspensionReason.LIFECYCLE_INVALIDATED),
                BodyAttitudeContinuity.INVALID,
                BodyAttitudeOwnership.INACTIVE,
                NO_LOCAL_SIMULATION_STEP,
                current.bootstrapCount(),
                Math.incrementExact(current.lifecycleEpoch()),
                watermark == current.authoritativeStreamEpoch()
                        ? current.authoritativeServerGameTick()
                        : NO_AUTHORITATIVE_SERVER_GAME_TICK,
                watermark == current.authoritativeStreamEpoch()
                        ? current.authoritativeRevision()
                        : NO_AUTHORITATIVE_REVISION,
                watermark,
                watermark == current.authoritativeStreamEpoch()
                        ? current.authoritativeConfigGeneration()
                        : NO_AUTHORITATIVE_CONFIG_GENERATION,
                false
        );
    }

    /**
     * Single component publication seam used by the unified transaction
     * coordinator. The service never calls this method; it only prepares
     * {@link BodyAttitudeLogicalCandidate}.
     */
    synchronized void commitLogicalCandidate(
            BodyAttitudeLogicalCandidate candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Snapshot current = this.snapshot;
        long bootstrapCount = candidate.bootstrapped()
                ? Math.incrementExact(current.bootstrapCount())
                : current.bootstrapCount();
        this.snapshot = new Snapshot(
                candidate.state(),
                candidate.view(),
                candidate.lastStepResult(),
                candidate.decision(),
                candidate.continuity(),
                candidate.ownership(),
                candidate.lastLocalSimulationStep(),
                bootstrapCount,
                current.lifecycleEpoch(),
                current.authoritativeServerGameTick(),
                current.authoritativeRevision(),
                current.authoritativeStreamEpoch(),
                current.authoritativeConfigGeneration(),
                current.authoritativeStreamOpen()
        );
    }

    /** Semantic input has its own commit boundary. An unavailable local body step
     * cannot discard look; no body, revision or geometry is installed. */
    synchronized void commitViewOnly(BodyRelativeViewState view, long localStep) {
        Snapshot c = snapshot;
        snapshot = new Snapshot(c.state(), view, c.lastStepResult(), c.decision(),
                c.continuity(), c.ownership(), localStep, c.bootstrapCount(), c.lifecycleEpoch(),
                c.authoritativeServerGameTick(), c.authoritativeRevision(), c.authoritativeStreamEpoch(),
                c.authoritativeConfigGeneration(), c.authoritativeStreamOpen());
    }

    /** Starts a server-owned monotonic stream without changing local tick idempotence. */
    public synchronized void beginAuthoritativeStream(long streamEpoch) {
        if (streamEpoch <= NO_AUTHORITATIVE_STREAM_EPOCH) {
            throw new IllegalArgumentException("streamEpoch must be positive");
        }
        Snapshot current = this.snapshot;
        if (current.authoritativeStreamEpoch() > streamEpoch) {
            throw new IllegalStateException("authoritative stream epoch regressed");
        }
        this.snapshot = new Snapshot(
                current.state(), current.view(),
                current.lastStepResult(),
                current.decision(),
                current.continuity(), current.ownership(),
                current.lastLocalSimulationStep(),
                current.bootstrapCount(), current.lifecycleEpoch(),
                NO_AUTHORITATIVE_SERVER_GAME_TICK,
                NO_AUTHORITATIVE_REVISION, streamEpoch,
                NO_AUTHORITATIVE_CONFIG_GENERATION, true
        );
    }

    /** Publishes the current accepted state at a lifecycle/configuration boundary.
     * Current body-state receipt does not execute a server simulation step. */
    synchronized void publishCurrentAuthoritativeState(
            long serverGameTick,
            long configGeneration
    ) {
        if (serverGameTick < 0L || configGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "serverGameTick must be non-negative and configGeneration positive");
        }
        Snapshot current = this.snapshot;
        if (!current.authoritativeStreamOpen()
                || current.authoritativeStreamEpoch() == NO_AUTHORITATIVE_STREAM_EPOCH) {
            throw new IllegalStateException("authoritative stream is not established");
        }
        if (serverGameTick < current.authoritativeServerGameTick()
                || configGeneration < current.authoritativeConfigGeneration()) {
            throw new IllegalStateException("authoritative publication metadata is stale");
        }
        this.snapshot = new Snapshot(
                current.state(), current.view(),
                current.lastStepResult(),
                current.decision(),
                current.continuity(), current.ownership(),
                current.lastLocalSimulationStep(),
                current.bootstrapCount(), current.lifecycleEpoch(), serverGameTick,
                current.state().revision(), current.authoritativeStreamEpoch(),
                configGeneration, true
        );
    }

    /**
     * Side-effect-free stream/revision classification of one replicated
     * update against the current snapshot.
     */
    public static ReplicatedInstallResult classifyReplicated(
            Snapshot current,
            ReplicatedAttitudeState update
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(update, "update");

        BodyAttitudeDecision decision = update.decision();
        BodyAttitudeOwnership authoritativeOwnership =
                update.ownership();
        if ((decision.active()
                        && authoritativeOwnership == BodyAttitudeOwnership.INACTIVE)
                || (!decision.active()
                        && authoritativeOwnership != BodyAttitudeOwnership.INACTIVE)) {
            throw new IllegalArgumentException(
                    "ownership must match active/suspended decision");
        }

        if (update.streamEpoch() < current.authoritativeStreamEpoch()) {
            return ReplicatedInstallResult.STALE_STREAM;
        }
        if (update.streamEpoch() == current.authoritativeStreamEpoch()) {
            if (!current.authoritativeStreamOpen()) {
                return ReplicatedInstallResult.STALE_STREAM;
            }
            if (update.authoritativeRevision()
                    < current.authoritativeRevision()) {
                return ReplicatedInstallResult.STALE_REVISION;
            }
            if (update.authoritativeRevision()
                    == current.authoritativeRevision()) {
                return equivalentAuthoritativeState(
                        current,
                        update
                )
                        ? ReplicatedInstallResult.DUPLICATE_REVISION
                        : ReplicatedInstallResult.CONFLICT;
            }
        }
        return update.streamEpoch() == current.authoritativeStreamEpoch()
                ? ReplicatedInstallResult.ACCEPTED_REVISION
                : ReplicatedInstallResult.ACCEPTED_NEW_STREAM;
    }

    /** Lifecycle/order gate and one immutable publication; no proposal or physics verification. */
    public synchronized ReplicatedInstallResult installReplicated(ReplicatedAttitudeState update) {
        Snapshot current = snapshot;
        ReplicatedInstallResult result = classifyReplicated(current, update);
        if (!result.accepted()) return result;
        snapshot = new Snapshot(update.state(), update.view(), null, update.decision(),
                update.continuity(), update.ownership(), current.lastLocalSimulationStep(),
                current.bootstrapCount(), current.lifecycleEpoch(), update.authoritativeServerGameTick(),
                update.authoritativeRevision(), update.streamEpoch(), update.configGeneration(), true);
        return result;
    }

    /** Same-stream self snapshots publish ordering/configuration only. Local simulation owns Q and view. */
    public synchronized boolean observeReplication(long stream, long revision, long serverTick, long generation) {
        Snapshot c = snapshot;
        if (!c.authoritativeStreamOpen() || stream != c.authoritativeStreamEpoch()
                || revision <= c.authoritativeRevision() || generation < c.authoritativeConfigGeneration()
                || serverTick < 0 || generation <= 0) return false;
        snapshot = new Snapshot(c.state(), c.view(), c.lastStepResult(), c.decision(), c.continuity(),
                c.ownership(), c.lastLocalSimulationStep(), c.bootstrapCount(), c.lifecycleEpoch(),
                serverTick, revision, stream, generation, true);
        return true;
    }

    public record Snapshot(
            BodyAttitudeState state,

            BodyRelativeViewState view,
            BodyAttitudeStepResult lastStepResult,
            BodyAttitudeDecision decision,
            BodyAttitudeContinuity continuity,
            BodyAttitudeOwnership ownership,
            long lastLocalSimulationStep,
            long bootstrapCount,
            long lifecycleEpoch,
            long authoritativeServerGameTick,
            long authoritativeRevision,
            long authoritativeStreamEpoch,
            long authoritativeConfigGeneration,
            boolean authoritativeStreamOpen
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");

            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(continuity, "continuity");
            Objects.requireNonNull(ownership, "ownership");
            if (bootstrapCount < 0L) {
                throw new IllegalArgumentException(
                        "bootstrapCount must be non-negative");
            }
            if (lifecycleEpoch < 0L) {
                throw new IllegalArgumentException("lifecycleEpoch must be non-negative");
            }
            if (authoritativeServerGameTick < NO_AUTHORITATIVE_SERVER_GAME_TICK
                    || authoritativeRevision < NO_AUTHORITATIVE_REVISION
                    || authoritativeStreamEpoch < NO_AUTHORITATIVE_STREAM_EPOCH
                    || authoritativeConfigGeneration
                    < NO_AUTHORITATIVE_CONFIG_GENERATION) {
                throw new IllegalArgumentException("invalid authoritative metadata");
            }
            if (authoritativeStreamOpen
                    && authoritativeStreamEpoch == NO_AUTHORITATIVE_STREAM_EPOCH) {
                throw new IllegalArgumentException(
                        "open authoritative stream requires an epoch");
            }
        }

    }


    public record RenderableSnapshot(
            BodyAttitudeState state,

            BodyRelativeViewState view,
            BodyAttitudeDecision decision,
            long lastLocalSimulationStep,
            long lifecycleEpoch,
            long authoritativeServerGameTick,
            long authoritativeRevision,
            long authoritativeStreamEpoch,
            long authoritativeConfigGeneration
    ) {
        public RenderableSnapshot {
            Objects.requireNonNull(state, "state");

            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(decision, "decision");
        }

    }

    public enum ReplicatedInstallResult {
        ACCEPTED_NEW_STREAM,
        ACCEPTED_REVISION,
        STALE_STREAM,
        STALE_REVISION,
        DUPLICATE_REVISION,
        CONFLICT;

        public boolean accepted() {
            return this == ACCEPTED_NEW_STREAM
                    || this == ACCEPTED_REVISION;
        }
    }

    /**
     * Same-revision semantic equivalence: q and -q encode the same rotation,
     * and identical content at the same revision is a duplicate. Different
     * content at the same revision is a protocol conflict.
     */
    private static boolean equivalentAuthoritativeState(
            Snapshot current,
            ReplicatedAttitudeState update
    ) {
        BodyAttitudeState state = update.state();
        return equivalentRotation(
                current.state().currentWorldFromBody(),
                state.currentWorldFromBody()
        )
                && equivalentRotation(
                current.state().previousWorldFromBody(),
                state.previousWorldFromBody()
        )
                && equivalentVec(
                current.state().angularVelocityWorld(),
                state.angularVelocityWorld()
        )
                && current.view().equals(update.view())
                && current.decision().equals(update.decision())
                && current.continuity() == update.continuity()
                && current.ownership() == update.ownership()
                && current.authoritativeConfigGeneration()
                == update.configGeneration();
    }

    private static boolean equivalentRotation(
            org.joml.Quaterniondc a,
            org.joml.Quaterniondc b
    ) {
        return Math.abs(a.dot(b))
                >= 1.0D - 1.0E-10D;
    }

    private static boolean equivalentVec(
            net.minecraft.world.phys.Vec3 a,
            net.minecraft.world.phys.Vec3 b
    ) {
        return a.distanceToSqr(b)
                <= 1.0E-20D;
    }
}
