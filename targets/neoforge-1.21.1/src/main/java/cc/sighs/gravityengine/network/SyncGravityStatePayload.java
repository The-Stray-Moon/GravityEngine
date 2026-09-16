package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

public record SyncGravityStatePayload(
        int entityId,
        UUID entityUuid,
        ResourceLocation dimensionId,
        long assignmentRevision,
        Vec3 assignedDown,
        double assignedStrength,
        long influenceRevision,
        GravitySuppressionReason suppressionReason,
        GravityAuthorityMode authorityMode,
        boolean fieldPresent
) implements CustomPacketPayload {

    public SyncGravityStatePayload {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(assignedDown, "assignedDown");
        Objects.requireNonNull(suppressionReason, "suppressionReason");
        Objects.requireNonNull(authorityMode, "authorityMode");
        if (authorityMode != GravityAuthorityMode.FIELD && fieldPresent) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }

        if (assignmentRevision < 0L) {
            throw new IllegalArgumentException(
                    "assignmentRevision must be non-negative: "
                            + assignmentRevision
            );
        }

        if (influenceRevision < 0L) {
            throw new IllegalArgumentException(
                    "influenceRevision must be non-negative: "
                            + influenceRevision
            );
        }

        /*
         * Canonical GravityState construction performs the authoritative
         * direction/strength validation and direction normalization.
         */
        GravityState canonical =
                new GravityState(assignedDown, assignedStrength);

        assignedDown = canonical.down();
        assignedStrength = canonical.strength();
    }

    public static final Type<SyncGravityStatePayload> TYPE =
            new Type<>(ResourceLocation.parse(GravityEngine.MOD_ID + ":sync_gravity_state"));

    public static final StreamCodec<FriendlyByteBuf, SyncGravityStatePayload> STREAM_CODEC =
            StreamCodec.of(SyncGravityStatePayload::encode, SyncGravityStatePayload::decode);

    public static SyncGravityStatePayload from(Entity entity) {
        EntityGravityComponent c =
                GravityEntityAccess.cast(entity).gravityengine$gravityComponent();

        GravityState state = c.assignedState();

        return new SyncGravityStatePayload(
                entity.getId(),
                entity.getUUID(),
                entity.level().dimension().location(),
                c.assignmentRevision(),
                state.down(),
                state.strength(),
                c.influenceRevision(),
                c.authoritativeSuppression(),
                c.assignedAuthority(),
                c.assignedFieldPresent()
        );
    }

    public GravityState toState() {
        return new GravityState(this.assignedDown, this.assignedStrength);
    }

    static void encode(FriendlyByteBuf buf, SyncGravityStatePayload pkt) {
        buf.writeVarInt(pkt.entityId);
        buf.writeUUID(pkt.entityUuid);
        buf.writeResourceLocation(pkt.dimensionId);
        buf.writeVarLong(pkt.assignmentRevision);
        buf.writeDouble(pkt.assignedDown.x);
        buf.writeDouble(pkt.assignedDown.y);
        buf.writeDouble(pkt.assignedDown.z);
        buf.writeDouble(pkt.assignedStrength);
        buf.writeVarLong(pkt.influenceRevision);
        buf.writeVarInt(pkt.suppressionReason.networkId());
        buf.writeVarInt(pkt.authorityMode.networkId());
        buf.writeBoolean(pkt.fieldPresent);
    }

    static SyncGravityStatePayload decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID entityUuid = buf.readUUID();
        ResourceLocation dimensionId = buf.readResourceLocation();
        long assignmentRevision = buf.readVarLong();
        Vec3 assignedDown = new Vec3(
                buf.readDouble(), buf.readDouble(), buf.readDouble());
        double assignedStrength = buf.readDouble();
        long influenceRevision = buf.readVarLong();
        GravitySuppressionReason reason =
                GravitySuppressionReason.fromNetworkId(buf.readVarInt());
        GravityAuthorityMode authority =
                GravityAuthorityMode.fromNetworkId(buf.readVarInt());
        boolean fieldPresent = buf.readBoolean();
        return new SyncGravityStatePayload(
                entityId, entityUuid, dimensionId,
                assignmentRevision,
                assignedDown, assignedStrength,
                influenceRevision, reason, authority, fieldPresent);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
