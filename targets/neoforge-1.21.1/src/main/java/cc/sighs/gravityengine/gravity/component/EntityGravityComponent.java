package cc.sighs.gravityengine.gravity.component;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

public final class EntityGravityComponent {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum SnapshotAcceptance { ACCEPTED, STALE, CONFLICT }

    private final Entity entity;
    private GravityState assignedState = GravityState.DEFAULT;
    private GravityAuthorityMode assignedAuthority = GravityAuthorityMode.FIELD;
    /**
     * Contribution-presence evidence for the current FIELD assignment.
     *
     * <p>This is independent from the numeric resultant GravityState: active
     * fields may cancel to zero or sum exactly to GravityState.DEFAULT. DIRECT
     * authority never carries field presence.</p>
     */
    private boolean assignedFieldPresent;
    private long assignmentRevision;
    private boolean assignmentSnapshotInitialized;
    private GravitySuppressionReason authoritativeSuppression = GravitySuppressionReason.NONE;
    private long influenceRevision;
    private boolean influenceSnapshotInitialized;
    @Nullable private GravitySuppressionReason localClientSuppression;
    private CommittedGravityApplication committedApplication = CommittedGravityApplication.vanillaNoAssignment();
    private long applicationEpoch;
    private boolean applicationBootstrapPending;
    private final GravityRuntimeState runtime;
    @Nullable private PendingTarget pendingApplication;

    static final String NBT_TAG = "GravityEngineGravity", NBT_VERSION = "FormatVersion",
            NBT_DOWN_X = "DownX", NBT_DOWN_Y = "DownY", NBT_DOWN_Z = "DownZ",
            NBT_STRENGTH = "Strength", NBT_ASSIGNMENT_REVISION = "AssignmentRev",
            NBT_AUTHORITY = "Authority";
    static final int CURRENT_FORMAT_VERSION = 4;

    public EntityGravityComponent(Entity entity) { this.entity = Objects.requireNonNull(entity); this.runtime = new GravityRuntimeState(); }
    public Entity entity() { return entity; }
    public GravityRuntimeState runtime() { return runtime; }

    public GravityState assignedState() { return assignedState; }
    public GravityAuthorityMode assignedAuthority() {
        return assignedAuthority;
    }
    public long assignmentRevision() { return assignmentRevision; }
    public boolean assignedFieldPresent() { return assignedFieldPresent; }

