package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Main-thread latest-state receiver. The connection owns sender identity; the server owns
 * stream/configuration/order, while the owning client produces the actor state. No controller,
 * movement evidence, collider query, owner echo or correction participates in this path. */
public final class BodyAttitudeStateReceiver {
    private record Received(long stream, long sequence) {}
    private static final Map<Player, Received> RECEIVED = new WeakHashMap<>();

    private BodyAttitudeStateReceiver() {}

    public static boolean receive(ServerPlayer player, ServerboundBodyAttitudeStatePayload update) {
        return receive(player, update, state -> PacketDistributor.sendToPlayersTrackingEntity(player, state));
    }

    static boolean receive(Player player, ServerboundBodyAttitudeStatePayload update,
            Consumer<ClientboundBodyAttitudeStatePayload> relay) {
        var component = BodyAttitudeRuntime.Access.peek(player);
        if (component == null) return false;
        var before = component.snapshot();
        if (!before.authoritativeStreamOpen() || update.streamEpoch() != before.authoritativeStreamEpoch()
                || !update.dimensionId().equals(player.level().dimension().location())
                || update.configGeneration() != BodyAttitudeRuntime.Config.server().generation()) return false;
        var last = RECEIVED.get(player);
        if (last != null && last.stream() == update.streamEpoch() && update.stateSequence() <= last.sequence()) return false;
        if (!update.hasUsableRepresentation()) return false;

        long revision = Math.incrementExact(before.authoritativeRevision());
        var q = update.worldFromBody();
        var state = new BodyAttitudeState(q, q, net.minecraft.world.phys.Vec3.ZERO,
                before.state().tick(), revision, update.initialized());
        var view = update.initialized()
                ? BodyRelativeViewState.fromSemantic(new cc.sighs.gravityengine.attitude.SemanticView(update.worldFromController()), q,
                        new cc.sighs.gravityengine.attitude.AttitudeSpaceTransform.LocalLookAngles(0, 0))
                : BodyRelativeViewState.uninitialized();
        var result = component.installReplicated(new ReplicatedAttitudeState(state, view,
                update.active() ? BodyAttitudeDecision.replicatedActive()
                        : BodyAttitudeDecision.suspended(update.suspensionReason()),
                update.active() ? BodyAttitudeContinuity.CONTINUOUS : BodyAttitudeContinuity.INVALID,
                update.ownership(), Math.max(0L, player.level().getGameTime()), revision,
                update.streamEpoch(), update.configGeneration()));
        if (!result.accepted()) return false;
        var control = (cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player;
        control.gravityengine$characterMode().installReplicated(update.swimActive());
        control.gravityengine$characterControl().clear();
        RECEIVED.put(player, new Received(update.streamEpoch(), update.stateSequence()));
        relay.accept(ClientboundBodyAttitudeStatePayload.from(player));
        return true;
    }
}
