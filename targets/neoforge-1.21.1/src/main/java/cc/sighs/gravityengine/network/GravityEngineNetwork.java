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
        PayloadRegistrar registrar = event.registrar("18");

        /*
         * Payload type/codec registration is common. Only the implementation
         * reached after logical-client delivery is physical-client owned.
         */
        registrar.playToServer(
                ServerboundPlayerBodyResyncPayload.TYPE,
                ServerboundPlayerBodyResyncPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff
                                .requestResync(player);
                    }
                }
        );

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

        LOGGER.info("Registered gravity and body-attitude state payloads with protocol 18; body commits envelope Vanilla teleports, ordinary player movement remains Vanilla");
    }

}