    public boolean setAssigned(
            GravityState s,
            GravityAuthorityMode authority,
            boolean fieldPresent
    ) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(authority);
        if (authority != GravityAuthorityMode.FIELD && fieldPresent) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }
        boolean normalizedFieldPresent =
                authority == GravityAuthorityMode.FIELD && fieldPresent;
        if (assignedState.sameSyncData(s)
                && assignedAuthority() == authority
                && assignedFieldPresent == normalizedFieldPresent) {
            return false;
        }
        assignedState = s;
        assignedAuthority = authority;
        assignedFieldPresent = normalizedFieldPresent;
        assignmentRevision++;
        return true;
    }

    public GravitySuppressionReason authoritativeSuppression() { return authoritativeSuppression; }
    public long influenceRevision() { return influenceRevision; }
    public boolean setAuthoritativeSuppression(GravitySuppressionReason r) { Objects.requireNonNull(r); if (authoritativeSuppression == r) return false; authoritativeSuppression = r; influenceRevision++; return true; }
    public boolean acceptAuthoritativeSuppression(GravitySuppressionReason r, long rev) { Objects.requireNonNull(r); if (rev <= influenceRevision) return false; authoritativeSuppression = r; influenceRevision = rev; return true; }

    @Nullable public GravitySuppressionReason localClientSuppression() { return localClientSuppression; }
    public void setLocalClientSuppression(@Nullable GravitySuppressionReason r) { localClientSuppression = r; }
    public GravitySuppressionReason effectiveSuppression() { return localClientSuppression != null ? localClientSuppression : authoritativeSuppression; }

    public GravityState appliedState() { return committedApplication.appliedState(); }
    public CommittedGravityApplication committedApplication() { return committedApplication; }
    public GravityApplicationPlan appliedPlan() { return committedApplication.plan(); }
    public long applicationEpoch() { return applicationEpoch; }

    /**
     * True while the entity's load bootstrap is incomplete.
     *
     * <p>For ordinary non-player entities this covers application
     * reconstruction. For ServerPlayer the high-level
     * {@code PlayerPhysicalLoadBootstrap} keeps the flag set until gravity
     * application, BodyAttitude restore/bootstrap and fresh stream
     * establishment have all succeeded. Never serialized.</p>
     */
    public boolean applicationBootstrapPending() {
        return this.applicationBootstrapPending;
    }

    public void clearApplicationBootstrapPending() {
        this.applicationBootstrapPending = false;
    }

    /**
     * Marks the entity's load bootstrap incomplete.
     *
     * <p>For ordinary non-player entities this covers application
     * reconstruction. For ServerPlayer the high-level PlayerPhysicalLoadBootstrap
     * keeps the flag set until gravity application, BodyAttitude restore/bootstrap
     * and fresh stream establishment have all succeeded.</p>
     */
    public void markApplicationBootstrapPending() {
        this.applicationBootstrapPending = true;
    }


    // -----------------------------------------------------------------
    // P0-2: Snapshot initialization and remote acceptance
    // -----------------------------------------------------------------

    public SnapshotAcceptance acceptRemoteAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            boolean fieldPresent,
            long revision
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(authority, "authority");
        if (authority != GravityAuthorityMode.FIELD && fieldPresent) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }
        boolean normalizedFieldPresent =
                authority == GravityAuthorityMode.FIELD && fieldPresent;

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "assignment revision must be non-negative: " + revision
            );
        }

        if (!assignmentSnapshotInitialized) {
            assignedState = state;
            assignedAuthority = authority;
            assignedFieldPresent = normalizedFieldPresent;
            assignmentRevision = revision;
            assignmentSnapshotInitialized = true;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision > assignmentRevision) {
            assignedState = state;
            assignedAuthority = authority;
            assignedFieldPresent = normalizedFieldPresent;
            assignmentRevision = revision;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision < assignmentRevision) {
            return SnapshotAcceptance.STALE;
        }

        if (assignedState.sameSyncData(state)
                && assignedAuthority == authority
                && assignedFieldPresent == normalizedFieldPresent) {
            return SnapshotAcceptance.STALE;
        }

        LOGGER.warn(
                "Assignment snapshot conflict: revision={} "
                        + "existingDown={} incomingDown={} "
                        + "existingAuthority={} incomingAuthority={} "
                        + "existingFieldPresent={} incomingFieldPresent={}",
                revision,
                assignedState.down(),
                state.down(),
                assignedAuthority,
                authority,
                assignedFieldPresent,
                normalizedFieldPresent
        );

        return SnapshotAcceptance.CONFLICT;
    }

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

        LOGGER.warn(
                "Suppression snapshot conflict: revision={} "
                        + "existing={} incoming={}",
                revision,
                authoritativeSuppression,
                suppression
        );

        return SnapshotAcceptance.CONFLICT;
    }

    public void resetSnapshotInitialization() {
        assignmentSnapshotInitialized = false;
        influenceSnapshotInitialized = false;
    }

    // -----------------------------------------------------------------
    // P0-3: Pending-target ownership
    // -----------------------------------------------------------------

    /** Latest desired application state; the committed application is the comparison authority. */
    public record PendingTarget(
            GravityState desiredAssignment,
            GravitySuppressionReason desiredEffectiveSuppression
    ) {
        public PendingTarget {
            Objects.requireNonNull(desiredAssignment, "desiredAssignment");
            Objects.requireNonNull(
                    desiredEffectiveSuppression,
                    "desiredEffectiveSuppression");
        }
    }

    @Nullable public PendingTarget pendingApplication() { return pendingApplication; }

    /** Atomically take the current pending target without clearing reentrant additions. */
    @Nullable public PendingTarget takePending() {
        PendingTarget t = pendingApplication;
        pendingApplication = null;
        return t;
    }

    /** Store the latest target. Incoming state always replaces older state. */
    public void enqueueOrReplace(PendingTarget target) {
        Objects.requireNonNull(target, "target");
        pendingApplication = target;
    }

    public void enqueueIfAbsent(PendingTarget target) {
        Objects.requireNonNull(target, "target");

        if (pendingApplication == null) {
            pendingApplication = target;
        }
    }

    public boolean hasPending() { return pendingApplication != null; }

    public void commitApplication(CommittedGravityApplication application) {
        Objects.requireNonNull(application);
        boolean changed = !committedApplication.equals(application);
        committedApplication = application;
        if (changed) applicationEpoch++;
    }

    // -----------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------

    private static final double MIN_STORED_DOWN_LENGTH_SQUARED = 1.0E-7D;

    record StoredAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            long revision
    ) {
        StoredAssignment {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(authority, "authority");

            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "stored assignment revision must be non-negative: "
                                + revision
                );
            }
        }
    }

    public void readFromNbt(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        resetForLoad();

        if (!tag.contains(NBT_TAG, CompoundTag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag root = tag.getCompound(NBT_TAG);

        Optional<StoredAssignment> decoded =
                decodeCurrentAssignment(root);

        if (decoded.isEmpty()) {
            return;
        }

        StoredAssignment assignment = decoded.get();

        /*
         * Atomic commit:
         *
         * state + authority + revision are published together only after the
         * entire current-format tuple has been validated.
         */
        this.assignedState = assignment.state();
        this.assignedAuthority = assignment.authority();
        // Field presence is transient evidence and must be re-evaluated from
        // the destination world before application bootstrap.
        this.assignedFieldPresent = false;
        this.assignmentRevision = assignment.revision();

        /*
         * Durable assignment was restored, but committed application and
         * installed geometry were deliberately discarded by resetForLoad().
         * Re-derive them even when a fresh field evaluation has the same
         * assignment value.
         */
        this.applicationBootstrapPending = true;
    }

    /** Player replacement transfers durable assignment, never the old world's derived state. */
    public void copyDurableAssignmentFrom(EntityGravityComponent source) {
        Objects.requireNonNull(source, "source");
        StoredAssignment assignment = new StoredAssignment(
                source.assignedState, source.assignedAuthority, source.assignmentRevision);
        resetForLoad();
        this.assignedState = assignment.state();
        this.assignedAuthority = assignment.authority();
        this.assignmentRevision = assignment.revision();
    }

    private void resetForLoad() {
        this.authoritativeSuppression = GravitySuppressionReason.NONE;
        this.influenceRevision = 0L;
        this.localClientSuppression = null;

        this.committedApplication =
                CommittedGravityApplication.vanillaNoAssignment();
        this.applicationEpoch = 0L;
        // Even absent/malformed NBT needs current FIELD evidence before first-tick movement.
        // The existing base-tick owner performs this once; deserialization never samples.
        this.applicationBootstrapPending = true;
        this.pendingApplication = null;

        this.assignedState = GravityState.DEFAULT;
        this.assignedAuthority = GravityAuthorityMode.FIELD;
        this.assignedFieldPresent = false;
        this.assignmentRevision = 0L;

        this.runtime.clearInfluenceTransientState();
        this.runtime.clearInstalledCollisionAxis();

        resetSnapshotInitialization();
    }

    static Optional<StoredAssignment> decodeCurrentAssignment(
            CompoundTag root
    ) {
        Objects.requireNonNull(root, "root");

        if (!root.contains(NBT_VERSION, CompoundTag.TAG_INT)
                || root.getInt(NBT_VERSION) != CURRENT_FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!root.contains(NBT_DOWN_X, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_DOWN_Y, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_DOWN_Z, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_STRENGTH, CompoundTag.TAG_DOUBLE)
                || !root.contains(
                NBT_ASSIGNMENT_REVISION,
                CompoundTag.TAG_LONG
        )
                || !root.contains(NBT_AUTHORITY, CompoundTag.TAG_INT)) {
            return Optional.empty();
        }

        Vec3 down = new Vec3(
                root.getDouble(NBT_DOWN_X),
                root.getDouble(NBT_DOWN_Y),
                root.getDouble(NBT_DOWN_Z)
        );

        double strength = root.getDouble(NBT_STRENGTH);
        long revision = root.getLong(NBT_ASSIGNMENT_REVISION);
        int authorityId = root.getInt(NBT_AUTHORITY);

        if (!Double.isFinite(down.x)
                || !Double.isFinite(down.y)
                || !Double.isFinite(down.z)) {
            return Optional.empty();
        }

        double downLengthSquared = down.lengthSqr();
        if (!Double.isFinite(downLengthSquared)
                || downLengthSquared
                < MIN_STORED_DOWN_LENGTH_SQUARED) {
            return Optional.empty();
        }

        if (!Double.isFinite(strength)) {
            return Optional.empty();
        }

        if (revision < 0L) {
            return Optional.empty();
        }

        GravityAuthorityMode authority;
        try {
            authority =
                    GravityAuthorityMode.fromNetworkId(authorityId);
        } catch (IllegalArgumentException malformedAuthority) {
            return Optional.empty();
        }

        GravityState state;
        try {
            state = new GravityState(down, strength);
        } catch (IllegalArgumentException malformedState) {
            return Optional.empty();
        }

        return Optional.of(
                new StoredAssignment(
                        state,
                        authority,
                        revision
                )
        );
    }

    public void writeToNbt(CompoundTag tag) { Objects.requireNonNull(tag); if (assignedState.isDefault() && assignedAuthority() == GravityAuthorityMode.FIELD && assignmentRevision == 0L) { tag.remove(NBT_TAG); return; }
        CompoundTag r = new CompoundTag(); r.putInt(NBT_VERSION, CURRENT_FORMAT_VERSION);
        r.putDouble(NBT_DOWN_X, assignedState.down().x); r.putDouble(NBT_DOWN_Y, assignedState.down().y); r.putDouble(NBT_DOWN_Z, assignedState.down().z);
        r.putDouble(NBT_STRENGTH, assignedState.strength()); r.putLong(NBT_ASSIGNMENT_REVISION, assignmentRevision);
        r.putInt(NBT_AUTHORITY, assignedAuthority().networkId()); tag.put(NBT_TAG, r); }
    
}
