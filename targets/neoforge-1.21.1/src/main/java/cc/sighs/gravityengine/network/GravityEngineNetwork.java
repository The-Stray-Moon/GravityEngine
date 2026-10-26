package cc.sighs.gravityengine.network;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Common payload type/codec registration and logical-side dispatch setup.
 *
 * <p>This class is loaded on dedicated servers and therefore must never
 * reference {@code cc.sighs.gravityengine.client.*} or Minecraft client classes.
 * Clientbound payload implementations are reached only through
 * {@link ClientboundPayloadDispatch}.</p>
 */
public final class GravityEngineNetwork {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GravityEngineNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        /*
         * PayloadRegistrar is intentionally MAIN-thread in NeoForge 21.1.249.
         * Do not change to NETWORK without adding explicit owner-thread
         * handoff, and do not wrap handlers in enqueueWork: that would queue a
         * second, redundant dispatch from the MAIN thread.
         */
        /*
         * 20: body-attitude wire layout changed. The config payload now
         * publishes torque/inertia/damping instead of acceleration and rate
         * caps, and the state payloads carry the durable world-space angular
         * momentum plus its isotropic effective inertia (or an explicit
         * pose-only handoff) alongside the owned quaternions.
         *
         * 21: the body commit payload separates an authorized commit mode
         * (correction / observer / metadata-only native application /
         * representation reconciliation) from the installed body
         * representation, so a synchronization request no longer decodes as a
         * physical relocation.
         */
        /*
         * 23: removes the synthetic entity-lifetime binding payload. NeoForge
         * already orders entity spawn/pairing before StartTracking and player
         * replacement before the login/respawn hooks, so fresh synchronization
         * from those native boundaries is sufficient and the client refuses
         * snapshots whose entity identity is absent or mismatched.
         */
        // 25: complete body transactions replace metadata/representation modes and resync requests.
        // 26: server-owned relocation distinguishes geometry adjustment from momentum correction.
        // 27: installed collision axis is independent of the complete reference frame.
        // 28: native player moves echo the body epoch captured before prediction.
        PayloadRegistrar registrar = event.registrar("29");
        registrar.playToServer(ServerboundPlayerMovePayload.TYPE, ServerboundPlayerMovePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) ServerPlayerMovementReceiver.receive(player, payload);
                });

        /*
         * Payload type/codec registration is common. Only the implementation
         * reached after logical-client delivery is physical-client owned.
         */
        registrar.playToClient(
                ClientboundPlayerBodyCommitPayload.TYPE,
                ClientboundPlayerBodyCommitPayload.STREAM_CODEC,
                (payload, context) -> ClientboundPayloadDispatch.handlePlayerBodyCommit(payload)
        );

        registrar.playToClient(
                SyncGravityStatePayload.TYPE,
                SyncGravityStatePayload.STREAM_CODEC,
                (payload, context) ->
                        ClientboundPayloadDispatch.handleGravitySync(payload)
        );

        registrar.playToServer(
                ServerboundBodyAttitudeStatePayload.TYPE,
                ServerboundBodyAttitudeStatePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BodyAttitudeStateReceiver.receive(player, payload);
                    }
                }
        );

        registrar.playToClient(
                ClientboundBodyAttitudeStatePayload.TYPE,
                ClientboundBodyAttitudeStatePayload.STREAM_CODEC,
                (payload, context) ->
                        ClientboundPayloadDispatch.handleBodyAttitudeState(
                                payload
                        )
        );

        /*
         * cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config is common-safe. installClient mutates
         * the client configuration mirror only because this callback is registered
         * strictly as playToClient; no physical-client class linkage is needed
         * here.
         */
        registrar.playToClient(
                ClientboundBodyAttitudeConfigPayload.TYPE,
                ClientboundBodyAttitudeConfigPayload.STREAM_CODEC,
                (payload, context) ->
                        cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.installClient(
                                payload.generation(),
                                payload.simulation()
                        )
        );

        LOGGER.info("Registered gravity and body-attitude state payloads with protocol 29 (strict ballistic snapshot fields)");
    }

}
