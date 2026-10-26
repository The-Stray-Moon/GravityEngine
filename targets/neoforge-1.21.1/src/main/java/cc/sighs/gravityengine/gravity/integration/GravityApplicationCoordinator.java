package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.assignment.GravityAssignmentService;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.geometry.GeometryTransitionResult;
import cc.sighs.gravityengine.gravity.geometry.GeometryTransitionStatus;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities;
import cc.sighs.gravityengine.gravity.model.GravityEntityState.PendingTarget;
import cc.sighs.gravityengine.gravity.model.GravityEntityState.SnapshotAcceptance;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.gravity.policy.GravityApplicationPlanner;
import cc.sighs.gravityengine.gravity.policy.GravityEntityCapabilitiesPolicy;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.network.GravitySyncService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Authoritative gravity-application transition coordinator.
 *
 * <p>Owns the authoritative application-selection entry: entity-kind
 * capabilities and the immutable entity-state snapshot feed the pure planner,
 * the resulting geometry transition is executed exactly once, and any
 * transition that cannot run under the current movement/geometry scope is
 * deferred rather than partially applied. Client non-player replicas use the
 * explicit {@link #applyRemoteApplication(Entity, CommittedGravityApplication, long)}
 * path, preserving the server plan and epoch through deferred installation.</p>
 */
public final class GravityApplicationCoordinator {
    private GravityApplicationCoordinator() {}

    /** Server-side consolidated influence reconciliation and synchronization. */
    public static void reconcileServerInfluence(ServerPlayer player) {
        var component = GravityEntityAccess.cast(player).gravityengine$gravityComponent();
        long before = component.state().influenceRevision();
        updateAuthoritativeSuppression(player);
        updateBody(player);
        if (component.state().influenceRevision() != before) {
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

    /** Replicated assignment/suppression acceptance. No application decision is derived here. */
    public record RemoteSnapshotResult(
            boolean assignmentAccepted,
            boolean influenceAccepted
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
        requireAuthoritativeMutationSide(entity);
        var component = component(entity);
        if (rejectWhileDirect && component.state().assignedAuthority() == GravityAuthorityMode.DIRECT) {
            return unchanged(false);
        }
        boolean hadReference = component.state().hasActiveGravityReference();
        boolean accepted = component.state().setAssigned(next, authority, fieldPresent);
        if (!accepted) {
            return unchanged(false);
        }
        /*
         * NeoForge persists only attachment instances present on the holder, so
         * a durable mutation must materialize the slot before the next save.
         */
        cc.sighs.gravityengine.gravity.persistence.GravityPersistence
                .materialize(entity);
        ApplicationTransitionResult result = buildAndApply(
                entity,
                new PendingTarget(component.state().assignedState(), component.state().authoritativeSuppression()),
                true,
                false,
                false
        );
        clearLostReferenceContinuity(component, hadReference);
        return result;
    }

    public static RemoteSnapshotResult applyRemoteSnapshot(
            Entity entity,
            GravityState assignment,
            GravityAuthorityMode authority,
            cc.sighs.gravityengine.api.FieldPresence fieldPresence,
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
        boolean hadReference = component.state().hasActiveGravityReference();

        SnapshotAcceptance assignmentAcceptance = component.state().acceptRemoteAssignment(
                assignment, authority, fieldPresence, assignmentRevision);
        SnapshotAcceptance suppressionAcceptance = component.state().acceptRemoteSuppression(
                suppression, suppressionRevision);

        /*
         * A conflicting equal-revision snapshot changes nothing. The domain
         * state reports the conflict and this Minecraft-facing network caller
         * owns the diagnostic.
         */
        if (assignmentAcceptance == SnapshotAcceptance.CONFLICT) {
            LOGGER.warn(
                    "Assignment snapshot conflict: revision={} "
                            + "existingDown={} incomingDown={} "
                            + "existingAuthority={} incomingAuthority={} "
                            + "existingFieldPresent={} incomingFieldPresent={}",
                    assignmentRevision,
                    component.state().assignedState().down(),
                    assignment.down(),
                    component.state().assignedAuthority(),
                    authority,
                    component.state().assignedFieldPresent(),
                    fieldPresence
            );
        }

        if (suppressionAcceptance == SnapshotAcceptance.CONFLICT) {
            LOGGER.warn(
                    "Suppression snapshot conflict: revision={} "
                            + "existing={} incoming={}",
                    suppressionRevision,
                    component.state().authoritativeSuppression(),
                    suppression
            );
        }

        clearLostReferenceContinuity(component, hadReference);

        return new RemoteSnapshotResult(
                assignmentAcceptance == SnapshotAcceptance.ACCEPTED,
                suppressionAcceptance == SnapshotAcceptance.ACCEPTED
        );
    }

    /**
     * A genuinely disappearing environmental reference is a reference
     * discontinuity, not an application, routing or collider event: no
     * presentation basis may outlive the reference it was derived from. An
     * application fallback that keeps the reference (for example the Vanilla
     * body while the entity is still inside a field) is not a loss and clears
     * nothing here.
     */
    private static void clearLostReferenceContinuity(
            EntityGravityComponent component,
            boolean hadReference
    ) {
        if (hadReference && !component.state().hasActiveGravityReference()) {
            component.operationState().clearFrameContinuity();
        }
    }

    public static ApplicationTransitionResult updateAuthoritativeSuppression(Entity entity) {
        requireAuthoritativeMutationSide(entity);
        var component = component(entity);
        GravitySuppressionReason next =
                GravityInfluencePolicy.deriveAuthoritativeSuppression(entity);
        if (!component.state().setAuthoritativeSuppression(next)) {
            return unchanged(false);
        }
        return buildAndApply(
                entity,
                new PendingTarget(component.state().assignedState(), component.state().authoritativeSuppression()),
                true,
                false,
                false
        );
    }

    /** Server-authoritative application transition from the current committed inputs. */
    public static ApplicationTransitionResult updateBody(Entity entity) {
        requireAuthoritativeMutationSide(entity);
        MovementModeIntegration.update(entity);
        var component = component(entity);
        return buildAndApply(
                entity,
                new PendingTarget(component.state().assignedState(), component.state().authoritativeSuppression()),
                false,
                false,
                false
        );
    }

    /**
     * Retries a deferred server application for a live non-player replica.
     * Assignment and local source discovery never select the replica plan.
     */
    public static ApplicationTransitionResult updateReplicaBody(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!entity.level().isClientSide()) {
            throw new IllegalStateException(
                    "replica application derivation is client-only");
        }
        if (entity instanceof Player) {
            throw new IllegalStateException(
                    "player application must be installed from the server body commit stream");
        }
        applyPending(entity);
        return unchanged(false);
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
        requireAuthoritativeMutationSide(entity);
        EntityGravityComponent component = component(entity);
        return buildAndApply(
                entity,
                new PendingTarget(component.state().assignedState(), component.state().authoritativeSuppression()),
                false,
                false,
                false
        );
    }

    /**
     * Refreshes load-time authoritative inputs without publishing any derived
     * application or geometry.
     */
    public static void refreshLoadedInputs(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        requireAuthoritativeMutationSide(entity);
        EntityGravityComponent component = component(entity);

        // A retry always rebuilds its derived target from current authoritative
        // inputs. Never allow a deferred target from an earlier attempt to commit
        // after the fresh load snapshot.
        component.state().takePending();

        if (component.state().assignedAuthority() == GravityAuthorityMode.FIELD) {
            GravityAssignmentService.AssignmentResult field =
                    GravityAssignmentService.evaluate(entity);
            if (component.state().commitFieldEvaluation(field.resolved(), field.fieldPresent(),
                    field.fieldEvaluation().coverage()) && field.authoritative()) {
                cc.sighs.gravityengine.gravity.persistence.GravityPersistence.materialize(entity);
            }
        }

        component.state().setAuthoritativeSuppression(
                GravityInfluencePolicy.deriveAuthoritativeSuppression(entity));
    }

    public static void applyPending(Entity entity) {
        var component = component(entity);
        var runtime = component.operationState();
        if (runtime.isInMove()
                || runtime.isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(entity)) {
            return;
        }
        PendingTarget pending = component.state().takePending();
        if (pending == null) {
            return;
        }
        if (entity.level().isClientSide()) {
            if (!(entity instanceof Player) && pending.remotePlan() != null) {
                applyRemoteApplication(entity, new CommittedGravityApplication(
                        pending.desiredAssignment(), pending.desiredEffectiveSuppression(),
                        pending.remotePlan()), pending.remoteEpoch());
            }
            return;
        }
        buildAndApply(
                entity,
                new PendingTarget(component.state().assignedState(), component.state().authoritativeSuppression()),
                false,
                false,
                false
        );
    }

    private static ApplicationTransitionResult buildAndApply(
            Entity entity,
            PendingTarget target,
            boolean assignmentAccepted,
            boolean retainOnlyIfAbsent,
            boolean replica
    ) {
        return buildAndApply(entity, target, assignmentAccepted, retainOnlyIfAbsent, replica, null, 0);
    }

    public static ApplicationTransitionResult applyRemoteApplication(Entity entity,
            CommittedGravityApplication application, long epoch) {
        var state = component(entity).state();
        PendingTarget pending = state.pendingApplication();
        if (pending != null && pending.remotePlan() != null && pending.remoteEpoch() >= epoch) {
            return unchanged(false);
        }
        if (state.classifyRemoteApplication(application, epoch)
                != cc.sighs.gravityengine.gravity.model.GravityEntityState.RemoteApplicationAcceptance.ACCEPTED) {
            return unchanged(false);
        }
        state.takePending();
        return buildAndApply(entity, new PendingTarget(application.appliedState(), application.authoritativeSuppression(),
                        application.plan(), epoch),
                false, false, true, application, epoch);
    }

    private static ApplicationTransitionResult buildAndApply(Entity entity, PendingTarget target,
            boolean assignmentAccepted, boolean retainOnlyIfAbsent, boolean replica,
            CommittedGravityApplication remoteApplication, long remoteEpoch) {
        if (replica) {
            if (!entity.level().isClientSide() || entity instanceof Player) {
                throw new IllegalStateException(
                        "replica application install requires a client non-player entity");
            }
        } else {
            requireAuthoritativeMutationSide(entity);
        }
        var component = component(entity);
        var runtime = component.operationState();
        if (runtime.isInMove()
                || runtime.isApplyingGeometry()
                || GravityApplicationBarrier.isHeld(entity)) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(
                    TransitionStatus.DEFERRED, assignmentAccepted, false);
        }

        CommittedGravityApplication previous = component.state().committedApplication();
        GravityApplicationPlan desired = replica ? Objects.requireNonNull(remoteApplication).plan() : desiredPlan(entity, component);

        ApplicationGeometryPlan plan = planGeometry(previous.plan(), desired);
        if (!cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.mayChangeBody(entity)) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(TransitionStatus.DEFERRED, assignmentAccepted, false);
        }
        if (!replica && plan != ApplicationGeometryPlan.NONE) {
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
            if (changed || replica) {
                commitApplication(component, next, replica, remoteEpoch);
            }
            if (changed
                    && !desired.usesCustomBody() && previous.plan().usesCustomBody()) {
                runtime.clearMovementTransientState();
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
        var savedInstalledUp = runtime.installedCollisionUp();

        GeometryTransitionResult geo;
        try {
            geo = executePlan(entity, plan, target.desiredAssignment());
        } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                | cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException unavailable) {
            rollbackEntity(entity, savedPosition, savedBox, savedVelocity, savedInstalledUp);
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(entity, unavailable);
            return new ApplicationTransitionResult(TransitionStatus.FAILED, assignmentAccepted, false);
        } catch (RuntimeException | Error failure) {
            try { rollbackEntity(entity, savedPosition, savedBox, savedVelocity, savedInstalledUp); }
            catch (RuntimeException | Error rollback) { if (rollback != failure) failure.addSuppressed(rollback); }
            throw failure;
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

            commitApplication(component, next, replica, remoteEpoch);
            return new ApplicationTransitionResult(
                    TransitionStatus.COMMITTED, assignmentAccepted, geometryChanged);
        } else if (geo.status() == GeometryTransitionStatus.DEFERRED) {
            enqueueDeferred(component, target, retainOnlyIfAbsent);
            return new ApplicationTransitionResult(
                    TransitionStatus.DEFERRED, assignmentAccepted, false);
        } else {
            rollbackEntity(entity, savedPosition, savedBox, savedVelocity, savedInstalledUp);
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

    private static GeometryTransitionResult executePlan(
            Entity entity,
            ApplicationGeometryPlan plan,
            GravityState state
    ) {
        return switch (plan) {
            case NONE -> GeometryTransitionResult.UNCHANGED;
            case VANILLA_TO_CUSTOM ->
                    GravityGeometryTransitionService.installCustomFromPositionAnchor(entity, state);
            case CUSTOM_TO_VANILLA -> GravityGeometryTransitionService.installVanilla(entity);
        };
    }

    private static void postTransitionCleanup(
            Entity entity,
            GravityOperationState runtime,
            ApplicationGeometryPlan plan
    ) {
        if (plan == ApplicationGeometryPlan.NONE) return;
        runtime.clearMovementTransientState();
        entity.setOnGround(false);
    }

    private static void cleanupVanillaTransition(Entity entity, GravityOperationState runtime) {
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
            cc.sighs.gravityengine.api.math.Vec3d installedUp
    ) {
        GravityEntityGeometry.restoreGeometry(entity, position, box, installedUp);
        entity.setDeltaMovement(velocity);
    }

    private static boolean isSuccessfulGeometryStatus(
            GeometryTransitionStatus status
    ) {
        return status == GeometryTransitionStatus.APPLIED
                || status == GeometryTransitionStatus.RECOVERED
                || status == GeometryTransitionStatus.UNCHANGED;
    }

    private static void enqueueDeferred(
            EntityGravityComponent component,
            PendingTarget target,
            boolean retainOnlyIfAbsent
    ) {
        if (retainOnlyIfAbsent) {
            component.state().enqueueIfAbsent(target);
        } else {
            component.state().enqueueOrReplace(target);
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
        GravityAuthorityMode authority = component.state().assignedAuthority();
        boolean hasExplicitState = authority == GravityAuthorityMode.DIRECT;
        // A durable FIELD seed may request safe continuity installation while
        // live presence stays UNKNOWN. The geometry owner commits or rejects it.
        boolean hasActiveField = authority == GravityAuthorityMode.FIELD
                && (component.state().fieldReferenceInForce()
                || component.state().fieldEvidenceUnknown()
                && component.state().durableFieldContinuity() == cc.sighs.gravityengine.gravity.model.GravityEntityState.FieldContinuity.SEED);
        boolean suppressed = component.state().authoritativeSuppression()
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

    private static void commitApplication(
            EntityGravityComponent component,
            CommittedGravityApplication application,
            boolean replica, long remoteEpoch
    ) {
        if (replica) {
            component.state().acceptRemoteApplication(application, remoteEpoch);
        } else {
            if (component.state().commitAuthoritativeApplication(application)) {
                if (component.entity() instanceof ServerPlayer player) {
                    cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.markApplicationChanged(player);
                } else {
                    GravitySyncService.syncTracking(component.entity());
                }
            }
        }
    }

    private static void requireAuthoritativeMutationSide(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.level().isClientSide()) {
            throw new IllegalStateException(
                    "authoritative gravity application mutation is server-owned");
        }
    }
}
