package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.assignment.GravityAssignmentService;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent.PendingTarget;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent.SnapshotAcceptance;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.policy.GravityApplicationPlanner;
import cc.sighs.gravityengine.gravity.policy.GravityEntityCapabilitiesPolicy;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import cc.sighs.gravityengine.network.GravitySyncService;
import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Authoritative gravity-application transition coordinator.
 *
 * <p>Owns the one application-selection entry: entity-kind capabilities and
 * the immutable entity-state snapshot feed the pure planner, the resulting
 * geometry transition is executed exactly once, and any transition that cannot
 * run under the current movement/geometry scope is deferred rather than
 * partially applied.</p>
 */
public final class GravityApplicationCoordinator {
    private GravityApplicationCoordinator() {}

    /** Server-side consolidated influence reconciliation and synchronization. */
    public static void reconcileServerInfluence(ServerPlayer player) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        long before = component.influenceRevision();
        updateAuthoritativeSuppression(player);
        updateBody(player);
        if (component.influenceRevision() != before) {
            GravitySyncService.syncTracking(player);
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum TransitionStatus { UNCHANGED, COMMITTED, DEFERRED, FAILED }

    /** Minimal externally meaningful application transition outcome. */
    public record ApplicationTransitionResult(
            TransitionStatus status,
            boolean assignmentAccepted,
            boolean geometryChanged
    ) {}

    /** Client snapshot acceptance plus the one derived application transition. */
    public record RemoteSnapshotResult(
            boolean assignmentAccepted,
            boolean influenceAccepted,
            ApplicationTransitionResult application
    ) {}

    /** Pure geometry transition plan derived from previous and desired body mode. */
    enum ApplicationGeometryPlan {
        NONE,
        VANILLA_TO_CUSTOM,
        CUSTOM_TO_VANILLA
    }

    // -----------------------------------------------------------------
    // Authority updates
    // -----------------------------------------------------------------

    public static ApplicationTransitionResult applyDirectAssignment(
            Entity entity, GravityState next
    ) {
        return applyAssignment(
                entity, next, GravityAuthorityMode.DIRECT, false, false);
    }

    public static ApplicationTransitionResult applyFieldAssignment(
            Entity entity,
            GravityState next,
            boolean fieldPresent
    ) {
        return applyAssignment(
                entity, next, GravityAuthorityMode.FIELD, fieldPresent, true);
    }

    private static ApplicationTransitionResult applyAssignment(
            Entity entity,
            GravityState next,
            GravityAuthorityMode authority,
            boolean fieldPresent,
            boolean rejectWhileDirect
    ) {
        var component = component(entity);
        if (rejectWhileDirect && component.assignedAuthority() == GravityAuthorityMode.DIRECT) {
            return unchanged(false);
        }
        boolean accepted = component.setAssigned(next, authority, fieldPresent);
        if (!accepted) {
            return unchanged(false);
        }
        return buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                true
        );
    }

    public static RemoteSnapshotResult applyRemoteSnapshot(
            Entity entity,
            GravityState assignment,
            GravityAuthorityMode authority,
            boolean fieldPresent,
            long assignmentRevision,
            GravitySuppressionReason suppression,
            long suppressionRevision
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(suppression, "suppression");

        if (assignmentRevision < 0L) {
            throw new IllegalArgumentException(
                    "assignmentRevision must be non-negative: " + assignmentRevision);
        }
        if (suppressionRevision < 0L) {
            throw new IllegalArgumentException(
                    "suppressionRevision must be non-negative: " + suppressionRevision);
        }

        var component = component(entity);
        CommittedGravityApplication previousApplication = component.committedApplication();

        SnapshotAcceptance assignmentAcceptance = component.acceptRemoteAssignment(
                assignment, authority, fieldPresent, assignmentRevision);
        SnapshotAcceptance suppressionAcceptance = component.acceptRemoteSuppression(
                suppression, suppressionRevision);

        if (assignmentAcceptance == SnapshotAcceptance.STALE
                && suppressionAcceptance == SnapshotAcceptance.STALE) {
            GravityApplicationPlan desired = desiredPlan(entity, component);
            if (desired.equals(previousApplication.plan())
                    && component.assignedState().sameSyncData(previousApplication.appliedState())) {
                return new RemoteSnapshotResult(
                        false,
                        false,
                        new ApplicationTransitionResult(
                                TransitionStatus.UNCHANGED, false, false)
                );
            }
        }

        ApplicationTransitionResult application = buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                assignmentAcceptance == SnapshotAcceptance.ACCEPTED
        );

