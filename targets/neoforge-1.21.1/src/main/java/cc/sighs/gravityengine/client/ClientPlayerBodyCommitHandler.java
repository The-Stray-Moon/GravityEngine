package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.integration.geometry.BodyCommitTransaction;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.integration.geometry.InstalledBodySnapshot;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.GravityEntityState.RemoteApplicationAcceptance;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload.CommitMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.WeakHashMap;

/** MAIN-thread admission and installation of complete body transactions.
 * No application/representation retry queue; native pairing owns entity lifetime.
 * Accepted snapshots below are transport fingerprints, never live physical state. */
public final class ClientPlayerBodyCommitHandler {
    private static final Map<Player, ClientboundPlayerBodyCommitPayload> ACCEPTED = new WeakHashMap<>();

    private ClientPlayerBodyCommitHandler() {}

    public static void handle(ClientboundPlayerBodyCommitPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("body commit requires the client thread");
        var identity = payload.assignment();
        if (mc.level == null || mc.getConnection() == null
                || !mc.level.dimension().location().equals(identity.dimensionId())) return;
        Entity entity = mc.level.getEntity(identity.entityId());
        if (!(entity instanceof Player player) || player.isRemoved()
                || !player.getUUID().equals(identity.entityUuid())) return;
        if ((payload.mode() == CommitMode.OBSERVER) == (player == mc.player)) return;

        try (var barrier = GravityApplicationBarrier.hold(player)) {
            if (!admit(player, payload)) return;
            var predictedVelocity = player.getDeltaMovement();
            ClientGravityFrameSampler.beforePhysicalCommit(player);
            try (var transaction = BodyCommitTransaction.begin(player)) {
                install(player, payload);
                if (payload.correction() != null) {
                    // The original native position/history update and ACK follow the
                    // complete physical install, under the same handoff barrier.
                    mc.getConnection().handleMovePlayer(payload.correction());
                    if (payload.mode() == CommitMode.RELOCATION) player.setDeltaMovement(predictedVelocity);
                }
                // Native position invalidation runs first. Publish the complete
                // received frame after the final body and anchor are installed.
                var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().operationState();
                var reference = payload.gravityReferenceFrame();
                if (reference == null) runtime.clearFrameContinuity();
                else runtime.acceptFrameEndpoint(reference, player.level().getGameTime());
            }
            ClientGravityFrameSampler.afterPhysicalCommit(player);
            ACCEPTED.put(player, payload);
        } catch (RuntimeException failure) {
            // Callback/ABI failures cannot be recovered by continuing with a partial body.
            // Stop the connection rather than silently acknowledging a failed transaction.
            mc.getConnection().getConnection().disconnect(
                    Component.literal("GravityEngine body transaction failed: " + failure.getMessage()));
            throw failure;
        }
        if (GravityDebugLog.shouldLog(player)) GravityDebugLog.log(player, "body-commit-applied",
                "mode=%s epoch=%s representation=%s plan=%s position=%s velocity=%s",
                payload.mode(), payload.applicationEpoch(), payload.representation(),
                payload.application().plan().kind(), GravityDebugLog.vec(player.position()),
                GravityDebugLog.vec(player.getDeltaMovement()));
    }

    private static boolean admit(Player player, ClientboundPlayerBodyCommitPayload payload) {
        var state = GravityEntityAccess.cast(player).gravityengine$gravityComponent().state();
        var acceptance = state.classifyRemoteApplication(payload.application(), payload.applicationEpoch());
        if (acceptance == RemoteApplicationAcceptance.CONFLICT) {
            throw new IllegalStateException("conflicting application at epoch " + payload.applicationEpoch());
        }
        if (payload.applicationEpoch() < state.applicationEpoch()) return false;
        var previous = ACCEPTED.get(player);
        if (previous == null) return true;
        if (payload.applicationEpoch() < previous.applicationEpoch()) return false;
        if (payload.applicationEpoch() == previous.applicationEpoch()) {
            if (!samePhysicalFacts(previous, payload)) {
                throw new IllegalStateException("conflicting body/reference at epoch " + payload.applicationEpoch());
            }
            // Same physical tuple may carry new assignment/evidence, or a native correction.
            if (payload.correction() == null
                    && payload.assignment().assignmentSyncRevision() <= previous.assignment().assignmentSyncRevision()
                    && payload.assignment().influenceRevision() <= previous.assignment().influenceRevision()) return false;
        }
        return true;
    }

