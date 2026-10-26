package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * Owning-client body/view state for the server-owned stream.
 *
 * <p>The client may supply actor pose and, when its local state is dynamic,
 * the authoritative world-space angular momentum {@code L_world}. It does not
 * supply effective inertia: inertia belongs to the server-approved control
 * profile/config generation.</p>
 *
 * <p>{@code dynamicStatePresent} is sender representation evidence, not
 * permission to choose dynamic ownership. The receiver must verify it against
 * the current server-side locomotion/attitude contract before installation.</p>
 */
public record ServerboundBodyAttitudeStatePayload(
        ResourceLocation dimensionId,
        long configGeneration,
        boolean initialized,
        BodyAttitudeOwnership ownership,
        BodyAttitudeSuspensionReason suspensionReason,
        Quatd worldFromBody,
        Quatd worldFromController,
        boolean swimActive,
        boolean dynamicStatePresent,
        Vec3d angularMomentumWorld,
        long streamEpoch,
        long stateSequence
) implements CustomPacketPayload {

    public static final Type<
            ServerboundBodyAttitudeStatePayload
            > TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            GravityEngine.MOD_ID,
                            "body_attitude_update"
                    )
            );

    public static final StreamCodec<
            FriendlyByteBuf,
            ServerboundBodyAttitudeStatePayload
            > STREAM_CODEC =
            StreamCodec.of(
                    ServerboundBodyAttitudeStatePayload::encode,
                    ServerboundBodyAttitudeStatePayload::decode
            );

    public ServerboundBodyAttitudeStatePayload {
        Objects.requireNonNull(
                dimensionId,
                "dimensionId"
        );
        Objects.requireNonNull(
                ownership,
                "ownership"
        );
        Objects.requireNonNull(
                suspensionReason,
                "suspensionReason"
        );
        Objects.requireNonNull(
                worldFromBody,
                "worldFromBody"
        );
        Objects.requireNonNull(
                worldFromController,
                "worldFromController"
        );
        Objects.requireNonNull(
                angularMomentumWorld,
                "angularMomentumWorld"
        );

        if (streamEpoch <= 0L
                || stateSequence < 0L) {
            throw new IllegalArgumentException(
                    "invalid body state sequence"
            );
        }

        boolean active =
                ownership != BodyAttitudeOwnership.INACTIVE;

        if (configGeneration <= 0L
                || active
                && (!initialized
                || suspensionReason
                != BodyAttitudeSuspensionReason.NONE)
                || !active
                && suspensionReason
                == BodyAttitudeSuspensionReason.NONE) {
            throw new IllegalArgumentException(
                    "invalid body attitude state"
            );
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
            if (!active || !initialized) {
                throw new IllegalArgumentException(
                        "dynamic state requires "
                                + "an active initialized body"
                );
            }

            requireMomentumProtocolSanity(
                    angularMomentumWorld
            );
        } else if (!angularMomentumWorld.equals(
                Vec3d.ZERO
        )) {
            throw new IllegalArgumentException(
                    "a kinematic handoff must not "
                            + "carry angular momentum"
            );
        }

        worldFromBody =
                BodyAttitudeRepresentation
                        .normalizedCopyOrUnusable(
                                worldFromBody
                        );

        worldFromController =
                BodyAttitudeRepresentation
                        .normalizedCopyOrUnusable(
                                worldFromController
                        );
    }

    public boolean active() {
        return ownership
                != BodyAttitudeOwnership.INACTIVE;
    }

    private static void requireMomentumProtocolSanity(
            Vec3d angularMomentumWorld
    ) {
        if (angularMomentumWorld.length()
                > cc.sighs.gravityengine.protocol
                .BodyAttitudeProtocolLimits
                .HARD_MAX_ANGULAR_MOMENTUM_MAGNITUDE) {
            throw new IllegalArgumentException(
                    "angular momentum exceeds "
                            + "the protocol sanity bound"
            );
        }
    }

    public boolean hasUsableRepresentation() {
        return BodyAttitudeRepresentation.usable(
                worldFromBody,
                worldFromController
        );
    }

    public static ServerboundBodyAttitudeStatePayload from(
            Player player
    ) {
        var component =
                cc.sighs.gravityengine.attitude.runtime
                        .BodyAttitudeRuntime.Access
                        .component(player);

        var snapshot = component.snapshot();
        var state = snapshot.state();
        var dynamics =
                state.angularMomentum()
                        .orElse(null);

        return new ServerboundBodyAttitudeStatePayload(
                player.level()
                        .dimension()
                        .location(),
                snapshot.authoritativeConfigGeneration(),
                state.initialized(),
                snapshot.ownership(),
                snapshot.decision()
                        .suspensionReason(),
                state.currentWorldFromBody(),
                snapshot.view()
                        .semantic(
                                state.currentWorldFromBody()
                        )
                        .worldFromController(),
                ((cc.sighs.gravityengine.gravity.minecraft.access
                        .CharacterControlAccess) player)
                        .gravityengine$characterMode()
                        .swimActive(),
                dynamics != null,
                dynamics == null
                        ? Vec3d.ZERO
                        : dynamics.angularMomentumWorld(),
                snapshot.authoritativeStreamEpoch(),
                0L
        );
    }

    public ServerboundBodyAttitudeStatePayload forTransport(
            long sequence
    ) {
        return new ServerboundBodyAttitudeStatePayload(
                dimensionId,
                configGeneration,
                initialized,
                ownership,
                suspensionReason,
                worldFromBody,
                worldFromController,
                swimActive,
                dynamicStatePresent,
                angularMomentumWorld,
                streamEpoch,
                sequence
        );
    }

    private static void encode(
            FriendlyByteBuf buf,
            ServerboundBodyAttitudeStatePayload value
    ) {
        buf.writeResourceLocation(
                value.dimensionId
        );
        buf.writeVarLong(
                value.configGeneration
        );
        buf.writeBoolean(
                value.initialized
        );

        buf.writeByte(
                BodyAttitudeWireValues.ownershipId(
                        value.ownership
                )
        );

        buf.writeByte(
                BodyAttitudeWireValues.suspensionReasonId(
                        value.suspensionReason
                )
        );

        buf.writeDouble(value.worldFromBody.x());
        buf.writeDouble(value.worldFromBody.y());
        buf.writeDouble(value.worldFromBody.z());
        buf.writeDouble(value.worldFromBody.w());

        buf.writeDouble(
                value.worldFromController.x()
        );
        buf.writeDouble(
                value.worldFromController.y()
        );
        buf.writeDouble(
                value.worldFromController.z()
        );
        buf.writeDouble(
                value.worldFromController.w()
        );

        buf.writeBoolean(value.swimActive);
        buf.writeBoolean(value.dynamicStatePresent);

        buf.writeDouble(
                value.angularMomentumWorld.x()
        );
        buf.writeDouble(
                value.angularMomentumWorld.y()
        );
        buf.writeDouble(
                value.angularMomentumWorld.z()
        );

        buf.writeVarLong(value.streamEpoch);
        buf.writeVarLong(value.stateSequence);
    }

    private static ServerboundBodyAttitudeStatePayload decode(
            FriendlyByteBuf buf
    ) {
        return new ServerboundBodyAttitudeStatePayload(
                buf.readResourceLocation(),
                buf.readVarLong(),
                buf.readBoolean(),

                BodyAttitudeWireValues.ownership(
                        buf.readByte()
                ),

                BodyAttitudeWireValues.suspensionReason(
                        buf.readByte()
                ),

                new Quatd(
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble()
                ),

                new Quatd(
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble()
                ),

                buf.readBoolean(),
                buf.readBoolean(),

                new Vec3d(
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble()
                ),

                buf.readVarLong(),
                buf.readVarLong()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}