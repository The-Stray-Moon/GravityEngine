package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.network.SyncGravityStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/**
 * Client-side gravity snapshot admission.
 *
 * <p>Ordinary gravity synchronization is ordered by the native server
 * lifecycle: NeoForge sends the entity pairing bundle before
 * {@code StartTracking}, and player replacement happens before the
 * login/respawn hooks. A snapshot whose target entity identity is absent or
 * mismatched is therefore stale transport data, not an entity-lifetime
 * binding request: it is dropped and the next native lifecycle boundary
 * supplies fresh state.</p>
 */
public final class ClientGravitySyncService {
    private ClientGravitySyncService() {}

    public record SnapshotResult(
            boolean assignmentAccepted,
            boolean influenceAccepted
    ) {}

    public static void handle(SyncGravityStatePayload pkt) {
        if (pkt == null) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null
                || !Objects.equals(
                pkt.dimensionId(),
                level.dimension().location())) {
            return;
        }
        Entity entity = level.getEntity(pkt.entityId());
        if (entity == null || !entity.getUUID().equals(pkt.entityUuid())) {
            /*
             * The intended entity does not exist (or the numeric id now belongs
             * to another identity). Native pairing guarantees a later fresh
             * synchronization, so the snapshot is dropped rather than carried
             * across an entity object lifetime.
             */
            return;
        }
        // Protocol 25 transfers player assignment only inside a complete body transaction.
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            throw new IllegalArgumentException("standalone player gravity snapshot");
        }
        applySnapshot(entity, pkt);
    }

    public static SnapshotResult applySnapshot(
            Entity entity,
            SyncGravityStatePayload pkt
    ) {
        if (entity.isRemoved()
                || entity.getId() != pkt.entityId()
                || !entity.getUUID().equals(pkt.entityUuid())
                || !entity.level().dimension().location().equals(pkt.dimensionId())) {
            return new SnapshotResult(false, false);
        }
        var result =
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                        .applyRemoteSnapshot(
                                entity,
                                pkt.toState(),
                                pkt.authorityMode(),
                                pkt.fieldPresence(),
                                pkt.assignmentSyncRevision(),
                                pkt.suppressionReason(),
                                pkt.influenceRevision());
        if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                    .applyRemoteApplication(entity, pkt.application(), pkt.applicationEpoch());
        }
        return new SnapshotResult(
                result.assignmentAccepted(),
                result.influenceAccepted());
    }
}