    private static boolean samePhysicalFacts(ClientboundPlayerBodyCommitPayload a,
            ClientboundPlayerBodyCommitPayload b) {
        return a.application().equals(b.application()) && a.representation() == b.representation()
                && a.pose() == b.pose() && a.installedWidth() == b.installedWidth()
                && a.installedHeight() == b.installedHeight()
                && sameAxis(a.installedUp(), b.installedUp())
                && sameReference(a.gravityReferenceFrame(), b.gravityReferenceFrame());
    }

    private static boolean sameAxis(cc.sighs.gravityengine.api.math.Vec3d a, cc.sighs.gravityengine.api.math.Vec3d b) {
        return a == b || a != null && b != null && a.distanceSquared(b) <= 1.0E-20;
    }

    private static boolean sameReference(GravityFrame a, GravityFrame b) {
        return a == b || a != null && b != null && a.orientation().equals(b.orientation())
                && a.strength() == b.strength();
    }

    private static void install(Player player, ClientboundPlayerBodyCommitPayload payload) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        var runtime = component.operationState();
        if (runtime.isInMove() || runtime.isApplyingGeometry()) {
            throw new IllegalStateException("body transaction during a physical operation");
        }
        var installed = InstalledBodySnapshot.capture(player);
        var installedUp = payload.installedUp();
        boolean changed = installed.representationFactsDifferFrom(payload.representation(), payload.pose(),
                payload.installedWidth(), payload.installedHeight(), installedUp);
        boolean dimensionsChanged = installed.pose() != payload.pose()
                || Math.abs(installed.width() - payload.installedWidth()) > 1.0E-6
                || Math.abs(installed.height() - payload.installedHeight()) > 1.0E-6;
        if (!dimensionsChanged) GravityEntityAccess.cast(player).gravityengine$discardDimensionProposal();
        var anchor = player.position();
        // Support/legality and any anchor adjustment were decided by the
        // server transaction. The replica never solves a different body here.
        // Protocol mode does not classify displacement. Same-body corrections
        // leave support available to EntityPositionIntegration's native write
        // classifier (unchanged / soft revalidation / hard discontinuity).
        if (changed) runtime.clearMovementTransientState();
        if (changed) {
            try (var geometry = runtime.openGeometryMutation()) {
                if (dimensionsChanged) {
                    // setPose synchronously invokes refreshDimensions in .249.
                    // A separately received native pose may already match while
                    // its dimension installation was deferred.
                    if (player.getPose() == payload.pose()) player.refreshDimensions();
                    else player.setPose(payload.pose());
                    var dimensions = GravityEntityGeometry.dimensions(player);
                    if (Math.abs(dimensions.width() - payload.installedWidth()) > 1.0E-6
                            || Math.abs(dimensions.height() - payload.installedHeight()) > 1.0E-6) {
                        throw new IllegalStateException("remote dimensions disagree with platform Size result");
                    }
                }
                if (payload.representation() == BodyRepresentation.EXACT_BODY) {
                    GravityEntityGeometry.installFromPositionAnchor(player, installedUp, anchor);
                } else {
                    GravityEntityGeometry.commitVanillaBody(player, anchor,
                            GravityEntityGeometry.dimensions(player).makeBoundingBox(anchor));
                    if (installedUp != null) runtime.setInstalledCollisionAxis(installedUp);
                }
                player.fallDistance = 0;
                player.setOnGround(false);
            }
        }
        ClientGravitySyncService.applySnapshot(player, payload.assignment());
        component.state().acceptRemoteApplication(payload.application(), payload.applicationEpoch());
        component.state().takePending();
    }

    public static void onEntityRemoved(Entity entity) { ACCEPTED.remove(entity); }
    public static void clearDimension(ResourceLocation dimension) {
        ACCEPTED.keySet().removeIf(player -> player.level().dimension().location().equals(dimension));
    }
    public static void clearAll() { ACCEPTED.clear(); }
}