        return new RemoteSnapshotResult(
                assignmentAcceptance == SnapshotAcceptance.ACCEPTED,
                suppressionAcceptance == SnapshotAcceptance.ACCEPTED,
                application
        );
    }

    public static ApplicationTransitionResult updateAuthoritativeSuppression(Entity entity) {
        var component = component(entity);
        GravitySuppressionReason next =
                GravityInfluencePolicy.deriveAuthoritativeSuppression(entity);
        if (!component.setAuthoritativeSuppression(next)) {
            return unchanged(false);
        }
        return buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                true
        );
    }

    public static ApplicationTransitionResult updateLocalAbilitySuppression(Player player) {
        var component = component(player);
        GravitySuppressionReason next = player.isCreative() && player.getAbilities().flying
                ? GravitySuppressionReason.CREATIVE_FLIGHT
                : GravitySuppressionReason.NONE;
        if (next == component.localClientSuppression()) {
            return unchanged(false);
        }
        component.setLocalClientSuppression(next);
        return buildAndApply(
                player,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                false
        );
    }

    public static ApplicationTransitionResult updateBody(Entity entity) {
        var component = component(entity);
        return buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                false
        );
    }

    /**
     * Load bootstrap application reconstruction for an entity whose durable
     * assignment was already restored by the persistence layer.
     *
     * <p>This rebuilds derived application authority from the already restored
     * durable assignment, suppression and current capabilities. It MUST NOT
     * call {@code setAssigned()} and MUST NOT depend on
     * {@code GravityAssignmentService.evaluate(...).changed()}: load
     * intentionally discarded the previous committed runtime application and
     * geometry, so an unchanged field value still requires an unconditional
     * re-derivation.</p>
     */
    public static ApplicationTransitionResult bootstrapLoadedApplication(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        EntityGravityComponent component = component(entity);
        return buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                false
        );
    }

    /**
     * Refreshes load-time authoritative inputs without publishing any derived
     * application or geometry.
     */
    public static void refreshLoadedInputs(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        EntityGravityComponent component = component(entity);

        // A retry always rebuilds its derived target from current authoritative
        // inputs. Never allow a deferred target from an earlier attempt to commit
        // after the fresh load snapshot.
        component.takePending();

        if (component.assignedAuthority() == GravityAuthorityMode.FIELD) {
            GravityAssignmentService.AssignmentResult field =
                    GravityAssignmentService.evaluate(entity);
            component.setAssigned(
                    field.resolved(), GravityAuthorityMode.FIELD, field.fieldPresent());
        }

        component.setAuthoritativeSuppression(
                GravityInfluencePolicy.deriveAuthoritativeSuppression(entity));
    }

    public static void applyPending(Entity entity) {
        var component = component(entity);
        var runtime = component.runtime();
        if (runtime.isInMove()
                || runtime.isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(entity)) {
            return;
        }
        if (component.takePending() == null) {
            return;
        }
        buildAndApply(
                entity,
                new PendingTarget(component.assignedState(), component.effectiveSuppression()),
                false
        );
    }

    private static ApplicationTransitionResult buildAndApply(
            Entity entity,
            PendingTarget target,
            boolean assignmentAccepted
    ) {
        return buildAndApply(entity, target, assignmentAccepted, false);
    }

    private static ApplicationTransitionResult buildAndApply(
            Entity entity,
            PendingTarget target,
            boolean assignmentAccepted,
            boolean retainOnlyIfAbsent
    ) {
        var component = component(entity);
        var runtime = component.runtime();
        if (runtime.isInMove()
                || runtime.isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(entity)) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(
                    TransitionStatus.DEFERRED, assignmentAccepted, false);
        }

        CommittedGravityApplication previous = component.committedApplication();
        GravityApplicationPlan desired = desiredPlan(entity, component);

        ApplicationGeometryPlan plan = planGeometry(previous.plan(), desired);
        if (plan != ApplicationGeometryPlan.NONE
                && !cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.mayChangeBody(entity)) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(TransitionStatus.DEFERRED, assignmentAccepted, false);
        }
        if (plan != ApplicationGeometryPlan.NONE) {
            CommittedGravityApplication next = new CommittedGravityApplication(
                    target.desiredAssignment(), target.desiredEffectiveSuppression(), desired);
            if (cc.sighs.gravityengine.gravity.integration.geometry.NativeAabbApplicationCommit
                    .tryCommit(entity, next)) {
                return new ApplicationTransitionResult(
                        TransitionStatus.COMMITTED, assignmentAccepted, false);
            }
        }
        if (plan == ApplicationGeometryPlan.NONE) {
            CommittedGravityApplication next = new CommittedGravityApplication(
                    target.desiredAssignment(), target.desiredEffectiveSuppression(), desired);
            boolean changed = !previous.equals(next);
            if (changed) {
                component.commitApplication(next);
            }
            if (changed
                    && !desired.usesCustomBody() && previous.plan().usesCustomBody()) {
                runtime.clearInfluenceTransientState();
                cleanupVanillaTransition(entity, runtime);
            }
            return new ApplicationTransitionResult(
                    changed ? TransitionStatus.COMMITTED : TransitionStatus.UNCHANGED,
                    assignmentAccepted,
                    false
            );
        }

        Vec3 savedPosition = entity.position();
        AABB savedBox = entity.getBoundingBox();
        Vec3 savedVelocity = entity.getDeltaMovement();
        GravityFrame savedInstalledFrame = runtime.geometryReferenceFrame();

        GravityGeometryTransitionService.Result geo;
        try {
            geo = executePlan(entity, plan, target.desiredAssignment());
        } catch (Exception failure) {
            rollbackEntity(entity, savedPosition, savedBox, savedVelocity, savedInstalledFrame);
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            LOGGER.error(
                    "Gravity geometry transition threw: entityId={} uuid={} dimension={} geometryPlan={} previousPlan={} desiredPlan={} desiredDown={} desiredStrength={}",
                    entity.getId(),
                    entity.getUUID(),
                    entity.level().dimension().location(),
                    plan,
                    previous.plan(),
                    desired,
                    target.desiredAssignment().down(),
                    target.desiredAssignment().strength(),
                    failure
            );
            return new ApplicationTransitionResult(
                    TransitionStatus.FAILED, assignmentAccepted, false);
        }

        if (isSuccessfulGeometryStatus(geo.status())) {
            boolean geometryChanged = geo.geometryChanged();
            CommittedGravityApplication next = new CommittedGravityApplication(
                    target.desiredAssignment(), target.desiredEffectiveSuppression(), desired);
            boolean changed = !previous.equals(next);

            postTransitionCleanup(entity, runtime, plan);
            if (changed
                    && !desired.usesCustomBody() && previous.plan().usesCustomBody()) {
                cleanupVanillaTransition(entity, runtime);
            }

            component.commitApplication(next);
            return new ApplicationTransitionResult(
                    TransitionStatus.COMMITTED, assignmentAccepted, geometryChanged);
        } else if (geo.status() == GravityGeometryTransitionService.Status.DEFERRED) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(
                    TransitionStatus.DEFERRED, assignmentAccepted, false);
        } else {
            rollbackEntity(entity, savedPosition, savedBox, savedVelocity, savedInstalledFrame);
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(
                    TransitionStatus.FAILED, assignmentAccepted, false);
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    static ApplicationGeometryPlan planGeometry(
            GravityApplicationPlan previous,
            GravityApplicationPlan desired
    ) {
        boolean previousCustom = previous.usesCustomBody();
        boolean nextCustom = desired.usesCustomBody();
        if (!previousCustom && !nextCustom) return ApplicationGeometryPlan.NONE;
        if (!previousCustom) return ApplicationGeometryPlan.VANILLA_TO_CUSTOM;
        if (!nextCustom) return ApplicationGeometryPlan.CUSTOM_TO_VANILLA;
        return ApplicationGeometryPlan.NONE;
    }

    private static GravityGeometryTransitionService.Result executePlan(
            Entity entity,
            ApplicationGeometryPlan plan,
            GravityState state
    ) {
        return switch (plan) {
            case NONE -> GravityGeometryTransitionService.Result.UNCHANGED;
            case VANILLA_TO_CUSTOM ->
                    GravityGeometryTransitionService.installCustomFromPositionAnchor(entity, state);
            case CUSTOM_TO_VANILLA -> GravityGeometryTransitionService.installVanilla(entity);
        };
    }

    private static void postTransitionCleanup(
            Entity entity,
            GravityRuntimeState runtime,
            ApplicationGeometryPlan plan
    ) {
        if (plan == ApplicationGeometryPlan.NONE) return;
        runtime.clearInfluenceTransientState();
        entity.setOnGround(false);
    }

    private static void cleanupVanillaTransition(Entity entity, GravityRuntimeState runtime) {
        entity.fallDistance = 0f;
        entity.setOnGround(false);
        runtime.clearCurrentMoveResult();
        runtime.clearInstalledCollisionAxis();
    }

    private static void rollbackEntity(
            Entity entity,
            Vec3 position,
            AABB box,
            Vec3 velocity,
            GravityFrame installedFrame
    ) {
        GravityEntityGeometry.restoreGeometry(entity, position, box, installedFrame);
        entity.setDeltaMovement(velocity);
    }

    private static boolean isSuccessfulGeometryStatus(
            GravityGeometryTransitionService.Status status
    ) {
        return status == GravityGeometryTransitionService.Status.APPLIED
                || status == GravityGeometryTransitionService.Status.RECOVERED
                || status == GravityGeometryTransitionService.Status.UNCHANGED;
    }

    private static void enqueueDeferred(
            EntityGravityComponent component,
            PendingTarget target,
            boolean retainOnlyIfAbsent
    ) {
        if (retainOnlyIfAbsent) {
            component.enqueueIfAbsent(target);
        } else {
            component.enqueueOrReplace(target);
        }
    }

    /**
     * The one authoritative application-selection entry. Entity-kind
     * capabilities and the immutable entity-state snapshot feed the pure
     * planner; no second policy tree is maintained in this controller.
     */
    static GravityApplicationPlan desiredPlan(
            Entity entity,
            EntityGravityComponent component
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(component, "component");
        GravityAuthorityMode authority = component.assignedAuthority();
        boolean hasExplicitState = authority == GravityAuthorityMode.DIRECT;
        boolean hasActiveField = authority == GravityAuthorityMode.FIELD
                && component.assignedFieldPresent();
        boolean suppressed = component.effectiveSuppression()
                != GravitySuppressionReason.NONE;
        return GravityApplicationPlanner.plan(
                GravityEntityCapabilitiesPolicy.capabilities(entity),
                authority,
                hasExplicitState,
                hasActiveField,
                suppressed,
                GravityApplicationStateCapture.capture(entity));
    }

    public static boolean supportsGroundAir(net.minecraft.world.entity.LivingEntity entity) {
        return !GravityEntityCapabilitiesPolicy.isFlyingControlled(entity)
                && !GravityEntityCapabilitiesPolicy.isVanillaSpecialLiving(entity);
    }

    private static ApplicationTransitionResult unchanged(boolean assignmentAccepted) {
        return new ApplicationTransitionResult(
                TransitionStatus.UNCHANGED, assignmentAccepted, false);
    }

    private static EntityGravityComponent component(Entity entity) {
        return GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
    }
}