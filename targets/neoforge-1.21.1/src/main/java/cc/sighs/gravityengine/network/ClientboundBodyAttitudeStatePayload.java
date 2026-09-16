package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;

import java.util.Objects;
import java.util.UUID;

/** Protocol 16 immutable current Qbody/Qcontroller and resolved transient swim mode.
 * Observers interpolate installed quaternions; no raw input or angular momentum is transported. */
public record ClientboundBodyAttitudeStatePayload(
        int entityId,
        UUID entityUuid,
        ResourceLocation dimensionId,
        long streamEpoch,
        long authoritativeRevision,
        long authoritativeServerGameTick,
        long authoritativeConfigGeneration,
        boolean initialized,
        boolean active,
        BodyAttitudeContinuity continuity,
        BodyAttitudeOwnership ownership,
        BodyAttitudeSuspensionReason suspensionReason,
        Quaterniond worldFromBody,
        Quaterniond worldFromController,
        boolean swimActive
) implements CustomPacketPayload {
    public static final int HARD_MAX_DIMENSION_ID_CHARACTERS = cc.sighs.gravityengine.protocol.BodyAttitudeProtocolLimits.HARD_MAX_DIMENSION_ID_CHARACTERS;

    public static final Type<ClientboundBodyAttitudeStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    GravityEngine.MOD_ID, "body_attitude_state"));
    public static final StreamCodec<FriendlyByteBuf, ClientboundBodyAttitudeStatePayload>
            STREAM_CODEC = StreamCodec.of(
                    ClientboundBodyAttitudeStatePayload::encode,
                    ClientboundBodyAttitudeStatePayload::decode);

    public ClientboundBodyAttitudeStatePayload {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(continuity, "continuity");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(suspensionReason, "suspensionReason");
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(worldFromController, "worldFromController");
        if (dimensionId.toString().length() > HARD_MAX_DIMENSION_ID_CHARACTERS) {
            throw new IllegalArgumentException(
                    "dimensionId exceeds protocol character bound");
        }
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        if (streamEpoch <= 0L || authoritativeRevision < 0L
                || authoritativeServerGameTick < 0L
                || authoritativeConfigGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "stream/config generations must be positive and authoritative counters non-negative");
        }
        if (active) {
            if (ownership != BodyAttitudeOwnership.ACTIVE) {
                throw new IllegalArgumentException(
                        "active wire state requires ACTIVE ownership");
            }
            if (!initialized || continuity != BodyAttitudeContinuity.CONTINUOUS) {
                throw new IllegalArgumentException(
                        "active state must be initialized and continuous");
            }
            if (suspensionReason != BodyAttitudeSuspensionReason.NONE) {
                throw new IllegalArgumentException(
                        "active wire state must have NONE suspension reason");
            }
        } else {
            if (ownership != BodyAttitudeOwnership.INACTIVE) {
                throw new IllegalArgumentException(
                        "inactive wire state requires INACTIVE ownership");
            }
            if (suspensionReason == BodyAttitudeSuspensionReason.NONE) {
                throw new IllegalArgumentException(
                        "inactive wire state requires a suspension reason");
            }
        }
        if (continuity == BodyAttitudeContinuity.CONTINUOUS
                && (!active || !initialized)) {
            throw new IllegalArgumentException(
                    "continuous wire state must be active and initialized");
        }
        worldFromBody = BodyAttitudeRepresentation.normalizedCopyOrUnusable(worldFromBody);
        worldFromController = BodyAttitudeRepresentation.normalizedCopyOrUnusable(worldFromController);

    }

    public boolean hasUsableRepresentation() {
        return BodyAttitudeRepresentation.usable(worldFromBody, worldFromController);
    }

    public static ClientboundBodyAttitudeStatePayload from(Player player) {
        BodyAttitudeComponent component =
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access
                        .component(player);
        BodyAttitudeComponent.Snapshot snapshot = component.snapshot();
        BodyRelativeViewState view = snapshot.view();
        return new ClientboundBodyAttitudeStatePayload(
                player.getId(), player.getUUID(), player.level().dimension().location(),
                snapshot.authoritativeStreamEpoch(),
                snapshot.authoritativeRevision(),
                snapshot.authoritativeServerGameTick(),
                snapshot.authoritativeConfigGeneration(),
                snapshot.state().initialized(), snapshot.decision().active(),
                snapshot.continuity(),
                component.ownership(),
                snapshot.decision().suspensionReason(),
                snapshot.state().currentWorldFromBody(),
                view.semantic(snapshot.state().currentWorldFromBody()).worldFromController(),
                ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player).gravityengine$characterMode().swimActive());
    }

    @Override public Quaterniond worldFromBody() {
        return new Quaterniond(worldFromBody);
    }

    @Override public Quaterniond worldFromController() { return new Quaterniond(worldFromController); }

    private static void encode(
            FriendlyByteBuf buf,
            ClientboundBodyAttitudeStatePayload value
    ) {
        buf.writeVarInt(value.entityId());
        buf.writeUUID(value.entityUuid());
        buf.writeResourceLocation(value.dimensionId());
        buf.writeVarLong(value.streamEpoch());
        buf.writeVarLong(value.authoritativeRevision());
        buf.writeVarLong(value.authoritativeServerGameTick());
        buf.writeVarLong(value.authoritativeConfigGeneration());
        buf.writeBoolean(value.initialized());
        buf.writeBoolean(value.active());
        buf.writeByte(BodyAttitudeWireValues.continuityId(value.continuity()));
        buf.writeByte(BodyAttitudeWireValues.ownershipId(value.ownership()));
        buf.writeByte(BodyAttitudeWireValues.suspensionReasonId(value.suspensionReason()));
        Quaterniond q = value.worldFromBody;
        buf.writeDouble(q.x); buf.writeDouble(q.y); buf.writeDouble(q.z); buf.writeDouble(q.w);
        Quaterniond controller = value.worldFromController;
        buf.writeDouble(controller.x); buf.writeDouble(controller.y); buf.writeDouble(controller.z); buf.writeDouble(controller.w);
        buf.writeBoolean(value.swimActive);

    }

    private static ClientboundBodyAttitudeStatePayload decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID uuid = buf.readUUID();
        ResourceLocation dimension = buf.readResourceLocation();
        long streamEpoch = buf.readVarLong();
        long revision = buf.readVarLong();
        long serverGameTick = buf.readVarLong();
        long configGeneration = buf.readVarLong();
        boolean initialized = buf.readBoolean();
        boolean active = buf.readBoolean();
        BodyAttitudeContinuity continuity = BodyAttitudeWireValues.continuity(buf.readByte());
        BodyAttitudeOwnership ownership = BodyAttitudeWireValues.ownership(buf.readByte());
        BodyAttitudeSuspensionReason suspensionReason =
                BodyAttitudeWireValues.suspensionReason(buf.readByte());
        Quaterniond q = new Quaterniond(
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        Quaterniond controller = new Quaterniond(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        boolean swim = buf.readBoolean();
        return new ClientboundBodyAttitudeStatePayload(
                entityId, uuid, dimension, streamEpoch, revision, serverGameTick,
                configGeneration,
                initialized, active, continuity, ownership, suspensionReason,
                q, controller, swim);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
