package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.client.ClientBodyAttitudeControl;
import cc.sighs.gravityengine.network.ServerboundBodyAttitudeStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Consumer;

/** Runs the transformed LocalPlayer.sendPosition, capturing only this fixture's connection. */
final class ClientBodyStateSendChecks {
    static void run(Minecraft mc) throws ReflectiveOperationException {
        var real = mc.player;
        List<Packet<?>> wire = new ArrayList<>();
        var cookie = new CommonListenerCookie(real.connection.getLocalGameProfile(), null,
                mc.level.registryAccess().freeze(), mc.level.enabledFeatures(), null, null, null, Map.of(), null,
                false, Map.of(), net.minecraft.server.ServerLinks.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        var listener = new ClientPacketListener(mc, new Connection(PacketFlow.CLIENTBOUND), cookie) {
            @Override public void send(Packet<?> packet) { wire.add(packet); }
        };
        var actor = new LocalPlayer(mc, mc.level, listener, new net.minecraft.stats.StatsCounter(),
                new net.minecraft.client.ClientRecipeBook(), false, false) {
            @Override protected boolean isControlledCamera() { return true; }
        };
        try {
            actor.setPos(real.position());
            var current = BodyAttitudeRuntime.Access.component(real).snapshot();
            var owner = BodyAttitudeRuntime.Access.component(actor);
            var state = cc.sighs.gravityengine.attitude.BodyAttitudeState.initialized(
                    current.state().currentWorldFromBody(), current.state().angularVelocityWorld(),
                    current.state().tick(), current.authoritativeRevision());
            var result = owner.installReplicated(new ReplicatedAttitudeState(state, current.view(),
                    current.decision(), current.continuity(), current.ownership(), mc.level.getGameTime(),
                    current.authoritativeRevision(), current.authoritativeStreamEpoch(), current.authoritativeConfigGeneration()));
            if (!result.accepted())
                throw new AssertionError("client fixture body install");
            var send = LocalPlayer.class.getDeclaredMethod("sendPosition"); send.setAccessible(true);
            var tickReturn = ClientBodyAttitudeControl.class.getDeclaredMethod("sendCurrentState",
                    net.minecraft.world.entity.player.Player.class, Consumer.class); tickReturn.setAccessible(true);
            send.invoke(actor); // Native last-sent P initialization.
            ClientBodyAttitudeControl.beginTick(actor);
            for (boolean rotation : new boolean[]{false, true}) {
                wire.clear();
                Vec3 before = actor.position();
                actor.setPos(before.add(1e-8, 0, 0));
                if (rotation) actor.setYRot(actor.getYRot() + 1);

                send.invoke(actor);
                Consumer<ServerboundBodyAttitudeStatePayload> bodySend = listener::send;
                tickReturn.invoke(null, actor, bodySend);
                long bodyCount = wire.stream().filter(packet -> packet instanceof ServerboundCustomPayloadPacket body
                        && body.payload() instanceof ServerboundBodyAttitudeStatePayload).count();
                if (wire.stream().anyMatch(packet -> packet instanceof ServerboundMovePlayerPacket movement && movement.hasPosition()))
                    throw new AssertionError("attitude cannot force a subthreshold position packet: " + wire);
                if (!rotation && bodyCount != 1) throw new AssertionError("one independent body update: " + wire);
                if (rotation && wire.stream().noneMatch(packet -> packet instanceof ServerboundMovePlayerPacket movement && movement.hasRotation()))
                    throw new AssertionError("native rotation packet remains independent: " + wire);
                int count = wire.size();
                tickReturn.invoke(null, actor, bodySend);
                send.invoke(actor);
                if (wire.size() != count) throw new AssertionError("no duplicate body or position sends");
            }
            System.out.println("CLIENT_BODY_STATE_SEND_CHECKS_PASSED independent-state native-threshold no-duplicate");
        } finally { ClientBodyAttitudeControl.clear(actor); }
    }
}
