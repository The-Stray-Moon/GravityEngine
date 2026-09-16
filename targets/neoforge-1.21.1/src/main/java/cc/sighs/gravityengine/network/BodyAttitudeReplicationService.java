package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.gravity.debug.PlayerViewDebugLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;

import java.util.Objects;

/** Server bootstrap/tracking snapshots and captured-generation configuration broadcasts.
 * Body updates are current state; ordinary movement and correction use Vanilla.
 */
public final class BodyAttitudeReplicationService {
    private BodyAttitudeReplicationService() {}
    public static void syncToPlayer(ServerPlayer receiver, Player target) {
        prepareServerSnapshot(target);
        if (PlayerViewDebugLog.ENABLED) PlayerViewDebugLog.event(target, "server-attitude-tracking-send",
                "receiver=%s", receiver.getUUID());
        PacketDistributor.sendToPlayer(
                receiver, ClientboundBodyAttitudeStatePayload.from(target));
        syncConfig(receiver);
    }

    public static void syncSelf(ServerPlayer player) {
        prepareServerSnapshot(player);
        if (PlayerViewDebugLog.ENABLED) PlayerViewDebugLog.event(player, "server-attitude-self-send", "");
        PacketDistributor.sendToPlayer(
                player, ClientboundBodyAttitudeStatePayload.from(player));
        syncConfig(player);
    }

    public static void syncConfig(ServerPlayer receiver) {
        PacketDistributor.sendToPlayer(receiver,
                currentServerConfigPayload());
    }

    /**
     * Latest-config semantics used by login/start-tracking synchronization:
     * the receiver must catch up with whatever generation is current right
     * now.  This is deliberately separate from
     * {@link #broadcastConfig(MinecraftServer, cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.Generation)},
     * which must freeze one captured generation for the whole broadcast.
     */
    static ClientboundBodyAttitudeConfigPayload currentServerConfigPayload() {
        return ClientboundBodyAttitudeConfigPayload.currentServer();
    }

    /**
     * Broadcasts one explicit configuration generation to every connected
     * player. Callers must pass the generation returned by the reload that
     * produced the config.  The payload is captured once from that immutable
     * generation before the loop starts; the loop never re-reads the global
     * "current generation" ({@link cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config#server()} or
     * {@link ClientboundBodyAttitudeConfigPayload#currentServer()}), because
     * a later reload may already have replaced it before this owner-thread
     * task executes.
     */
    public static void broadcastConfig(
            MinecraftServer server,
            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.Generation generation
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(generation, "generation");
        ClientboundBodyAttitudeConfigPayload captured =
                payloadFor(generation);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, captured);
            BodyAttitudeStreamEpochService.ensureServerStream(player);
            BodyAttitudeTransactionCoordinator.publishCurrentAuthoritativeState(
                    player, player.level().getGameTime(), generation.generation());
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    player, ClientboundBodyAttitudeStatePayload.from(player));
        }
    }

    /**
     * Pure payload factory for one captured generation.  This is the only
     * association between a reload's immutable generation and the config
     * payload; keeping it a pure function lets the captured-generation
     * semantics be tested without a live server or player list.
     */
    static ClientboundBodyAttitudeConfigPayload payloadFor(
            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.Generation generation
    ) {
        Objects.requireNonNull(generation, "generation");
        return new ClientboundBodyAttitudeConfigPayload(
                generation.generation(),
                generation.simulation()
        );
    }

    /** Read-only server diagnostic boundary; never allocates stream/input state. */
    public static ServerDebugSnapshot debugSnapshot(Player player) {
        BodyAttitudeComponent owner = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.peek(player);
        if (owner == null) {
            return null;
        }

        BodyAttitudeComponent.Snapshot component = owner.snapshot();
        BodyAttitudeConstraintKind constraint =
                component.decision().active()
                        ? component.decision().profile().constraint()
                        : null;

        return new ServerDebugSnapshot(
                "SERVER",
                constraint,
                component.continuity(),
                component.lifecycleEpoch(),
                component.lastLocalSimulationStep(),
                component.authoritativeServerGameTick(),
                component.state().revision(),
                component.authoritativeRevision(),
                component.authoritativeStreamEpoch(),
                component.authoritativeConfigGeneration(),
                component.state().currentWorldFromBody(),
                component.state().angularVelocityWorld(),
                component.view().localYaw(),
                component.view().localPitch()
        );
    }

    public record ServerDebugSnapshot(
            String authorityRole,
            @javax.annotation.Nullable BodyAttitudeConstraintKind constraint,
            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity continuity,
            long lifecycleEpoch,
            long lastLocalSimulationStep,
            long authoritativeServerGameTick,
            long localRevision,
            long authoritativeRevision,
            long streamEpoch,
            long configGeneration,
            Quaterniond worldFromBody,
            Vec3 angularVelocityWorld,
            float viewLocalYaw,
            float viewLocalPitch
    ) {
        public ServerDebugSnapshot {
            worldFromBody = new Quaterniond(worldFromBody);
        }

        @Override public Quaterniond worldFromBody() {
            return new Quaterniond(worldFromBody);
        }
    }

    private static void prepareServerSnapshot(Player player) {
        BodyAttitudeStreamEpochService.ensureServerStream(player);
        BodyAttitudeComponent component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player);
        if (component.snapshot().authoritativeRevision()
                == BodyAttitudeComponent.NO_AUTHORITATIVE_REVISION) {
            BodyAttitudeTransactionCoordinator
                    .publishCurrentAuthoritativeState(
                            player,
                            Math.max(
                                    0L,
                                    player.level().getGameTime()
                            ),
                            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config
                                    .server()
                                    .generation()
                    );
        }
    }

}
