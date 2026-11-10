package cc.sighs.gravityengine.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One native movement proposal with its prediction body epoch as diagnostic context.
 * Impact evidence belongs to that prediction only; it carries no geometry,
 * timestamp, independent sequence or persistent velocity estimate. */
public record ServerboundPlayerMovePayload(ResourceLocation dimension, long bodyEpoch,
        ServerboundMovePlayerPacket movement,
        java.util.List<cc.sighs.gravityengine.gravity.integration.ImpactDamageIntegration.Impact> impacts) implements CustomPacketPayload {
    public static final Type<ServerboundPlayerMovePayload> TYPE =
            new Type<>(ResourceLocation.parse("gravityengine:player_move"));
    public static final StreamCodec<FriendlyByteBuf, ServerboundPlayerMovePayload> STREAM_CODEC =
            StreamCodec.of(ServerboundPlayerMovePayload::encode, ServerboundPlayerMovePayload::decode);

    public ServerboundPlayerMovePayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(movement, "movement");
        impacts = java.util.List.copyOf(impacts);
        if (bodyEpoch < 0) throw new IllegalArgumentException("negative body epoch");
    }

    public ServerboundPlayerMovePayload(ResourceLocation dimension, long bodyEpoch, ServerboundMovePlayerPacket movement) {
        this(dimension, bodyEpoch, movement, java.util.List.of());
    }

    private static void encode(FriendlyByteBuf b, ServerboundPlayerMovePayload p) {
        b.writeResourceLocation(p.dimension);
        b.writeVarLong(p.bodyEpoch);
        var m = p.movement;
        b.writeByte((m.hasPosition() ? 1 : 0) | (m.hasRotation() ? 2 : 0));
        if (m.hasPosition()) { b.writeDouble(m.getX(0)); b.writeDouble(m.getY(0)); b.writeDouble(m.getZ(0)); }
        if (m.hasRotation()) { b.writeFloat(m.getYRot(0)); b.writeFloat(m.getXRot(0)); }
        b.writeBoolean(m.isOnGround());
        b.writeCollection(p.impacts, (buffer, impact) -> {
            buffer.writeFloat(impact.distance());
            buffer.writeBoolean(impact.materialDimension() != null);
            if (impact.materialDimension() != null) {
                buffer.writeResourceLocation(impact.materialDimension());
                buffer.writeBlockPos(impact.materialPosition());
            }
        });
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
        var impacts = b.readList(buffer -> {
            float distance = buffer.readFloat();
            boolean material = buffer.readBoolean();
            return new cc.sighs.gravityengine.gravity.integration.ImpactDamageIntegration.Impact(distance,
                    material ? buffer.readResourceLocation() : null, material ? buffer.readBlockPos() : null);
        });
        return new ServerboundPlayerMovePayload(dimension, epoch, movement, impacts);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
