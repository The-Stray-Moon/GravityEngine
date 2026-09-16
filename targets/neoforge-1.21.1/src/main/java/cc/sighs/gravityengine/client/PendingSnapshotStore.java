package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.network.SyncGravityStatePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class PendingSnapshotStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_AGE = 600L;
    private static final ConcurrentHashMap<PendingKey, PendingSnap> STORE = new ConcurrentHashMap<>();
    static final AtomicLong CLIENT_TICK = new AtomicLong(0L);

    record PendingKey(ResourceLocation dim, UUID entityUuid) { PendingKey { Objects.requireNonNull(dim, "dim"); Objects.requireNonNull(entityUuid, "uuid"); } }

    /**
     * Immutable remote assignment tuple. {@code state}, {@code authority},
     * {@code fieldPresent} and {@code revision} belong to one indivisible
     * snapshot and are never mixed across revisions.
     */
    record AssignmentSnapshot(
            GravityState state,
            GravityAuthorityMode authority,
            boolean fieldPresent,
            long revision
    ) {
        AssignmentSnapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(authority, "authority");
            if (authority != GravityAuthorityMode.FIELD && fieldPresent) {
                throw new IllegalArgumentException(
                        "fieldPresent is valid only under FIELD authority");
            }

            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "assignment revision must be non-negative: "
                                + revision
                );
            }
        }

        boolean sameContent(AssignmentSnapshot other) {
            Objects.requireNonNull(other, "other");

            return revision == other.revision
                    && authority == other.authority
                    && fieldPresent == other.fieldPresent
                    && state.sameSyncData(other.state);
        }
    }

    /** Immutable remote suppression snapshot, revisioned independently. */
    record SuppressionSnapshot(
            GravitySuppressionReason reason,
            long revision
    ) {
        SuppressionSnapshot {
            Objects.requireNonNull(reason, "reason");

            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "suppression revision must be non-negative: "
                                + revision
                );
            }
        }

        boolean sameContent(SuppressionSnapshot other) {
            Objects.requireNonNull(other, "other");

            return revision == other.revision
                    && reason == other.reason;
        }
    }

    /**
     * Merged remote snapshot for one entity key. Assignment and suppression
     * merge in their own revision domains; the entity id follows the
     * assignment tuple only.
     */
    record PendingSnap(
            int entityId,
            AssignmentSnapshot assignment,
            SuppressionSnapshot suppression,
            long arrivalTick
    ) {
        PendingSnap {
            Objects.requireNonNull(assignment, "assignment");
            Objects.requireNonNull(suppression, "suppression");
        }
    }

    /** Merge result with explicit conflict diagnostics for equal-revision peers. */
    record MergeOutcome(PendingSnap merged, boolean assignmentConflict, boolean suppressionConflict) {}

    static void offer(SyncGravityStatePayload pkt) {
        if (pkt == null) return;
        PendingKey key = new PendingKey(pkt.dimensionId(), pkt.entityUuid());
        PendingSnap incoming = new PendingSnap(
                pkt.entityId(),
                new AssignmentSnapshot(
                        pkt.toState(),
                        pkt.authorityMode(),
                        pkt.fieldPresent(),
                        pkt.assignmentRevision()
                ),
                new SuppressionSnapshot(
                        pkt.suppressionReason(), pkt.influenceRevision()
                ),
                CLIENT_TICK.get()
        );
        STORE.merge(key, incoming, PendingSnapshotStore::merge);
    }

    private static PendingSnap merge(PendingSnap ex, PendingSnap inc) {
        return mergeDetailed(ex, inc).merged();
    }

    static MergeOutcome mergeDetailed(PendingSnap existing, PendingSnap incoming) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(incoming, "incoming");

        AssignmentSnapshot existingAssignment = existing.assignment();
        AssignmentSnapshot incomingAssignment = incoming.assignment();
        AssignmentSnapshot mergedAssignment = existingAssignment;
        boolean assignmentConflict = false;
        if (incomingAssignment.revision() > existingAssignment.revision()) {
            mergedAssignment = incomingAssignment;
        } else if (incomingAssignment.revision() < existingAssignment.revision()) {
            // Keep the whole existing assignment tuple.
        } else if (!incomingAssignment.sameContent(existingAssignment)) {
            // Same revision with different state/authority/field-presence
            // is a protocol conflict. Keep the first-arrived tuple
            // deterministically and never combine a mixed
            // snapshot.
            assignmentConflict = true;
            LOGGER.warn(
                    "Pending assignment snapshot conflict: revision={} "
                            + "existingDown={} incomingDown={} "
                            + "existingAuthority={} incomingAuthority={} "
                            + "existingStrength={} incomingStrength={} "
                            + "existingFieldPresent={} incomingFieldPresent={}",
                    existingAssignment.revision(),
                    existingAssignment.state().down(),
                    incomingAssignment.state().down(),
                    existingAssignment.authority(),
                    incomingAssignment.authority(),
                    existingAssignment.state().strength(),
                    incomingAssignment.state().strength(),
                    existingAssignment.fieldPresent(),
                    incomingAssignment.fieldPresent()
            );
        }

        SuppressionSnapshot existingSuppression = existing.suppression();
        SuppressionSnapshot incomingSuppression = incoming.suppression();
        SuppressionSnapshot mergedSuppression = existingSuppression;
        boolean suppressionConflict = false;
        if (incomingSuppression.revision() > existingSuppression.revision()) {
            mergedSuppression = incomingSuppression;
        } else if (incomingSuppression.revision() < existingSuppression.revision()) {
            // Keep the existing suppression snapshot.
        } else if (!incomingSuppression.sameContent(existingSuppression)) {
            suppressionConflict = true;
            LOGGER.warn(
                    "Pending suppression snapshot conflict: revision={} "
                            + "existing={} incoming={}",
                    existingSuppression.revision(),
                    existingSuppression.reason(),
                    incomingSuppression.reason()
            );
        }

        boolean assignmentAdvanced =
                incomingAssignment.revision() > existingAssignment.revision();
        boolean suppressionAdvanced =
                incomingSuppression.revision() > existingSuppression.revision();
        // Entity id provenance: only a newer assignment tuple may replace it.
        // A newer suppression snapshot alone never changes state/authority/id.
        int entityId = assignmentAdvanced
                ? incoming.entityId()
                : existing.entityId();
        boolean changed = assignmentAdvanced || suppressionAdvanced
                || assignmentConflict || suppressionConflict;
        long arrivalTick = changed
                ? Math.max(existing.arrivalTick(), incoming.arrivalTick())
                : existing.arrivalTick();
        return new MergeOutcome(
                new PendingSnap(
                        entityId, mergedAssignment, mergedSuppression, arrivalTick
                ),
                assignmentConflict,
                suppressionConflict
        );
    }

    static void retryOnTick() {
        long now = CLIENT_TICK.incrementAndGet();
        if (STORE.isEmpty()) return;
        ClientLevel l = Minecraft.getInstance().level; if (l == null) return;
        ResourceLocation cd = l.dimension().location();
        var it = STORE.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next(); var k = en.getKey(); var s = en.getValue();
            if (now - s.arrivalTick() > MAX_AGE) { it.remove(); continue; }
            if (!k.dim.equals(cd)) continue;
            Entity e = l.getEntity(s.entityId);
            if (e == null) continue;
            if (!e.getUUID().equals(k.entityUuid)) { it.remove(); continue; }
            ClientGravitySyncService.applyPending(e, s);
            it.remove();
        }
    }

    static void onEntityJoin(Entity e) {
        if (e == null) return;
        if (!(e.level() instanceof ClientLevel cl)) return;
        PendingSnap s = STORE.remove(new PendingKey(cl.dimension().location(), e.getUUID()));
        if (s != null) ClientGravitySyncService.applyPending(e, s);
    }

    static void onEntityRemoved(Entity e) {
        if (e == null) return;
        if (!(e.level() instanceof ClientLevel cl)) return;
        STORE.remove(new PendingKey(cl.dimension().location(), e.getUUID()));
    }

    static void clearDimension(ResourceLocation dim) { if (dim != null) STORE.keySet().removeIf(k -> dim.equals(k.dim)); }
    static void clearAll() { STORE.clear(); CLIENT_TICK.set(0L); }
    static int pendingCount() { return STORE.size(); }
}
