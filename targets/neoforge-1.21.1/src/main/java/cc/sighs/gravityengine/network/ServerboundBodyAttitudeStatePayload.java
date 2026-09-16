package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.attitude.runtime.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;

import java.util.Objects;

/** Current absolute body and head joints, which Vanilla yaw/pitch cannot encode. No movement or input history. */
public record ServerboundBodyAttitudeStatePayload(
        ResourceLocation dimensionId, long configGeneration, boolean initialized,
        BodyAttitudeOwnership ownership, BodyAttitudeSuspensionReason suspensionReason,
        Quaterniond worldFromBody, Quaterniond worldFromController, boolean swimActive,
        long streamEpoch, long stateSequence
) implements CustomPacketPayload {
    public static final Type<ServerboundBodyAttitudeStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(GravityEngine.MOD_ID, "body_attitude_update"));
    public static final StreamCodec<FriendlyByteBuf, ServerboundBodyAttitudeStatePayload> STREAM_CODEC =
            StreamCodec.of(ServerboundBodyAttitudeStatePayload::encode, ServerboundBodyAttitudeStatePayload::decode);

    public ServerboundBodyAttitudeStatePayload {
        Objects.requireNonNull(dimensionId);
        Objects.requireNonNull(ownership);
        Objects.requireNonNull(suspensionReason);
        Objects.requireNonNull(worldFromBody);
        Objects.requireNonNull(worldFromController);
        if (streamEpoch <= 0 || stateSequence < 0) {
            throw new IllegalArgumentException("invalid body state sequence");
        }
        boolean active = ownership != BodyAttitudeOwnership.INACTIVE;
        if (configGeneration <= 0 || active && (!initialized || suspensionReason != BodyAttitudeSuspensionReason.NONE)
                || !active && suspensionReason == BodyAttitudeSuspensionReason.NONE) {
            throw new IllegalArgumentException("invalid body attitude state");
        }
        worldFromBody = BodyAttitudeRepresentation.normalizedCopyOrUnusable(worldFromBody);
        worldFromController = BodyAttitudeRepresentation.normalizedCopyOrUnusable(worldFromController);

    }

    public boolean active() { return ownership != BodyAttitudeOwnership.INACTIVE; }
    /** Decodable but unusable numeric state reaches the receiver only to be discarded. */
    public boolean hasUsableRepresentation() {
        return BodyAttitudeRepresentation.usable(worldFromBody, worldFromController);
    }
    @Override public Quaterniond worldFromBody() { return new Quaterniond(worldFromBody); }

    @Override public Quaterniond worldFromController() { return new Quaterniond(worldFromController); }

    public static ServerboundBodyAttitudeStatePayload from(Player player) {
        var c = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(player).snapshot();
        return new ServerboundBodyAttitudeStatePayload(player.level().dimension().location(),
                c.authoritativeConfigGeneration(), c.state().initialized(), c.ownership(),
                c.decision().suspensionReason(), c.state().currentWorldFromBody(), c.view().semantic(c.state().currentWorldFromBody()).worldFromController(),
                ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player).gravityengine$characterMode().swimActive(),
                c.authoritativeStreamEpoch(), 0);
    }

    /** Orders current actor state within its server-owned stream. */
    public ServerboundBodyAttitudeStatePayload forTransport(long sequence) {
        return new ServerboundBodyAttitudeStatePayload(dimensionId, configGeneration, initialized,
                ownership, suspensionReason, worldFromBody, worldFromController, swimActive,
                streamEpoch, sequence);
    }

    private static void encode(FriendlyByteBuf b, ServerboundBodyAttitudeStatePayload p) {
        b.writeResourceLocation(p.dimensionId); b.writeVarLong(p.configGeneration); b.writeBoolean(p.initialized);
        b.writeByte(BodyAttitudeWireValues.ownershipId(p.ownership));
        b.writeByte(BodyAttitudeWireValues.suspensionReasonId(p.suspensionReason));
        b.writeDouble(p.worldFromBody.x); b.writeDouble(p.worldFromBody.y);
        b.writeDouble(p.worldFromBody.z); b.writeDouble(p.worldFromBody.w);
        b.writeDouble(p.worldFromController.x); b.writeDouble(p.worldFromController.y);
        b.writeDouble(p.worldFromController.z); b.writeDouble(p.worldFromController.w);
        b.writeBoolean(p.swimActive);
        b.writeVarLong(p.streamEpoch); b.writeVarLong(p.stateSequence);
    }

    private static ServerboundBodyAttitudeStatePayload decode(FriendlyByteBuf b) {
        return new ServerboundBodyAttitudeStatePayload(b.readResourceLocation(), b.readVarLong(), b.readBoolean(),
                BodyAttitudeWireValues.ownership(b.readByte()), BodyAttitudeWireValues.suspensionReason(b.readByte()),
                new Quaterniond(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()),
                new Quaterniond(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()), b.readBoolean(),
                b.readVarLong(), b.readVarLong());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
