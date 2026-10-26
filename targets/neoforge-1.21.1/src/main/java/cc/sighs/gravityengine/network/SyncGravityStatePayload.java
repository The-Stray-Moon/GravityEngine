package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.api.math.Vec3d;
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

import java.util.Objects;
import java.util.UUID;

/**
 * The {@code assignmentSyncRevision} wire slot carries transient
 * snapshot ordering, not the durable NBT revision.
 * A loaded server state starts this sequence at its durable revision for
 * compatibility, then advances it independently for live truth. It is not a
 * persisted network history; replica identity/lifecycle and monotonic snapshot
 * admission remain the owners of stale-message rejection.
 *
 * <p>FIELD evidence is transmitted as UNKNOWN/PRESENT/ABSENT. UNKNOWN
 * carries retained assignment continuity, never a fabricated absence.</p>
 */
public record SyncGravityStatePayload(
        int entityId,
        UUID entityUuid,
        ResourceLocation dimensionId,
        long assignmentSyncRevision,
        Vec3d assignedDown,
        double assignedStrength,
        long influenceRevision,
        GravitySuppressionReason suppressionReason,
        GravityAuthorityMode authorityMode,
        cc.sighs.gravityengine.api.FieldPresence fieldPresence,
        long applicationEpoch,
        cc.sighs.gravityengine.gravity.model.CommittedGravityApplication application
) implements CustomPacketPayload {

    public SyncGravityStatePayload {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(assignedDown, "assignedDown");
        Objects.requireNonNull(suppressionReason, "suppressionReason");
        Objects.requireNonNull(authorityMode, "authorityMode");
        Objects.requireNonNull(fieldPresence, "fieldPresence");
        Objects.requireNonNull(application, "application");
        if (applicationEpoch < 0) throw new IllegalArgumentException("negative application epoch");
        if (authorityMode != GravityAuthorityMode.FIELD && fieldPresence != cc.sighs.gravityengine.api.FieldPresence.ABSENT) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }

        if (assignmentSyncRevision < 0L) {
            throw new IllegalArgumentException(
                    "assignmentSyncRevision must be non-negative: "
                            + assignmentSyncRevision
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

        GravityState state = c.state().assignedState();

        return new SyncGravityStatePayload(
                entity.getId(),
                entity.getUUID(),
                entity.level().dimension().location(),
                c.state().assignmentSyncRevision(),
                state.down(),
                state.strength(),
                c.state().influenceRevision(),
                c.state().authoritativeSuppression(),
                c.state().assignedAuthority(),
                c.state().fieldPresence(), c.state().applicationEpoch(), c.state().committedApplication()
        );
    }

    public GravityState toState() {
        return new GravityState(this.assignedDown, this.assignedStrength);
    }

    static void encode(FriendlyByteBuf buf, SyncGravityStatePayload pkt) {
        buf.writeVarInt(pkt.entityId);
        buf.writeUUID(pkt.entityUuid);
        buf.writeResourceLocation(pkt.dimensionId);
        buf.writeVarLong(pkt.assignmentSyncRevision);
        buf.writeDouble(pkt.assignedDown.x());
        buf.writeDouble(pkt.assignedDown.y());
        buf.writeDouble(pkt.assignedDown.z());
        buf.writeDouble(pkt.assignedStrength);
        buf.writeVarLong(pkt.influenceRevision);
        buf.writeVarInt(pkt.suppressionReason.networkId());
        buf.writeVarInt(pkt.authorityMode.networkId());
        buf.writeEnum(pkt.fieldPresence);
        buf.writeVarLong(pkt.applicationEpoch);
        buf.writeDouble(pkt.application.appliedState().down().x());
        buf.writeDouble(pkt.application.appliedState().down().y());
        buf.writeDouble(pkt.application.appliedState().down().z());
        buf.writeDouble(pkt.application.appliedState().strength());
        buf.writeVarInt(pkt.application.authoritativeSuppression().networkId());
        buf.writeEnum(pkt.application.plan().kind());
        buf.writeEnum(pkt.application.plan().accelerationMode());
    }

    static SyncGravityStatePayload decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID entityUuid = buf.readUUID();
        ResourceLocation dimensionId = buf.readResourceLocation();
        long assignmentSyncRevision = buf.readVarLong();
        Vec3d assignedDown = new Vec3d(
                buf.readDouble(), buf.readDouble(), buf.readDouble());
        double assignedStrength = buf.readDouble();
        long influenceRevision = buf.readVarLong();
        GravitySuppressionReason reason =
                GravitySuppressionReason.fromNetworkId(buf.readVarInt());
        GravityAuthorityMode authority =
                GravityAuthorityMode.fromNetworkId(buf.readVarInt());
        cc.sighs.gravityengine.api.FieldPresence fieldPresence = buf.readEnum(cc.sighs.gravityengine.api.FieldPresence.class);
        long epoch = buf.readVarLong();
        var applied = new GravityState(new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readDouble());
        var appliedSuppression = GravitySuppressionReason.fromNetworkId(buf.readVarInt());
        var plan = new cc.sighs.gravityengine.gravity.model.GravityApplicationPlan(
                buf.readEnum(cc.sighs.gravityengine.gravity.model.GravityApplicationPlan.Kind.class),
                buf.readEnum(cc.sighs.gravityengine.gravity.model.GravityAccelerationMode.class));
        var application = new cc.sighs.gravityengine.gravity.model.CommittedGravityApplication(applied, appliedSuppression, plan);
        return new SyncGravityStatePayload(
                entityId, entityUuid, dimensionId,
                assignmentSyncRevision,
                assignedDown, assignedStrength,
                influenceRevision, reason, authority, fieldPresence, epoch, application);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
