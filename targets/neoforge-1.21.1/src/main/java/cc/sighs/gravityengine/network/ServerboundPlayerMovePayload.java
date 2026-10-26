package cc.sighs.gravityengine.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One native movement proposal with its prediction body epoch as diagnostic context.
 * No client geometry, collision result, timestamp or independent sequence. */
public record ServerboundPlayerMovePayload(ResourceLocation dimension, long bodyEpoch,
        ServerboundMovePlayerPacket movement) implements CustomPacketPayload {
    public static final Type<ServerboundPlayerMovePayload> TYPE =
            new Type<>(ResourceLocation.parse("gravityengine:player_move"));
    public static final StreamCodec<FriendlyByteBuf, ServerboundPlayerMovePayload> STREAM_CODEC =
            StreamCodec.of(ServerboundPlayerMovePayload::encode, ServerboundPlayerMovePayload::decode);

    public ServerboundPlayerMovePayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(movement, "movement");
        if (bodyEpoch < 0) throw new IllegalArgumentException("negative body epoch");
    }

    private static void encode(FriendlyByteBuf b, ServerboundPlayerMovePayload p) {
        b.writeResourceLocation(p.dimension);
        b.writeVarLong(p.bodyEpoch);
        var m = p.movement;
        b.writeByte((m.hasPosition() ? 1 : 0) | (m.hasRotation() ? 2 : 0));
        if (m.hasPosition()) { b.writeDouble(m.getX(0)); b.writeDouble(m.getY(0)); b.writeDouble(m.getZ(0)); }
        if (m.hasRotation()) { b.writeFloat(m.getYRot(0)); b.writeFloat(m.getXRot(0)); }
        b.writeBoolean(m.isOnGround());
    }

    private static ServerboundPlayerMovePayload decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation();
        long epoch = b.readVarLong();
        int flags = b.readUnsignedByte();
        if (flags > 3) throw new IllegalArgumentException("invalid movement flags");
        double x = 0, y = 0, z = 0;
        float yaw = 0, pitch = 0;
        if ((flags & 1) != 0) { x = b.readDouble(); y = b.readDouble(); z = b.readDouble(); }
        if ((flags & 2) != 0) { yaw = b.readFloat(); pitch = b.readFloat(); }
        boolean ground = b.readBoolean();
        ServerboundMovePlayerPacket movement = switch (flags) {
            case 0 -> new ServerboundMovePlayerPacket.StatusOnly(ground);
            case 1 -> new ServerboundMovePlayerPacket.Pos(x, y, z, ground);
            case 2 -> new ServerboundMovePlayerPacket.Rot(yaw, pitch, ground);
            default -> new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, ground);
        };
        return new ServerboundPlayerMovePayload(dimension, epoch, movement);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
