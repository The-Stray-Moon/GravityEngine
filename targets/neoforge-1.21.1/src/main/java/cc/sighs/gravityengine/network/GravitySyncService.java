package cc.sighs.gravityengine.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Server-side gravity synchronization.
 *
 * <p>Packets carry assignment and influence revisions for ordered
 * client-side application. Fresh state is emitted from native server lifecycle
 * boundaries: entity pairing/StartTracking, player login, respawn and dimension
 * change. There is no synthetic entity-lifetime packet, because NeoForge
 * already orders entity spawn/pairing before {@code StartTracking} and player
 * replacement before the login/respawn hooks.</p>
 */
public final class GravitySyncService {
    private GravitySyncService() {}

    public static void syncPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (pending(player)) return;
        PacketDistributor.sendToPlayer(
                player,
                ClientboundPlayerBodyCommitPayload.capture(player, null)
                        .withMode(ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION)
        );
    }

    public static void syncEntityToPlayer(ServerPlayer player, Entity target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        if (pending(target)) return;
        if (target instanceof ServerPlayer tracked) {
            var snapshot = ClientboundPlayerBodyCommitPayload.capture(tracked, null);
            PacketDistributor.sendToPlayer(player, tracked == player
                    ? snapshot.withMode(ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION) : snapshot);
        } else {
            PacketDistributor.sendToPlayer(player, SyncGravityStatePayload.from(target));
        }
    }

    public static void syncTracking(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (pending(entity)) return;
        if (entity instanceof ServerPlayer player) {
            // Assignment/evidence changes join the final body publication. Do
            // not send an intermediate tuple before the connection handoff.
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.markApplicationChanged(player);
        } else {
            PacketDistributor.sendToPlayersTrackingEntity(entity, SyncGravityStatePayload.from(entity));
        }
    }

    private static boolean pending(Entity entity) {
        return entity.isRemoved();
    }
}
