package cc.sighs.gravityengine.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Server-side gravity synchronization.
 *
 * <p>Packets carry assignment and influence revisions for ordered
 * client-side application.</p>
 */
public final class GravitySyncService {
    private GravitySyncService() {}

    public static void syncPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PacketDistributor.sendToPlayer(
                player,
                SyncGravityStatePayload.from(player)
        );
    }

    public static void syncEntityToPlayer(ServerPlayer player, Entity target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        PacketDistributor.sendToPlayer(
                player,
                SyncGravityStatePayload.from(target)
        );
        if (target instanceof ServerPlayer tracked && target != player) {
            PacketDistributor.sendToPlayer(player, ClientboundPlayerBodyCommitPayload.capture(tracked, null));
        }
    }

    public static void syncTracking(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                SyncGravityStatePayload.from(entity)
        );
        if (entity instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayersTrackingEntity(player,
                    ClientboundPlayerBodyCommitPayload.capture(player, null));
        }
    }
}
