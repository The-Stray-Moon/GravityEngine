package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.attitude.AttitudeDynamicHandoff;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeControlPolicyResolver;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeDecision;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime;
import cc.sighs.gravityengine.attitude.runtime.MinecraftBodyAttitudeSnapshotAdapter;
import cc.sighs.gravityengine.attitude.runtime.ReplicatedAttitudeState;
import cc.sighs.gravityengine.gravity.movement.CharacterAttitudeContract;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Main-thread latest-state receiver.
 *
 * <p>The owning client produces actor pose and, for a dynamic body, its
 * {@code L_world}. The server owns stream/config/order and decides whether the
 * current server-observed locomotion state permits dynamic attitude.
 *
 * <p>Effective inertia always comes from the accepted server config
 * generation; it is never trusted from an uplink payload.</p>
 */
public final class BodyAttitudeStateReceiver {

    private record Received(
            long stream,
            long sequence
    ) {}

    private static final Map<
            Player,
            Received
            > RECEIVED =
            new WeakHashMap<>();

    private BodyAttitudeStateReceiver() {}

    public static boolean receive(
            ServerPlayer player,
            ServerboundBodyAttitudeStatePayload update
    ) {
        return receive(
                player,
                update,
                state ->
                        PacketDistributor
                                .sendToPlayersTrackingEntity(
                                        player,
                                        state
                                )
        );
    }

    static boolean receive(
            Player player,
            ServerboundBodyAttitudeStatePayload update,
            Consumer<ClientboundBodyAttitudeStatePayload> relay
    ) {
        var component =
                BodyAttitudeRuntime.Access.peek(player);

        if (component == null) {
            return false;
        }

        var before = component.snapshot();
        var serverConfig =
                BodyAttitudeRuntime.Config.server();

        if (!before.authoritativeStreamOpen()
                || update.streamEpoch()
                != before.authoritativeStreamEpoch()
                || !update.dimensionId().equals(
                player.level()
                        .dimension()
                        .location()
        )
                || update.configGeneration()
                != serverConfig.generation()) {
            return false;
        }

        var last = RECEIVED.get(player);

        if (last != null
                && last.stream()
                == update.streamEpoch()
                && update.stateSequence()
                <= last.sequence()) {
            return false;
        }

        if (!update.hasUsableRepresentation()) {
            return false;
        }

        /*
         * Dynamic ownership is server-approved policy,
         * not a client boolean.
         *
         * ELYTRA_ALIGNED is currently the only dynamic
         * player attitude contract.
         *
         * This resolution reads the same immutable player-state
         * evidence as normal attitude policy, but deliberately
         * does not consume movement input or mutate the character
         * mode/cache from packet reception.
         */
        var playerState =
                MinecraftBodyAttitudeSnapshotAdapter
                        .snapshot(player);

        boolean eligibleForSpecialAttitude =
                BodyAttitudeControlPolicyResolver
                        .findSuspension(playerState)
                        .isEmpty();

        boolean serverExpectsDynamic =
                eligibleForSpecialAttitude
                        && playerState.fallFlying();

        /*
         * Presence must agree exactly.
         *
         * Missing L for a required dynamic body must not
         * silently become L=0.
         *
         * Conversely, a sender cannot enable the dynamic
         * physics owner by setting a packet flag.
         */
        if (update.dynamicStatePresent()
                != serverExpectsDynamic) {
            return false;
        }

        long revision =
                Math.incrementExact(
                        before.authoritativeRevision()
                );

        BodyAttitudeState state =
                serverExpectsDynamic

                        ? AttitudeDynamicHandoff
                        .installDynamic(
                                update.worldFromBody(),
                                update.angularMomentumWorld(),
                                serverConfig.simulation()
                                        .elytraEffectiveAngularInertia(),
                                before.state().tick(),
                                revision,
                                update.initialized()
                        )

                        : AttitudeDynamicHandoff
                        .installKinematic(
                                update.worldFromBody(),
                                before.state().tick(),
                                revision,
                                update.initialized()
                        );

        var view =
                update.initialized()

                        ? BodyRelativeViewState
                        .fromSemantic(
                                new cc.sighs.gravityengine.attitude
                                    .SemanticView(
                                        update.worldFromController()
                                ),
                                update.worldFromBody(),
                                new cc.sighs.gravityengine.attitude
                                    .AttitudeSpaceTransform
                                    .LocalLookAngles(
                                        0,
                                        0
                                )
                        )

                        : BodyRelativeViewState
                        .uninitialized();

        BodyAttitudeDecision installedDecision =
                update.active()

                        ? serverExpectsDynamic

                          ? BodyAttitudeControlPolicyResolver
                        .resolveActive(
                                CharacterAttitudeContract
                                .ELYTRA_ALIGNED
                        )

                          : BodyAttitudeDecision
                        .replicatedActive()

                        : BodyAttitudeDecision
                        .suspended(
                                update.suspensionReason()
                        );

        var result =
                component.installReplicated(
                        new ReplicatedAttitudeState(
                                state,
                                view,
                                installedDecision,

                                update.active()
                                        ? BodyAttitudeContinuity
                                          .CONTINUOUS
                                        : BodyAttitudeContinuity
                                          .INVALID,

                                update.ownership(),

                                Math.max(
                                        0L,
                                        player.level()
                                                .getGameTime()
                                ),

                                revision,
                                update.streamEpoch(),
                                update.configGeneration()
                        )
                );

        if (!result.accepted()) {
            return false;
        }

        var control =
                (cc.sighs.gravityengine.gravity.minecraft.access
                        .CharacterControlAccess) player;

        control.gravityengine$characterMode()
                .installReplicated(
                        update.swimActive()
                );

        control.gravityengine$characterControl()
                .clear();

        RECEIVED.put(
                player,
                new Received(
                        update.streamEpoch(),
                        update.stateSequence()
                )
        );

        relay.accept(
                ClientboundBodyAttitudeStatePayload
                        .from(player)
        );

        return true;
    }
}