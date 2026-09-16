package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Request the sender's current server body/position; no client-authored state. */
public record ServerboundPlayerBodyResyncPayload() implements CustomPacketPayload {
    public static final ServerboundPlayerBodyResyncPayload INSTANCE =
            new ServerboundPlayerBodyResyncPayload();
    public static final Type<ServerboundPlayerBodyResyncPayload> TYPE =
            new Type<>(ResourceLocation.parse(GravityEngine.MOD_ID + ":player_body_resync"));
    public static final StreamCodec<FriendlyByteBuf, ServerboundPlayerBodyResyncPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {}, buffer -> INSTANCE);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
