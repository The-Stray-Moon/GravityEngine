package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.integration.geometry.NativeAabbApplicationCommit;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Server-committed physical representation. A self correction is one indivisible
 * envelope containing the ORIGINAL Vanilla teleport packet, including its ID.
 * This is not a new movement proposal/acceptance/ack protocol.
 * A null correction is normally an observer snapshot. The explicit
 * nativeApplicationOnly flag instead authorizes a local metadata-only commit;
 * it cannot install a different collider, move the player or generate an ACK.
 */
public record ClientboundPlayerBodyCommitPayload(
        SyncGravityStatePayload assignment,
        long applicationEpoch,
        CommittedGravityApplication application,
        Pose pose,
        GravityFrame installedFrame,
        ClientboundPlayerPositionPacket correction,
        boolean nativeApplicationOnly
) implements CustomPacketPayload {
    public static final Type<ClientboundPlayerBodyCommitPayload> TYPE =
            new Type<>(ResourceLocation.parse(GravityEngine.MOD_ID + ":player_body_commit"));
    public static final StreamCodec<FriendlyByteBuf, ClientboundPlayerBodyCommitPayload> STREAM_CODEC =
            StreamCodec.of(ClientboundPlayerBodyCommitPayload::encode,
                    ClientboundPlayerBodyCommitPayload::decode);

    /** Existing six-argument call sites remain correction/observer snapshots. */
    public ClientboundPlayerBodyCommitPayload(
            SyncGravityStatePayload assignment, long applicationEpoch,
            CommittedGravityApplication application, Pose pose, GravityFrame installedFrame,
            ClientboundPlayerPositionPacket correction) {
        this(assignment, applicationEpoch, application, pose, installedFrame, correction, false);
    }

    public ClientboundPlayerBodyCommitPayload {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(pose, "pose");
        if (applicationEpoch < 0) throw new IllegalArgumentException("negative application epoch");
        if (application.plan().usesCustomBody() != (installedFrame != null)) {
            throw new IllegalArgumentException("committed plan/frame mismatch");
        }
        if (installedFrame != null && (!Double.isFinite(installedFrame.strength())
                || installedFrame.strength() < 0.0D)) {
            throw new IllegalArgumentException("invalid frame strength");
        }
        if (nativeApplicationOnly && (correction != null
                || !NativeAabbApplicationCommit.isNativeApplication(application, installedFrame))) {
            throw new IllegalArgumentException("native application commit cannot carry a teleport or exact body");
        }
        if (correction != null && (!Double.isFinite(correction.getX())
                || !Double.isFinite(correction.getY()) || !Double.isFinite(correction.getZ())
                || !Float.isFinite(correction.getYRot()) || !Float.isFinite(correction.getXRot()))) {
            throw new IllegalArgumentException("non-finite correction");
        }
    }

    public static ClientboundPlayerBodyCommitPayload capture(
            ServerPlayer player, ClientboundPlayerPositionPacket correction) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        return new ClientboundPlayerBodyCommitPayload(SyncGravityStatePayload.from(player),
                component.applicationEpoch(), component.committedApplication(), player.getPose(),
                component.appliedPlan().usesCustomBody()
                        ? Objects.requireNonNull(component.runtime().geometryReferenceFrame(), "installed frame")
                        : null,
                correction);
    }

    public static ClientboundPlayerBodyCommitPayload captureNativeApplication(ServerPlayer player) {
        var snapshot = capture(player, null);
        return new ClientboundPlayerBodyCommitPayload(
                snapshot.assignment(), snapshot.applicationEpoch(), snapshot.application(),
                snapshot.pose(), snapshot.installedFrame(), null, true);
    }

    private static void encode(FriendlyByteBuf b, ClientboundPlayerBodyCommitPayload p) {
        SyncGravityStatePayload.STREAM_CODEC.encode(b, p.assignment);
        b.writeVarLong(p.applicationEpoch);
        writeVector(b, p.application.appliedState().down());
        b.writeDouble(p.application.appliedState().strength());
        b.writeVarInt(p.application.effectiveSuppression().networkId());
        b.writeEnum(p.application.plan().kind());
        b.writeEnum(p.application.plan().accelerationMode());
        b.writeEnum(p.pose);
        b.writeBoolean(p.installedFrame != null);
        if (p.installedFrame != null) {
            writeVector(b, p.installedFrame.samplePoint());
            writeVector(b, p.installedFrame.left());
            writeVector(b, p.installedFrame.up());
            writeVector(b, p.installedFrame.forward());
            b.writeDouble(p.installedFrame.strength());
        }
        b.writeBoolean(p.correction != null);
        if (p.correction != null) ClientboundPlayerPositionPacket.STREAM_CODEC.encode(b, p.correction);
        b.writeBoolean(p.nativeApplicationOnly);
    }

    private static ClientboundPlayerBodyCommitPayload decode(FriendlyByteBuf b) {
        var assignment = SyncGravityStatePayload.STREAM_CODEC.decode(b);
        long epoch = b.readVarLong();
        var state = new GravityState(readVector(b), b.readDouble());
        var suppression = GravitySuppressionReason.fromNetworkId(b.readVarInt());
        var plan = new GravityApplicationPlan(b.readEnum(GravityApplicationPlan.Kind.class),
                b.readEnum(GravityAccelerationMode.class));
        var pose = b.readEnum(Pose.class);
        GravityFrame frame = null;
        if (b.readBoolean()) {
            Vec3 sample = readVector(b), left = readVector(b), up = readVector(b), forward = readVector(b);
            frame = new GravityFrame(sample, new OrthonormalFrame3d(
                    vector(left), vector(up), vector(forward)), b.readDouble());
        }
        var correction = b.readBoolean() ? ClientboundPlayerPositionPacket.STREAM_CODEC.decode(b) : null;
        boolean nativeApplicationOnly = b.readBoolean();
        return new ClientboundPlayerBodyCommitPayload(assignment, epoch,
                new CommittedGravityApplication(state, suppression, plan), pose, frame,
                correction, nativeApplicationOnly);
    }

    private static Vector3d vector(Vec3 v) { return new Vector3d(v.x, v.y, v.z); }
    private static void writeVector(FriendlyByteBuf b, Vec3 v) {
        b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z);
    }
    private static Vec3 readVector(FriendlyByteBuf b) {
        Vec3 v = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        if (!Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)) {
            throw new IllegalArgumentException("non-finite vector");
        }
        return v;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
