package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.geometry.GravityGeometryTransitionPlanner;
import cc.sighs.gravityengine.gravity.geometry.PositionAuthorityPolicy;
import cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.PlayerPhysicalLoadBootstrap;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.BodyTransitionDecision;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The one server-side body-ownership transaction.
 *
 * <p>Only the completed connection tick (or the single physical load/
 * replacement bootstrap owner using {@link #withAuthority}) may change a
 * player's new body. Routine preparation keeps the accepted anchor;
 * only lifecycle bootstrap may require a relocation. The transaction separates
 * concepts that are never substituted for one another:</p>
 *
 * <ul>
 *   <li>authoritative application synchronization - a published
 *       application/assignment snapshot, or a receiver's reconciliation
 *       request;</li>
 *   <li>collision/movement routing - which path owns the operation, which is an
 *       ownership decision and never an installed collider;</li>
 *   <li>physical body/collider discontinuity - the installed
 *       {@link BodyRepresentation} actually changed;</li>
 *   <li>player kinematic state - the authoritative position anchor actually
 *       moved.</li>
 * </ul>
 *
 * <p>A synchronization request ({@link #requestSynchronization}) is therefore
 * never a physical event: it re-publishes authoritative state and never
 * teleports, never zeroes momentum and never rewrites the collider. Only an
 * observed position relocation reaches the platform correction path, and even
 * then the world-space velocity is not owned by the representation change.</p>
 */
public final class PlayerBodyHandoff {
    private record Authority(ServerPlayer player, boolean externalAnchor) {}
    private static final ThreadLocal<Authority> AUTHORITY = new ThreadLocal<>();

    /** Routine preparation owns geometry only; bootstrap retains its lifecycle authority. */
    public static boolean preservesPosition(Entity entity) {
        var authority = AUTHORITY.get();
        return authority != null && authority.player() == entity && authority.externalAnchor();
    }
    /** Producer provenance for the native teleport send, scoped to this call only. */
    private static final ThreadLocal<ServerPlayer> RELOCATION = new ThreadLocal<>();

    public static boolean isGeometryRelocation(ServerPlayer player) { return RELOCATION.get() == player; }

    public static void geometryRelocation(ServerPlayer player, Runnable send) {
        var previous = RELOCATION.get();
        RELOCATION.set(player);
        try { send.run(); }
        finally { if (previous == null) RELOCATION.remove(); else RELOCATION.set(previous); }
    }
    private static final Map<ServerPlayer, Integer> LAST_SYNC_REQUEST_TICK =
            new WeakHashMap<>();
    private static final Set<ServerPlayer> SYNC_REQUESTED =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** Authoritative application changed before this connection tick's snapshot. */
    private static final Set<ServerPlayer> APPLICATION_CHANGED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Last captured publication facts, not another installed-body owner. Weak entity
     * keys scope ordering to the native player lifetime. No position history. */
    private record PublishedBody(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            InstalledBodySnapshot installed, cc.sighs.gravityengine.gravity.GravityFrame reference, long epoch) {}
    private static final Map<ServerPlayer, PublishedBody> PUBLISHED = new WeakHashMap<>();

    public static void preparePublication(ServerPlayer player, InstalledBodySnapshot installed,
            cc.sighs.gravityengine.gravity.GravityFrame reference) {
        var state = GravityEntityAccess.cast(player).gravityengine$gravityComponent().state();
        var previous = PUBLISHED.get(player);
        if (previous == null || physicalFactsChanged(player, previous, installed, reference)) {
            if (previous != null && state.applicationEpoch() <= previous.epoch()) {
                state.advanceBodyPublicationEpoch();
            }
            // A tracking/lifecycle capture can happen before the connection tick.
            // Capturing an observer packet must not consume the owner's publication.
            APPLICATION_CHANGED.add(player);
        }
        PUBLISHED.put(player, new PublishedBody(player.level().dimension(), installed, reference,
                state.applicationEpoch()));
    }

    private static boolean physicalFactsChanged(ServerPlayer player, PublishedBody previous,
            InstalledBodySnapshot installed, cc.sighs.gravityengine.gravity.GravityFrame reference) {
        return !previous.dimension().equals(player.level().dimension())
                || installed.representationFactsDifferFrom(previous.installed().representation(),
                        previous.installed().pose(), previous.installed().width(), previous.installed().height(),
                        previous.installed().installedUp())
                || !sameReference(previous.reference(), reference);
    }

    private static boolean sameReference(cc.sighs.gravityengine.gravity.GravityFrame a,
            cc.sighs.gravityengine.gravity.GravityFrame b) {
        // Sampling location is not physical orientation or acceleration identity.
        return a == b || a != null && b != null
                && a.orientation().equals(b.orientation()) && a.strength() == b.strength();
    }

    private static boolean unpublishedPhysicalChange(ServerPlayer player) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        var previous = PUBLISHED.get(player);
        var reference = component.state().hasActiveGravityReference()
                ? cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(player) : null;
        return previous == null || physicalFactsChanged(player, previous,
                InstalledBodySnapshot.capture(player), reference);
    }

    private PlayerBodyHandoff() {}

    /**
     * Server-thread request to republish the complete transaction, rate limited.
     * Internal lifecycle callers only; there is no client reconciliation packet.
     * It grants no authorization to move or reset the player.
     */
    public static void requestSynchronization(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        int now = player.tickCount;
        Integer last = LAST_SYNC_REQUEST_TICK.get(player);
        if (last != null && now >= last && now - last < 20) return;
        LAST_SYNC_REQUEST_TICK.put(player, now);
        SYNC_REQUESTED.add(player);
    }

    /**
     * Marks an already-committed authoritative player application revision for
     * publication to the owning client. This is not a second application
     * decision: the epoch and application were already committed by the server
     * owner before this notification.
     */
    public static void markApplicationChanged(ServerPlayer player) {
        APPLICATION_CHANGED.add(Objects.requireNonNull(player, "player"));
    }

    public static boolean mayChangeBody(Entity entity) {
        if (!(entity instanceof Player)) return true;
        // Client geometry is installed only by the committed server payload,
        // never by the desired-application planner or a local recovery query.
        if (entity.level().isClientSide()) return false;
        if (!(entity instanceof ServerPlayer player)) return true; // non-networked server actors/test fixtures
        if (player.connection == null || player instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            return true;
        }
        return AUTHORITY.get() != null && AUTHORITY.get().player() == player;
    }

    /**
     * Runs the designated load/replacement transaction with lifecycle placement
     * authority. Routine preparation uses its separate external-anchor policy
     * at the connection-tick boundary; packets never acquire this authority.
     */
    public static <T> T withAuthority(
            ServerPlayer player,
            java.util.function.Supplier<T> action
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");

        var previous = AUTHORITY.get();
        AUTHORITY.set(new Authority(player, false));
        try {
            return action.get();
        } finally {
            if (previous == null) AUTHORITY.remove();
            else AUTHORITY.set(previous);
        }
    }

    /** Authoritative body/application/kinematic snapshot used by the decision. */
    public static BodyHandoffState captureBodyState(ServerPlayer player) {
        return BodyHandoffState.capture(player);
    }

    /**
     * The one transition decision. Exposed so tests can assert the semantics
     * on real observed states instead of on the action that followed.
     *
     * <p>Each input is read from exactly one state dimension: the installed
     * geometry comparison selects a representation transition, the position
     * anchor comparison selects relocation, and application/routing changes
     * (with a receiver's request) select synchronization. A pure application or
     * routing change therefore never reinstalls the collider.</p>
     */
    public static BodyTransitionDecision transitionDecision(
            BodyHandoffState before,
            BodyHandoffState after,
            boolean synchronizationRequested
    ) {
        return BodyTransitionDecision.decide(
                after.positionAnchorDiffersFrom(before),
                after.installedGeometryDiffersFrom(before),
                synchronizationRequested
                        || after.applicationDiffersFrom(before)
                        || after.routingDiffersFrom(before)
        );
    }

    public static void afterConnectionTick(ServerPlayer player, boolean awaitingTeleport) {
        if (awaitingTeleport || player.isRemoved() || player.isDeadOrDying()) return;
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        if (component.operationState().isInMove() || component.operationState().isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(player)) return;

        boolean bootstrapPlacement = component.state().applicationBootstrapPending();
        BodyHandoffState before = BodyHandoffState.capture(player);
        var previous = AUTHORITY.get();
        AUTHORITY.set(new Authority(player, !bootstrapPlacement));
        try (var transaction = BodyCommitTransaction.beginAuthoritative(player)) {
            GravityEntityAccess.cast(player).gravityengine$flushPlayerDimensions();
            if (component.state().applicationBootstrapPending()) {
                PlayerPhysicalLoadBootstrap.bootstrapLoadedPlayer(player);
            } else {
                GravityApplicationCoordinator.updateAuthoritativeSuppression(player);
                var result = GravityApplicationCoordinator.updateBody(player);
                if (result.status() == GravityApplicationCoordinator.TransitionStatus.COMMITTED
                        || result.status() == GravityApplicationCoordinator.TransitionStatus.UNCHANGED) {
                    component.state().takePending();
                }
            }
            prepareCommittedFrame(player);
        } finally {
            if (previous == null) AUTHORITY.remove(); else AUTHORITY.set(previous);
        }
        BodyHandoffState after = BodyHandoffState.capture(player);
        /*
         * A receiver's reconciliation request and an authoritative
         * application/routing change are separate signals and are never
         * substituted for one another: all changed committed physical facts must reach the receiver together.
         */
        boolean reconciliationRequested = SYNC_REQUESTED.remove(player) | unpublishedPhysicalChange(player);
        boolean applicationChanged =
                APPLICATION_CHANGED.remove(player)
                        || after.applicationDiffersFrom(before);
        boolean routingChanged = after.routingDiffersFrom(before);
        boolean installedGeometryChanged = after.installedGeometryDiffersFrom(before);
        boolean relocationRequired = after.positionAnchorDiffersFrom(before);
        BodyTransitionDecision decision =
                transitionDecision(
                        before, after,
                        reconciliationRequested || applicationChanged);

        if (GravityDebugLog.shouldLog(player)) {
            GravityDebugLog.log(player, "body-transition",
                    "decision=%s "
                            + "applicationPlanBefore=%s applicationPlanAfter=%s "
                            + "committedCollisionRouteBefore=%s committedCollisionRouteAfter=%s "
                            + "representationBefore=%s representationAfter=%s "
                            + "bodyShapeRevisionBefore=%s bodyShapeRevisionAfter=%s "
                            + "geometryChanged=%s applicationChanged=%s routingChanged=%s "
                            + "resyncRequested=%s relocationRequired=%s "
                            + "positionBefore=%s positionAfter=%s "
                            + "velocityBefore=%s velocityAfter=%s epochBefore=%s epochAfter=%s "
                            + "poseBefore=%s poseAfter=%s "
                            + "installedWidthBefore=%s installedHeightBefore=%s "
                            + "installedWidthAfter=%s installedHeightAfter=%s",
                    decision,
                    before.application().plan(),
                    after.application().plan(),
                    before.committedCollisionRoute(),
                    after.committedCollisionRoute(),
                    before.installedBody().representation(),
                    after.installedBody().representation(),
                    before.installedBody().bodyShapeRevision(),
                    after.installedBody().bodyShapeRevision(),
                    installedGeometryChanged,
                    applicationChanged,
                    routingChanged,
                    reconciliationRequested,
                    relocationRequired,
                    GravityDebugLog.vec(before.anchor().positionAnchor()),
                    GravityDebugLog.vec(after.anchor().positionAnchor()),
                    GravityDebugLog.vec(before.anchor().velocityWorld()),
                    GravityDebugLog.vec(after.anchor().velocityWorld()),
                    before.application().epoch(),
                    after.application().epoch(),
                    before.installedBody().pose(),
                    after.installedBody().pose(),
                    before.installedBody().width(),
                    before.installedBody().height(),
                    after.installedBody().width(),
                    after.installedBody().height());
        }

        switch (decision) {
            case NONE -> { }
            case SYNC_ONLY -> {
                if (reconciliationRequested || applicationChanged || routingChanged) {
                    publishAuthoritativeState(
                            player, after,
                            reconciliationRequested, applicationChanged, routingChanged);
                }
            }
            case GEOMETRY_REPRESENTATION_CHANGE ->
                    // The committed collider changed, so the receiver must
                    // reconcile its representation at its own anchor.
                    publishAuthoritativeState(player, after, true, false, false);
            case PHYSICAL_RELOCATION -> {
                if (!bootstrapPlacement) throw new IllegalStateException("routine body preparation moved the accepted anchor");
                relocateBootstrapBody(player, before, after);
            }
        }
    }

    /** Same-body axis evolution belongs to the completed server tick, too.
     * One captured scene proves current support and the candidate occupancy;
     * movement/packet operations only consume the resulting installed axis. */
    private static void prepareCommittedFrame(ServerPlayer player) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        var runtime = component.operationState();
        if (!component.state().appliedPlan().usesCustomBody()
                || runtime.installedCollisionUp() == null) return;
        var proposed = GravityFrame.fromState(component.state().appliedState(),
                MinecraftMathAdapter.toVec3d(GravityEntityGeometry.proxyCenter(player)),
                runtime.lastCompletedFrame());
        var installed = GravityGeometryTransitionService.installedFallback(runtime);
        if (!GravityGeometryTransitionPlanner.shouldUpdateCollisionGeometryFrame(installed, proposed)) {
            runtime.acceptFrameEndpoint(proposed,
                    player.level().getGameTime());
            return;
        }
        try {
            var capture = GravityCurrentSupportQuery.capture(player);
            var support = capture.stableSupport(player).orElse(null);
            GravityGeometryTransitionService.prepareOperationFrame(player, proposed,
                    capture.scene(), PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR,
                    capture.context(), support);
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            // No candidate was proven. Retain the installed body and retry at
            // the next legal handoff; unavailable evidence never means clear.
        }
        // Failure to rotate geometry does not change the environmental reference.
        runtime.acceptFrameEndpoint(proposed, player.level().getGameTime());
    }

    /** Publish one complete body transaction. The receiver installs only changed
     * physical facts at its own anchor; publication alone never authorizes relocation. */
    private static void publishAuthoritativeState(
            ServerPlayer player,
            BodyHandoffState state,
            boolean reconciliationRequested,
            boolean applicationChanged,
            boolean routingChanged
    ) {
        var mode = ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION;
        var snapshot = ClientboundPlayerBodyCommitPayload.capture(player, null);
        PacketDistributor.sendToPlayer(player, snapshot.withMode(mode));
        PacketDistributor.sendToPlayersTrackingEntity(player, snapshot);
        APPLICATION_CHANGED.remove(player);
        if (GravityDebugLog.shouldLog(player)) {
            GravityDebugLog.log(player, "body-transition-publish",
                    "mode=%s representation=%s reconciliationRequested=%s "
                            + "applicationChanged=%s routingChanged=%s "
                            + "epoch=%s position=%s velocity=%s",
                    mode,
                    state.installedBody().representation(),
                    reconciliationRequested,
                    applicationChanged,
                    routingChanged,
                    snapshot.applicationEpoch(),
                    GravityDebugLog.vec(player.position()),
                    GravityDebugLog.vec(player.getDeltaMovement()));
        }
    }

    /**
     * Bootstrap placement transport. It keeps the world-space velocity the
     * authoritative geometry transition chose and uses Vanilla's own
     * relative-argument teleport, so every axis the relocation does not change
     * keeps the client's momentum instead of being zeroed by an absolute
     * same-value teleport.
     */
    private static void relocateBootstrapBody(
            ServerPlayer player,
            BodyHandoffState before,
            BodyHandoffState state
    ) {
        Vec3 target = state.anchor().positionAnchor();
        if (player.connection == null) return;
        Set<RelativeMovement> relative = EnumSet.noneOf(RelativeMovement.class);
        // Vanilla derives a relative axis from (target - current server
        // position) and the authoritative transition has already installed
        // that target, so only an axis the relocation did NOT change may be
        // relative. A moved axis stays absolute so the receiver converges.
        if (axisUnchanged(before.anchor().positionAnchor().x, target.x)) relative.add(RelativeMovement.X);
        if (axisUnchanged(before.anchor().positionAnchor().y, target.y)) relative.add(RelativeMovement.Y);
        if (axisUnchanged(before.anchor().positionAnchor().z, target.z)) relative.add(RelativeMovement.Z);
        // The relocation owns position only. Rotation stays with the receiving
        // side instead of being snapped by this correction.
        relative.add(RelativeMovement.Y_ROT);
        relative.add(RelativeMovement.X_ROT);

        if (GravityDebugLog.shouldLog(player)) {
            GravityDebugLog.log(player, "body-transition-relocate",
                    "representation=%s epoch=%s from=%s target=%s velocity=%s relativeAxes=%s",
                    state.installedBody().representation(),
                    state.application().epoch(),
                    GravityDebugLog.vec(before.anchor().positionAnchor()),
                    GravityDebugLog.vec(target),
                    GravityDebugLog.vec(player.getDeltaMovement()),
                    relative);
        }
        // Native teleport owns awaitingPosition, ID, timeout/resend and
        // acknowledgement. Its send seam attaches the committed body to this
        // SAME correction. No velocity write happens here.
        geometryRelocation(player, () -> player.connection.teleport(target.x, target.y, target.z,
                player.getYRot(), player.getXRot(), relative));
        PacketDistributor.sendToPlayersTrackingEntity(player,
                ClientboundPlayerBodyCommitPayload.capture(player, null));
        APPLICATION_CHANGED.remove(player);
    }

    private static boolean axisUnchanged(double current, double target) {
        // Even a sub-micrometre support adjustment must reach the replica.
        // Treating it as relative would send zero after the server commits P,
        // letting repeated rotations accumulate an uncorrected support gap.
        return current == target;
    }
}
