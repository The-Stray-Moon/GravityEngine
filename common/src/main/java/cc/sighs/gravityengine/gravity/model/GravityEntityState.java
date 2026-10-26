package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.api.FieldPresence;
import cc.sighs.gravityengine.gravity.GravityState;
import java.util.Objects;
import java.util.Optional;

/**
 * Loader-neutral authoritative gravity state of one entity.
 *
 * <p>The live model contains only facts that are genuinely independent:</p>
 *
 * <ul>
 *   <li>the assigned {@link GravityState} and its
 *   {@link GravityAuthorityMode};</li>
 *   <li>one tri-state {@link FieldPresence} for the current assignment
 *   ({@code UNKNOWN}/{@code PRESENT}/{@code ABSENT}); it is not applicable
 *   when the authority is not {@code FIELD}, and that is derived from the
 *   authority rather than stored as another state;</li>
 *   <li>one durable FIELD continuity bit; it distinguishes an ordinary
 *   default/absent FIELD state from a FIELD-derived durable seed whose numeric
 *   gravity may happen to equal vanilla/default gravity;</li>
 *   <li>the durable assignment revision, which orders durable persistence
 *   writes, and the live synchronization revision, which orders the live
 *   assignment stream;</li>
 *   <li>authoritative suppression and its influence revision;</li>
 *   <li>committed application and its application epoch;</li>
 *   <li>the load bootstrap flag and the latest desired pending target;</li>
 *   <li>remote snapshot acceptance initialization.</li>
 * </ul>
 *
 * <p>There is no second reconciliation state machine, no provisional-presence
 * flag and no synthetic entity-incarnation identity: those concepts either
 * restate {@code authority}/{@code fieldPresence}/{@code durableFieldContinuity}
 * or collide with the native Minecraft/NeoForge entity lifecycle.</p>
 *
 * <p>It deliberately owns no {@code Entity}, {@code Level}, {@code CompoundTag},
 * loader handle, logger or transport type. Persistence encoding, entity
 * identity, attachment lifecycle and diagnostics belong to the target adapter
 * that wraps this state.</p>
 */
public final class GravityEntityState {
    /**
     * Largest durable revision. Long.MAX_VALUE is excluded so a restored
     * seed's initial sync revision always has room for evidence resolution.
     * Exhaustion rejects mutations; it never saturates or wraps a counter.
     */
    public static final long MAX_DURABLE_ASSIGNMENT_REVISION = Long.MAX_VALUE - 1;
    /**
     * Durable provenance of a persisted FIELD assignment.
     *
     * <p>This is deliberately independent from the numeric
     * {@link GravityState}. A FIELD contribution may resolve to an
     * acceleration that is numerically equal to {@link GravityState#DEFAULT}
     * (including zero-strength cancellation), so numeric equality can never
     * decide whether a persisted assignment still needs destination-world
     * reconciliation.</p>
     *
     * <ul>
     *   <li>{@link #NONE} - the durable assignment owns no FIELD continuity to
     *   protect. A {@code DEFAULT}/{@code FIELD}/confirmed-absent entity is
     *   the canonical example.</li>
     *   <li>{@link #SEED} - the durable assignment was captured while FIELD
     *   presence was confirmed. Loading it into a cold/incomplete destination
     *   runtime opens reconciliation. It is not a claim that a matching field
     *   is currently present.</li>
     * </ul>
     */
    public enum FieldContinuity { NONE, SEED }

    /** Outcome of one remote snapshot acceptance attempt. */
    public enum SnapshotAcceptance {
        ACCEPTED,
        STALE,
        CONFLICT
    }

    /** Outcome of installing an application owned by the authoritative side. */
    public enum RemoteApplicationAcceptance {
        ACCEPTED,
        STALE,
        CONFLICT
    }

