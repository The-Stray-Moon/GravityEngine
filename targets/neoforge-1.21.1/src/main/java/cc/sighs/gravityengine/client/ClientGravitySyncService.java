package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.network.SyncGravityStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

public final class ClientGravitySyncService {
    private ClientGravitySyncService() {}
    public record SnapshotResult(boolean assignmentAccepted, boolean influenceAccepted,
                                 cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus status, boolean geometryChanged) {}

    public static void handle(SyncGravityStatePayload pkt) {
        if (pkt == null) return;
        ClientLevel l = Minecraft.getInstance().level;
        if (l == null || !Objects.equals(pkt.dimensionId(), l.dimension().location())) {
            PendingSnapshotStore.offer(pkt);
            return;
        }
        Entity e = l.getEntity(pkt.entityId());
        if (e == null) {
            PendingSnapshotStore.offer(pkt);
            return;
        }
        // Verify UUID matches before applying
        if (!e.getUUID().equals(pkt.entityUuid())) {
            // Stale entity ID resolved to a different entity - discard
            return;
        }
        applySnapshot(e, pkt);
    }

    public static SnapshotResult applySnapshot(Entity e, SyncGravityStatePayload pkt) {
        var r = cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyRemoteSnapshot(
                e,
                pkt.toState(),
                pkt.authorityMode(),
                pkt.fieldPresent(),
                pkt.assignmentRevision(),
                pkt.suppressionReason(),
                pkt.influenceRevision());
        var app = r.application();
        return new SnapshotResult(r.assignmentAccepted(), r.influenceAccepted(), app.status(), app.geometryChanged());
    }

    /**
     * Current-format pending application. Rebuilds a complete
     * {@link SyncGravityStatePayload} from the stored assignment and
     * suppression snapshots and routes it through the same function as the
     * immediate packet path. Authority comes from the decoded assignment.
     */
    static SnapshotResult applyPending(Entity e, PendingSnapshotStore.PendingSnap snap) {
        if (e == null) {
            return new SnapshotResult(
                    false,
                    false,
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus.UNCHANGED,
                    false
            );
        }
        PendingSnapshotStore.AssignmentSnapshot assignment = snap.assignment();
        PendingSnapshotStore.SuppressionSnapshot suppression = snap.suppression();
        ResourceLocation dimensionId = e.level().dimension().location();
        SyncGravityStatePayload payload = new SyncGravityStatePayload(
                e.getId(),
                e.getUUID(),
                dimensionId,
                assignment.revision(),
                assignment.state().down(),
                assignment.state().strength(),
                suppression.revision(),
                suppression.reason(),
                assignment.authority(),
                assignment.fieldPresent()
        );
        return applySnapshot(e, payload);
    }
    public static int pendingCount() { return PendingSnapshotStore.pendingCount(); }
}