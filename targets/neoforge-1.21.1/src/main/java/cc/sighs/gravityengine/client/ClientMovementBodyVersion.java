package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.ServerboundPlayerMovePayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;

/** Prediction-to-send binding. A tick's body publication cannot be replaced by
 * a newer epoch merely because the wire send occurs later. Entity refs live only in the scope. */
public final class ClientMovementBodyVersion implements AutoCloseable {
    private static ClientMovementBodyVersion active;
    private final ClientMovementBodyVersion parent;
    private final LocalPlayer player;
    private final long epoch;
    private final ResourceLocation dimension;

    private ClientMovementBodyVersion(LocalPlayer player) {
        this.parent = active;
        this.player = player;
        this.epoch = epoch(player);
        this.dimension = player.level().dimension().location();
        active = this;
    }

    public static ClientMovementBodyVersion begin(LocalPlayer player) { return new ClientMovementBodyVersion(player); }
    public static boolean predicting(LocalPlayer player) { return active != null && active.player == player; }

    public static Packet<?> envelope(LocalPlayer player, Packet<?> packet, boolean correctionReply) {
        if (!(packet instanceof ServerboundMovePlayerPacket movement)) return packet;
        var prediction = active;
        boolean captured = !correctionReply && prediction != null && prediction.player == player;
        return new ServerboundCustomPayloadPacket(new ServerboundPlayerMovePayload(
                captured ? prediction.dimension : player.level().dimension().location(),
                captured ? prediction.epoch : epoch(player), movement));
    }

    private static long epoch(LocalPlayer player) {
        return GravityEntityAccess.cast(player).gravityengine$gravityComponent().state().applicationEpoch();
    }

    @Override public void close() {
        if (active != this) throw new IllegalStateException("prediction scopes must close in order");
        active = parent;
    }
}
