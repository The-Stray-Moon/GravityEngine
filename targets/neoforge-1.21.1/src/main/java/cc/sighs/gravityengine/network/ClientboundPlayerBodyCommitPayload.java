package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.integration.geometry.InstalledBodySnapshot;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;

import java.util.Objects;

/**
 * Server-committed physical representation and its authorized use.
 *
 * <p>One physical transaction carries distinct authoritative facts:</p>
 *
 * <ul>
 *   <li>the authoritative application snapshot (assignment, plan, epoch);</li>
 *   <li>{@link BodyRepresentation} - which collider is actually installed
 *       there, {@code NATIVE_AABB} or an exact {@code EXACT_BODY};</li>
 *   <li>{@link CommitMode} - what the receiver is allowed to do with the
 *       snapshot (ordinary transaction, correction or observer).</li>
 * </ul>
 *
 * <p>A correction is one indivisible envelope containing the ORIGINAL Vanilla
 * teleport packet, including its ID. Every other mode must not move the player
 * and must not write kinematic state. The collision axis is separate from the complete reference frame. Neither
 * carries a second position authority or actor attitude.</p>
 */
public record ClientboundPlayerBodyCommitPayload(
        SyncGravityStatePayload assignment,
        long applicationEpoch,
        CommittedGravityApplication application,
        Pose pose,
        double installedWidth,
        double installedHeight,
        Vec3d installedUp,
        GravityFrame gravityReferenceFrame,
        BodyRepresentation representation,
        CommitMode mode,
        ClientboundPlayerPositionPacket correction
) implements CustomPacketPayload {

    /** What the receiving side may do with this authoritative snapshot. */
    public enum CommitMode {
        /** Self correction: install the representation, then run the native position packet. */
        CORRECTION,
        /** Observer snapshot for another entity; never installs the local player. */
        OBSERVER,
        /** Complete representation/application/reference install at the current anchor. */
        TRANSACTION,
        /** Server geometry relocation: native position/ACK, no momentum impulse. */
        RELOCATION
    }

    public static final Type<ClientboundPlayerBodyCommitPayload> TYPE =
            new Type<>(ResourceLocation.parse(GravityEngine.MOD_ID + ":player_body_commit"));
    public static final StreamCodec<FriendlyByteBuf, ClientboundPlayerBodyCommitPayload> STREAM_CODEC =
            StreamCodec.of(ClientboundPlayerBodyCommitPayload::encode,
                    ClientboundPlayerBodyCommitPayload::decode);

    public ClientboundPlayerBodyCommitPayload {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(mode, "mode");
        if (applicationEpoch < 0) throw new IllegalArgumentException("negative application epoch");
        if ((mode == CommitMode.CORRECTION || mode == CommitMode.RELOCATION) != (correction != null)) {
            throw new IllegalArgumentException("only a correction carries a position packet");
        }
        if (!Double.isFinite(installedWidth) || !Double.isFinite(installedHeight)
                || !(installedWidth > 0.0D) || !(installedHeight > 0.0D)) {
            throw new IllegalArgumentException(
                    "installed body extents must be finite and positive: "
                            + installedWidth + "x" + installedHeight
            );
        }
        if (representation != BodyRepresentation.ofAxis(installedUp)) {
            throw new IllegalArgumentException(
                    "representation does not match installed collision axis"
            );
        }
        if (installedUp != null && (!installedUp.isFinite()
                || Math.abs(installedUp.lengthSquared() - 1.0D) > 1.0E-10D)) {
            throw new IllegalArgumentException("installed axis must be finite and unit length");
        }
        requireFrameFinite("gravityReferenceFrame", gravityReferenceFrame);
        if (correction != null && !isFinite(correction)) {
            throw new IllegalArgumentException("non-finite correction");
        }
    }

    private static boolean isFinite(ClientboundPlayerPositionPacket correction) {
        return Double.isFinite(correction.getX())
                && Double.isFinite(correction.getY())
                && Double.isFinite(correction.getZ())
                && Float.isFinite(correction.getYRot())
                && Float.isFinite(correction.getXRot());
    }

    /**
     * Same snapshot, different authorized use. The representation and transferred geometry facts are preserved by the canonical constructor.
     */
    public ClientboundPlayerBodyCommitPayload withMode(CommitMode nextMode) {
        Objects.requireNonNull(nextMode, "nextMode");
        return new ClientboundPlayerBodyCommitPayload(
                assignment, applicationEpoch, application, pose,
                installedWidth, installedHeight, installedUp,
                gravityReferenceFrame,
                representation, nextMode, correction);
    }

    /**
     * Authoritative snapshot of the player's committed representation.
     *
     * <p>Capture installed geometry, committed application and the complete
     * environmental reference together. The existing application epoch orders
     * geometry/reference changes too; an observer capture cannot consume the
     * owning player's publication notification.</p>
     */
    public static ClientboundPlayerBodyCommitPayload capture(
            ServerPlayer player, ClientboundPlayerPositionPacket correction) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        Vec3d installedUp = component.operationState().installedCollisionUp();
        GravityFrame gravityReferenceFrame =
                component.state().hasActiveGravityReference()
                        ? cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                        .authoritativeFrame(player)
                        : null;
        InstalledBodySnapshot installed = InstalledBodySnapshot.capture(player);
        cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff
                .preparePublication(player, installed, gravityReferenceFrame);
        return new ClientboundPlayerBodyCommitPayload(SyncGravityStatePayload.from(player),
                component.state().applicationEpoch(), component.state().committedApplication(),
                installed.pose(), installed.width(), installed.height(), installedUp,
                gravityReferenceFrame,
                installed.representation(),
                correction == null ? CommitMode.OBSERVER : CommitMode.CORRECTION, correction);
    }

    private static void encode(FriendlyByteBuf b, ClientboundPlayerBodyCommitPayload p) {
        SyncGravityStatePayload.STREAM_CODEC.encode(b, p.assignment);
        b.writeVarLong(p.applicationEpoch);
        writeVector(b, p.application.appliedState().down());
        b.writeDouble(p.application.appliedState().strength());
        b.writeVarInt(p.application.authoritativeSuppression().networkId());
        b.writeEnum(p.application.plan().kind());
        b.writeEnum(p.application.plan().accelerationMode());
        b.writeEnum(p.pose);
        b.writeDouble(p.installedWidth);
        b.writeDouble(p.installedHeight);
        b.writeBoolean(p.installedUp != null);
        if (p.installedUp != null) writeVector(b, p.installedUp);
        writeFrame(b, p.gravityReferenceFrame);
        b.writeEnum(p.representation);
        b.writeEnum(p.mode);
        b.writeBoolean(p.correction != null);
        if (p.correction != null) ClientboundPlayerPositionPacket.STREAM_CODEC.encode(b, p.correction);
    }

    private static ClientboundPlayerBodyCommitPayload decode(FriendlyByteBuf b) {
        var assignment = SyncGravityStatePayload.STREAM_CODEC.decode(b);
        long epoch = b.readVarLong();
        var state = new GravityState(readVector(b), b.readDouble());
        var suppression = GravitySuppressionReason.fromNetworkId(b.readVarInt());
        var plan = new GravityApplicationPlan(b.readEnum(GravityApplicationPlan.Kind.class),
                b.readEnum(GravityAccelerationMode.class));
        var pose = b.readEnum(Pose.class);
        double installedWidth = b.readDouble();
        double installedHeight = b.readDouble();
        Vec3d installedUp = b.readBoolean() ? readVector(b) : null;
        GravityFrame gravityReferenceFrame = readFrame(b);
        var representation = b.readEnum(BodyRepresentation.class);
        var mode = b.readEnum(CommitMode.class);
        var correction = b.readBoolean() ? ClientboundPlayerPositionPacket.STREAM_CODEC.decode(b) : null;
        return new ClientboundPlayerBodyCommitPayload(assignment, epoch,
                new CommittedGravityApplication(state, suppression, plan), pose,
                installedWidth, installedHeight, installedUp, gravityReferenceFrame,
                representation, mode, correction);
    }

    private static void writeFrame(FriendlyByteBuf b, GravityFrame frame) {
        b.writeBoolean(frame != null);
        if (frame == null) return;
        writeVector(b, frame.samplePoint());
        writeVector(b, frame.left());
        writeVector(b, frame.up());
        writeVector(b, frame.forward());
        b.writeDouble(frame.strength());
    }

    private static GravityFrame readFrame(FriendlyByteBuf b) {
        if (!b.readBoolean()) return null;
        Vec3d sample = readVector(b);
        Vec3d left = readVector(b);
        Vec3d up = readVector(b);
        Vec3d forward = readVector(b);
        double strength = b.readDouble();
        if (!Double.isFinite(strength) || strength < 0.0D) {
            throw new IllegalArgumentException("invalid frame strength");
        }
        return new GravityFrame(
                sample,
                new OrthonormalFrame3d(
                        vector(left), vector(up), vector(forward)),
                strength);
    }

    private static void requireFrameFinite(String name, GravityFrame frame) {
        if (frame == null) return;
        if (!Double.isFinite(frame.strength()) || frame.strength() < 0.0D
                || !frame.samplePoint().isFinite()
                || !frame.left().isFinite()
                || !frame.up().isFinite()
                || !frame.forward().isFinite()) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }

    private static Vec3d vector(Vec3d v) { return new Vec3d(v.x(), v.y(), v.z()); }
    private static void writeVector(FriendlyByteBuf b, Vec3d v) {
        b.writeDouble(v.x()); b.writeDouble(v.y()); b.writeDouble(v.z());
    }
    private static Vec3d readVector(FriendlyByteBuf b) {
        Vec3d v = new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
        if (!Double.isFinite(v.x()) || !Double.isFinite(v.y()) || !Double.isFinite(v.z())) {
            throw new IllegalArgumentException("non-finite vector");
        }
        return v;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
