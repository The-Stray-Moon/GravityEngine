package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable current Qbody/Qcontroller, the dynamic angular state that must be
 * installed with it, and the resolved transient swim mode.
 *
 * <p>Observers interpolate installed quaternions for presentation only. When
 * the sender's state was dynamic, the payload also carries the durable
 * world-space angular momentum {@code L_world}, so a receiver never fabricates
 * a zero angular velocity for a corrected or replicated dynamic body. A
 * pose-only handoff is declared explicitly and carries no angular momentum.
 * Effective inertia is never a wire operand: it belongs to the receiving
 * control profile/config generation.</p>
 */
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
        Quatd worldFromBody,
        Quatd worldFromController,
        boolean swimActive,
        boolean dynamicStatePresent,
        Vec3d angularMomentumWorld
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
        Objects.requireNonNull(angularMomentumWorld, "angularMomentumWorld");
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
        if (!angularMomentumWorld.isFinite()
                || !Double.isFinite(
                angularMomentumWorld.lengthSquared()
        )) {
            throw new IllegalArgumentException(
                    "angularMomentumWorld must be finite"
            );
        }

        if (dynamicStatePresent) {
            if (!active
                    || !initialized
                    || continuity
                    != BodyAttitudeContinuity.CONTINUOUS) {
                throw new IllegalArgumentException(
                        "dynamic state requires "
                                + "a continuous active body"
                );
            }

            if (angularMomentumWorld.length()
                    > cc.sighs.gravityengine.protocol
                    .BodyAttitudeProtocolLimits
                    .HARD_MAX_ANGULAR_MOMENTUM_MAGNITUDE) {
                throw new IllegalArgumentException(
                        "angular momentum exceeds "
                                + "the protocol sanity bound"
                );
            }
        } else if (!angularMomentumWorld.equals(
                Vec3d.ZERO
        )) {
            throw new IllegalArgumentException(
                    "a pose-only handoff must not "
                            + "carry angular momentum"
            );
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
        var state = snapshot.state();
        var dynamics =
                state.angularMomentum().orElse(null);

        return new ClientboundBodyAttitudeStatePayload(
                player.getId(),
                player.getUUID(),
                player.level().dimension().location(),
                snapshot.authoritativeStreamEpoch(),
                snapshot.authoritativeRevision(),
                snapshot.authoritativeServerGameTick(),
                snapshot.authoritativeConfigGeneration(),
                state.initialized(),
                snapshot.decision().active(),
                snapshot.continuity(),
                component.ownership(),
                snapshot.decision().suspensionReason(),
                state.currentWorldFromBody(),
                view.semantic(
                        state.currentWorldFromBody()
                ).worldFromController(),
                ((cc.sighs.gravityengine.gravity.minecraft.access
                        .CharacterControlAccess) player)
                        .gravityengine$characterMode()
                        .swimActive(),
                dynamics != null,
                dynamics == null
                        ? Vec3d.ZERO
                        : dynamics.angularMomentumWorld()
        );
    }

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
        Quatd q = value.worldFromBody;
        buf.writeDouble(q.x()); buf.writeDouble(q.y()); buf.writeDouble(q.z()); buf.writeDouble(q.w());
        Quatd controller = value.worldFromController;
        buf.writeDouble(controller.x()); buf.writeDouble(controller.y()); buf.writeDouble(controller.z()); buf.writeDouble(controller.w());
        buf.writeBoolean(value.swimActive);
        buf.writeBoolean(value.dynamicStatePresent);

        Vec3d momentum =
                value.angularMomentumWorld;

        buf.writeDouble(momentum.x());
        buf.writeDouble(momentum.y());
        buf.writeDouble(momentum.z());
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
        Quatd q = new Quatd(
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        Quatd controller = new Quatd(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        boolean swim =
                buf.readBoolean();

        boolean dynamicStatePresent =
                buf.readBoolean();

        Vec3d momentum =
                new Vec3d(
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble()
                );

        return new ClientboundBodyAttitudeStatePayload(
                entityId,
                uuid,
                dimension,
                streamEpoch,
                revision,
                serverGameTick,
                configGeneration,
                initialized,
                active,
                continuity,
                ownership,
                suspensionReason,
                q,
                controller,
                swim,
                dynamicStatePresent,
                momentum
        );
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