    /**
     * Latest desired application state; the committed application is the
     * comparison authority.
     */
    public record PendingTarget(
            GravityState desiredAssignment,
            GravitySuppressionReason desiredEffectiveSuppression,
            GravityApplicationPlan remotePlan,
            long remoteEpoch
    ) {
        public PendingTarget(GravityState assignment, GravitySuppressionReason suppression) {
            this(assignment, suppression, null, 0L);
        }

        public PendingTarget {
            Objects.requireNonNull(desiredAssignment, "desiredAssignment");
            Objects.requireNonNull(
                    desiredEffectiveSuppression,
                    "desiredEffectiveSuppression");
            requireEpoch(remoteEpoch);
        }
    }

    /**
     * Durable assignment tuple restored from persistence.
     *
     * <p>Only {@code state}, {@code authority}, {@code revision} and
     * {@code fieldContinuity} are durable. FIELD presence is transient
     * evidence and must be re-evaluated from the destination world;
     * {@code fieldContinuity} authorizes continuity installation while that
     * evidence remains unknown. All FIELD assignments require evaluation.</p>
     *
     * <p>Revision is in {@code [0, MAX_DURABLE_ASSIGNMENT_REVISION]}.
     * The same bound applies to live durable mutations, so every committed
     * durable tuple remains serializable and reloadable after exhaustion.</p>
     */
    public record StoredAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            long revision,
            FieldContinuity fieldContinuity
    ) {
        public StoredAssignment {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(fieldContinuity, "fieldContinuity");

            if (!validDurableRevision(revision)) {
                throw new IllegalArgumentException(
                        "stored assignment revision must be in [0, "
                                + MAX_DURABLE_ASSIGNMENT_REVISION + "]: "
                                + revision
                );
            }

            if (fieldContinuity == FieldContinuity.SEED
                    && authority != GravityAuthorityMode.FIELD) {
                throw new IllegalArgumentException(
                        "FIELD continuity is valid only under FIELD authority");
            }
        }
    }

    /**
     * Durable direction vectors below this squared length are treated as
     * malformed persistence rather than as a degenerate gravity assignment.
     */
    private static final double MIN_STORED_DOWN_LENGTH_SQUARED = 1.0E-7D;

    private GravityState assignedState = GravityState.DEFAULT;
    private GravityAuthorityMode assignedAuthority = GravityAuthorityMode.FIELD;
    /** Single live FIELD-presence fact; not applicable is derived from authority. */
    private FieldPresence fieldPresence = FieldPresence.UNKNOWN;
    /** Single durable FIELD provenance bit. */
    private FieldContinuity durableFieldContinuity = FieldContinuity.NONE;
    private long assignmentRevision;
    /** Live assignment stream ordering, independent of the durable revision. */
    private long assignmentSyncRevision;
    private boolean assignmentSnapshotInitialized;

    private GravitySuppressionReason authoritativeSuppression =
            GravitySuppressionReason.NONE;
    private long influenceRevision;
    private boolean influenceSnapshotInitialized;

    private CommittedGravityApplication committedApplication =
            CommittedGravityApplication.vanillaNoAssignment();
    private long applicationEpoch;
    private boolean remoteApplicationObserved;
    private boolean applicationBootstrapPending;
    private PendingTarget pendingApplication;

    // -----------------------------------------------------------------
    // Assigned state
    // -----------------------------------------------------------------

    /**
     * Persisted/bootstrap assignment result.
     *
     * <p>This value is retained for save compatibility, synchronization and
     * startup reconstruction. It is not the canonical current physical FIELD
     * result once a runtime evaluation exists: a velocity- or time-dependent
     * field may produce a different operation-local result.</p>
     */
    public GravityState assignedState() {
        return assignedState;
    }

    public GravityAuthorityMode assignedAuthority() {
        return assignedAuthority;
    }

    public long assignmentRevision() {
        return assignmentRevision;
    }

    /** Live assignment stream ordering. */
    public long assignmentSyncRevision() {
        return assignmentSyncRevision;
    }

    /**
     * Three-state FIELD presence of the current assignment.
     *
     * <p>{@link FieldPresence#UNKNOWN} is a real state, not a synonym for
     * absence: a restored durable FIELD seed or a runtime episode that lost
     * its known producer set must never be encoded as confirmed absence.</p>
     */
    public FieldPresence fieldPresence() {
        if (assignedAuthority != GravityAuthorityMode.FIELD) {
            return FieldPresence.ABSENT;
        }
        return fieldPresence;
    }

    /**
     * True while the current FIELD assignment has no authoritative destination
     * evidence yet. Under a non-FIELD authority this is not applicable and
     * therefore {@code false}.
     */
    public boolean fieldEvidenceUnknown() {
        return assignedAuthority == GravityAuthorityMode.FIELD
                && fieldPresence == FieldPresence.UNKNOWN;
    }

    /** Durable FIELD continuity provenance of the current assignment. */
    public FieldContinuity durableFieldContinuity() {
        return durableFieldContinuity;
    }

    /**
     * Binary view of {@link #fieldPresence()}: {@code true} only for confirmed
     * presence. UNKNOWN never claims confirmed presence.
     */
    public boolean assignedFieldPresent() {
        return fieldPresence() == FieldPresence.PRESENT;
    }

    /**
     * Whether a FIELD reference is currently usable by environmental/reference
     * consumers.
     *
     * <p>PRESENT is current-world proof and therefore establishes a reference.
     * ABSENT explicitly does not.</p>
     *
     * <p>UNKNOWN is deliberately derived from the already committed physical
     * application rather than from another mutable reconciliation flag:
     *
     * <ul>
     *     <li>a disk-restored FIELD seed starts with a Vanilla committed
     *     application and therefore does not masquerade as a live destination
     *     reference;</li>
     *     <li>a runtime/Level-episode transition may put a previously confirmed
     *     FIELD assignment back into UNKNOWN while its old FIELD application is
     *     still committed; that already-committed application may retain reference
     *     continuity until reconciliation completes.</li>
     * </ul>
     *
     * <p>This keeps durable continuity, live evidence and committed application as
     * separate authorities without adding a provisional-presence boolean.</p>
     */
    public boolean fieldReferenceInForce() {
        if (assignedAuthority != GravityAuthorityMode.FIELD) {
            return false;
        }

        return switch (fieldPresence) {
            case PRESENT -> true;
            case ABSENT -> false;
            case UNKNOWN ->
                    committedApplication.plan().usesFieldAcceleration();
        };
    }

    /**
     * True while the current assignment still carries FIELD continuity that a
     * destination runtime transition must protect: either the destination
     * evidence is unresolved, or presence was confirmed (and the durable seed
     * is therefore at risk).
     *
     * <p>A plain {@code DEFAULT}/{@code FIELD}/confirmed-absent entity owns no
     * FIELD continuity. It still loses live absence evidence on a runtime
     * change. The numeric assignment is deliberately not consulted.</p>
     */
    public boolean hasUnresolvedFieldContinuity() {
        return assignedAuthority == GravityAuthorityMode.FIELD
                && (fieldPresence != FieldPresence.ABSENT
                || durableFieldContinuity == FieldContinuity.SEED);
    }

    /**
     * Installs a new assigned state from an authoritative mutation.
     *
     * @return {@code true} when observable runtime data changed. Only durable
     * state/authority changes advance {@link #assignmentRevision()}; evidence
     * changes advance {@link #assignmentSyncRevision()} instead.
     * @throws ArithmeticException if either required revision is exhausted;
     *         no assignment, evidence or revision is changed in that case
     */
    public boolean setAssigned(
            GravityState state,
            GravityAuthorityMode authority,
            boolean fieldPresent
    ) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(authority);
        boolean normalizedFieldPresent =
                normalizeFieldPresent(authority, fieldPresent);
        boolean durableChanged = !assignedState.sameSyncData(state)
                || assignedAuthority != authority
                || durableFieldContinuity != durableContinuity(authority, normalizedFieldPresent);
        FieldPresence evidence =
                fieldEvidence(authority, normalizedFieldPresent);
        if (!durableChanged && fieldPresence == evidence) {
            return false;
        }
        long nextDurableRevision = durableChanged
                ? nextDurableRevision(assignmentRevision) : assignmentRevision;
        long nextSyncRevision = Math.incrementExact(assignmentSyncRevision);
        assignedState = state;
        assignedAuthority = authority;
        fieldPresence = evidence;
        durableFieldContinuity =
                durableContinuity(authority, normalizedFieldPresent);
        assignmentRevision = nextDurableRevision;
        assignmentSyncRevision = nextSyncRevision;
        return true;
    }

    /** Invalidate destination-world FIELD evidence without changing durable truth.
     * Revision exhaustion throws before modifying evidence. */
    public boolean invalidateFieldEvidence() {
        if (assignedAuthority != GravityAuthorityMode.FIELD || fieldPresence == FieldPresence.UNKNOWN) return false;
        long nextSyncRevision = Math.incrementExact(assignmentSyncRevision);
        fieldPresence = FieldPresence.UNKNOWN;
        assignmentSyncRevision = nextSyncRevision;
        return true;
    }

    /** GE reconciliation for one query; partial-positive and empty partial
     * results both preserve the last authoritative assignment. */
    public boolean commitFieldEvaluation(GravityState state, boolean present,
            cc.sighs.gravityengine.api.field.FieldCoverage coverage) {
        Objects.requireNonNull(coverage, "coverage");
        if (assignedAuthority != GravityAuthorityMode.FIELD) return false;
        return coverage == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE
                ? invalidateFieldEvidence() : setAssigned(state, GravityAuthorityMode.FIELD, present);
    }

    // -----------------------------------------------------------------
    // Authoritative / local suppression
    // -----------------------------------------------------------------

    public GravitySuppressionReason authoritativeSuppression() {
        return authoritativeSuppression;
    }

    /**
     * Authoritative/replicated evidence that an environmental gravity
     * reference currently exists, independent of whether its down vector
     * happens to equal the Vanilla world vertical.
     */
    public boolean hasActiveGravityReference() {
        return authoritativeSuppression == GravitySuppressionReason.NONE
                && (assignedAuthority == GravityAuthorityMode.DIRECT
                || assignedAuthority == GravityAuthorityMode.FIELD
                && fieldReferenceInForce());
    }

    public long influenceRevision() {
        return influenceRevision;
    }

    public boolean setAuthoritativeSuppression(
            GravitySuppressionReason reason
    ) {
        Objects.requireNonNull(reason);
        if (authoritativeSuppression == reason) {
            return false;
        }
        authoritativeSuppression = reason;
        influenceRevision++;
        return true;
    }

    public boolean acceptAuthoritativeSuppression(
            GravitySuppressionReason reason,
            long revision
    ) {
        Objects.requireNonNull(reason);
        if (revision <= influenceRevision) {
            return false;
        }
        authoritativeSuppression = reason;
        influenceRevision = revision;
        return true;
    }

    // -----------------------------------------------------------------
    // Committed application
    // -----------------------------------------------------------------

    public GravityState appliedState() {
        return committedApplication.appliedState();
    }

    public CommittedGravityApplication committedApplication() {
        return committedApplication;
    }

    public GravityApplicationPlan appliedPlan() {
        return committedApplication.plan();
    }

    public long applicationEpoch() {
        return applicationEpoch;
    }

    /**
     * True while the entity's load bootstrap is incomplete.
     *
     * <p>For ordinary entities this covers application reconstruction. A
     * host-level player bootstrap may keep the flag set until gravity
     * application, body-attitude restore and fresh stream establishment have
     * all succeeded. Never serialized.</p>
     */
    public boolean applicationBootstrapPending() {
        return this.applicationBootstrapPending;
    }

    public void clearApplicationBootstrapPending() {
        this.applicationBootstrapPending = false;
    }

    public void markApplicationBootstrapPending() {
        this.applicationBootstrapPending = true;
    }

    /**
     * Advances the server-authoritative application revision when the committed
     * application changed.
     */
    public boolean commitAuthoritativeApplication(
            CommittedGravityApplication application
    ) {
        Objects.requireNonNull(application);
        boolean changed = !committedApplication.equals(application);
        committedApplication = application;
        if (changed) {
            applicationEpoch++;
        }
        return changed;
    }

    /** Advance the existing physical epoch for a geometry/reference-only publication.
     * This is transport ordering for committed state, never durable assignment state. */
    public void advanceBodyPublicationEpoch() {
        applicationEpoch = Math.incrementExact(applicationEpoch);
    }

    /**
     * Installs a deterministic replica application without claiming a server
     * revision. Client-side non-player prediction may use this; player
     * application must be installed through
     * {@link #acceptRemoteApplication}.
     */
    public void installReplicaApplication(
            CommittedGravityApplication application
    ) {
        committedApplication = Objects.requireNonNull(application, "application");
    }

    /**
     * Classifies an authoritative application install without mutating state.
     */
    public RemoteApplicationAcceptance classifyRemoteApplication(
            CommittedGravityApplication application,
            long authoritativeEpoch
    ) {
        Objects.requireNonNull(application, "application");
        requireEpoch(authoritativeEpoch);
        if (!remoteApplicationObserved) {
            /*
             * The first authoritative application observed for this entity
             * object establishes its chronology; there is no client-side
             * revision to compare it against yet.
             */
            return RemoteApplicationAcceptance.ACCEPTED;
        }
        if (authoritativeEpoch < applicationEpoch) {
            return RemoteApplicationAcceptance.STALE;
        }
        if (authoritativeEpoch == applicationEpoch) {
            return committedApplication.equals(application)
                    ? RemoteApplicationAcceptance.STALE
                    : RemoteApplicationAcceptance.CONFLICT;
        }
        return RemoteApplicationAcceptance.ACCEPTED;
    }

    /**
     * Accepts the server-authoritative application and its exact revision.
     * Equal revisions are never silently rewritten: identical data is stale
     * and conflicting data is reported as a conflict.
     */
    public RemoteApplicationAcceptance acceptRemoteApplication(
            CommittedGravityApplication application,
            long authoritativeEpoch
    ) {
        RemoteApplicationAcceptance acceptance =
                classifyRemoteApplication(application, authoritativeEpoch);
        if (acceptance != RemoteApplicationAcceptance.ACCEPTED) {
            return acceptance;
        }
        committedApplication = application;
        applicationEpoch = authoritativeEpoch;
        remoteApplicationObserved = true;
        return RemoteApplicationAcceptance.ACCEPTED;
    }

    // -----------------------------------------------------------------
    // Pending target ownership
    // -----------------------------------------------------------------

    public PendingTarget pendingApplication() {
        return pendingApplication;
    }

    /**
     * Atomically take the current pending target without clearing reentrant
     * additions.
     */
    public PendingTarget takePending() {
        PendingTarget target = pendingApplication;
        pendingApplication = null;
        return target;
    }

    /** Store the latest target. Incoming state always replaces older state. */
    public void enqueueOrReplace(PendingTarget target) {
        pendingApplication = Objects.requireNonNull(target, "target");
    }

    public void enqueueIfAbsent(PendingTarget target) {
        Objects.requireNonNull(target, "target");

        if (pendingApplication == null) {
            pendingApplication = target;
        }
    }

    public boolean hasPending() {
        return pendingApplication != null;
    }

    // -----------------------------------------------------------------
    // Remote snapshot acceptance
    // -----------------------------------------------------------------

    /**
     * Accepts one remote assignment snapshot.
     *
     * <p>Acceptance is monotonic in {@code revision}. An equal revision with
     * identical sync data is stale; an equal revision with different data is
     * a conflict and changes nothing. The caller owns any diagnostics.</p>
     */
    public SnapshotAcceptance acceptRemoteAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            FieldPresence evidence,
            long revision
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(evidence, "evidence");
        if (authority != GravityAuthorityMode.FIELD && evidence != FieldPresence.ABSENT) throw new IllegalArgumentException("FIELD evidence requires FIELD authority");

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "assignment revision must be non-negative: " + revision
            );
        }

        if (!assignmentSnapshotInitialized) {
            assignSnapshot(
                    state,
                    authority,
                    evidence,
                    revision
            );
            assignmentSnapshotInitialized = true;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision > assignmentSyncRevision) {
            assignSnapshot(
                    state,
                    authority,
                    evidence,
                    revision
            );
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision < assignmentSyncRevision) {
            return SnapshotAcceptance.STALE;
        }

        if (assignedState.sameSyncData(state)
                && assignedAuthority == authority
                && fieldPresence == evidence) {
            return SnapshotAcceptance.STALE;
        }

        return SnapshotAcceptance.CONFLICT;
    }

    /**
     * Accepts one remote suppression snapshot with the same monotonic
     * revision and conflict semantics as assignment acceptance.
     */
    public SnapshotAcceptance acceptRemoteSuppression(
            GravitySuppressionReason suppression,
            long revision
    ) {
        Objects.requireNonNull(suppression, "suppression");

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "influence revision must be non-negative: " + revision
            );
        }

        if (!influenceSnapshotInitialized) {
            authoritativeSuppression = suppression;
            influenceRevision = revision;
            influenceSnapshotInitialized = true;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision > influenceRevision) {
            authoritativeSuppression = suppression;
            influenceRevision = revision;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision < influenceRevision) {
            return SnapshotAcceptance.STALE;
        }

        if (authoritativeSuppression == suppression) {
            return SnapshotAcceptance.STALE;
        }

        return SnapshotAcceptance.CONFLICT;
    }

    /** Reopens remote snapshot acceptance for a freshly constructed replica. */
    public void resetSnapshotInitialization() {
        assignmentSnapshotInitialized = false;
        influenceSnapshotInitialized = false;
        remoteApplicationObserved = false;
    }

    // -----------------------------------------------------------------
    // Durability
    // -----------------------------------------------------------------

    /**
     * Decodes one durable assignment tuple from primitive persisted values.
     *
     * <p>This is protocol-neutral validation: non-finite components, a
     * degenerate stored direction, an out-of-range durable revision and an unknown
     * authority id are all rejected as malformed persistence without throwing
     * at the caller.</p>
     */
    public static Optional<StoredAssignment> decodeStoredAssignment(
            double downX,
            double downY,
            double downZ,
            double strength,
            long revision,
            int authorityNetworkId,
            boolean fieldContinuitySeed
    ) {
        Vec3d down = new Vec3d(downX, downY, downZ);

        if (!down.isFinite()) {
            return Optional.empty();
        }

        double downLengthSquared = down.lengthSquared();
        if (!Double.isFinite(downLengthSquared)
                || downLengthSquared < MIN_STORED_DOWN_LENGTH_SQUARED) {
            return Optional.empty();
        }

        if (!Double.isFinite(strength)) {
            return Optional.empty();
        }

        if (!validDurableRevision(revision)) {
            return Optional.empty();
        }

        GravityAuthorityMode authority;
        try {
            authority = GravityAuthorityMode.fromNetworkId(authorityNetworkId);
        } catch (IllegalArgumentException malformedAuthority) {
            return Optional.empty();
        }

        GravityState state;
        try {
            state = new GravityState(down, strength);
        } catch (IllegalArgumentException malformedState) {
            return Optional.empty();
        }

        FieldContinuity fieldContinuity;
        if (!fieldContinuitySeed) {
            fieldContinuity = FieldContinuity.NONE;
        } else if (authority == GravityAuthorityMode.FIELD) {
            fieldContinuity = FieldContinuity.SEED;
        } else {
            /*
             * FIELD continuity provenance is meaningless under DIRECT
             * authority; a payload claiming both is malformed persistence, not
             * a silent downgrade.
             */
            return Optional.empty();
        }

        return Optional.of(
                new StoredAssignment(
                        state,
                        authority,
                        revision,
                        fieldContinuity
                )
        );
    }

    /**
     * Resets every derived value and installs one durable assignment.
     *
     * <p>Committed application, installed geometry evidence, transient revisions and
     * snapshot initialization are all discarded: a loaded entity must
     * re-derive them from its destination world.</p>
     *
     * <p>For compatibility, the new live sync sequence is seeded with the
     * durable revision. This is only a starting value, not restoration of the
     * previous entity's network chronology: subsequent evidence-only changes
     * advance sync independently, and native replica lifecycle/identity owns
     * packet admission. No sync revision is persisted.</p>
     *
     * <p>A FIELD record carrying continuity provenance starts with
     * {@link FieldPresence#UNKNOWN}: the durable seed is continuity evidence,
     * not proof that a matching field currently exists in the destination
     * Level.</p>
     */
    public void restoreDurableAssignment(StoredAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        resetForLoad();
        assignSnapshot(
                assignment.state(),
                assignment.authority(),
                assignment.authority() == GravityAuthorityMode.FIELD ? FieldPresence.UNKNOWN : FieldPresence.ABSENT,
                assignment.revision()
        );
        durableFieldContinuity = assignment.fieldContinuity();
        assignmentRevision = assignment.revision();
        this.applicationBootstrapPending = true;
    }

    /**
     * Discards every derived value, including committed application and
     * snapshot initialization, without touching a durable assignment.
     *
     * <p>Every load path must mark the bootstrap pending, because even an
     * absent or malformed durable tuple still needs current evidence before
     * the first movement tick.</p>
     */
    public void resetForLoad() {
        this.authoritativeSuppression = GravitySuppressionReason.NONE;
        this.influenceRevision = 0L;

        this.committedApplication =
                CommittedGravityApplication.vanillaNoAssignment();
        this.applicationEpoch = 0L;
        this.applicationBootstrapPending = true;
        this.pendingApplication = null;

        this.assignedState = GravityState.DEFAULT;
        this.assignedAuthority = GravityAuthorityMode.FIELD;
        this.fieldPresence = FieldPresence.UNKNOWN;
        this.durableFieldContinuity = FieldContinuity.NONE;
        this.assignmentRevision = 0L;
        this.assignmentSyncRevision = 0L;

        resetSnapshotInitialization();
    }

    private void assignSnapshot(
            GravityState state,
            GravityAuthorityMode authority,
            FieldPresence evidence,
            long revision
    ) {
        assignedState = state;
        assignedAuthority = authority;
        assignmentSyncRevision = revision;
        durableFieldContinuity = durableContinuity(authority, evidence == FieldPresence.PRESENT);
        fieldPresence = evidence;
    }

    private static boolean validDurableRevision(long revision) {
        return revision >= 0 && revision <= MAX_DURABLE_ASSIGNMENT_REVISION;
    }

    private static long nextDurableRevision(long revision) {
        long next = Math.incrementExact(revision);
        if (!validDurableRevision(next)) {
            throw new ArithmeticException("durable assignment revision exhausted: " + revision);
        }
        return next;
    }

    /**
     * Durable FIELD continuity provenance of one assignment tuple.
     *
     * <p>Continuity is installed by confirmed FIELD presence and cleared by an
     * authoritative FIELD absence. The numeric {@code GravityState} never
     * participates: an active contribution whose resultant equals
     * {@link GravityState#DEFAULT} still owns FIELD continuity.</p>
     */
    private static FieldContinuity durableContinuity(
            GravityAuthorityMode authority,
            boolean normalizedFieldPresent
    ) {
        return authority == GravityAuthorityMode.FIELD
                && normalizedFieldPresent
                ? FieldContinuity.SEED
                : FieldContinuity.NONE;
    }

    private static FieldPresence fieldEvidence(
            GravityAuthorityMode authority,
            boolean normalizedFieldPresent
    ) {
        if (authority != GravityAuthorityMode.FIELD) {
            /*
             * FIELD presence is not applicable under a non-FIELD authority.
             * The derived accessor reports ABSENT and callers branch on
             * authority; no NOT_APPLICABLE state is stored.
             */
            return FieldPresence.ABSENT;
        }
        return normalizedFieldPresent
                ? FieldPresence.PRESENT
                : FieldPresence.ABSENT;
    }

    private static boolean normalizeFieldPresent(
            GravityAuthorityMode authority,
            boolean fieldPresent
    ) {
        if (authority != GravityAuthorityMode.FIELD && fieldPresent) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }
        return authority == GravityAuthorityMode.FIELD && fieldPresent;
    }

    private static void requireEpoch(long epoch) {
        if (epoch < 0L) {
            throw new IllegalArgumentException(
                    "application epoch must be non-negative: " + epoch);
        }
    }
}
