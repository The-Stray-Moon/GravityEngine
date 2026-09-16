package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.attitude.BodyAttitudeConfigSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-published control law for owning-client state production. */
public record ClientboundBodyAttitudeConfigPayload(
        long generation,
        BodyAttitudeConfigSnapshot simulation
) implements CustomPacketPayload {
    public static final Type<ClientboundBodyAttitudeConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(GravityEngine.MOD_ID, "body_attitude_config"));
    public static final StreamCodec<FriendlyByteBuf, ClientboundBodyAttitudeConfigPayload>
            STREAM_CODEC = StreamCodec.of(
                    ClientboundBodyAttitudeConfigPayload::encode,
                    ClientboundBodyAttitudeConfigPayload::decode);

    public ClientboundBodyAttitudeConfigPayload {
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        if (simulation == null) throw new NullPointerException("simulation");
    }

    public static ClientboundBodyAttitudeConfigPayload currentServer() {
        cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.Generation current = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Config.server();
        return new ClientboundBodyAttitudeConfigPayload(
                current.generation(), current.simulation());
    }

    private static void encode(FriendlyByteBuf b, ClientboundBodyAttitudeConfigPayload p) {
        BodyAttitudeConfigSnapshot c = p.simulation;
        b.writeVarLong(p.generation);
        b.writeDouble(c.lowGravitySwimThresholdRatio());
        b.writeDouble(Math.toDegrees(c.elytraMaxPitchRateRadiansPerSecond()));
        b.writeDouble(Math.toDegrees(c.elytraMaxYawRateRadiansPerSecond()));
        b.writeDouble(Math.toDegrees(c.controllerRollRateRadiansPerSecond()));
        b.writeDouble(Math.toDegrees(
                c.elytraRollAngularAccelerationRadiansPerSecondSquared()));
        b.writeDouble(c.elytraGravityAlignmentGainPerSecondSquared()); b.writeDouble(c.elytraAngularDragPerSecond());
        b.writeDouble(c.elytraMaxGravityScale()); b.writeDouble(c.elytraFlightAlignmentGainPerSecondSquared());
        b.writeDouble(Math.toDegrees(c.elytraMaxAngularAccelerationRadiansPerSecondSquared()));
        b.writeDouble(Math.toDegrees(c.elytraMaxAngularSpeedRadiansPerSecond()));
        b.writeDouble(c.elytraVelocityAlignment()); b.writeDouble(c.elytraVelocityAlignStartSpeed());
        b.writeDouble(c.elytraVelocityAlignFullSpeed());
        b.writeDouble(c.quaternionEpsilon()); b.writeDouble(c.vectorEpsilon());
        b.writeDouble(c.maxSubstepSeconds());
        b.writeVarInt(c.maxSubsteps());
    }

    private static ClientboundBodyAttitudeConfigPayload decode(FriendlyByteBuf b) {
        long generation = b.readVarLong();
        BodyAttitudeConfigSnapshot c = new BodyAttitudeConfigSnapshot(
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readVarInt());
        return new ClientboundBodyAttitudeConfigPayload(generation, c);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
