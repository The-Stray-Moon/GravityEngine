package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts;

import cc.sighs.gravityengine.gravity.assignment.GravityAssignmentService;
import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityAuthorityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationService;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContext;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.network.GravitySyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Owns entity base-tick application bootstrap, reconciliation and periodic assignment refresh. */
public final class GravityEntityTickLifecycle {
    private GravityEntityTickLifecycle() {}

    public static void onBaseTick(Entity e) {
        if (e.isRemoved()) return;
        boolean groundAir = e instanceof LivingEntity living
                && cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.supportsGroundAir(living);
        if (e.level().isClientSide()) {
            /*
             * Local and remote players consume the committed application from
             * the server body stream. Non-player replicas retry only an
             * application explicitly received from the server.
             */
            if (!(e instanceof net.minecraft.world.entity.player.Player)) {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateReplicaBody(e);
            }
            return;
        }
        var gravityComponent =
                GravityEntityAccess.cast(e)
                        .gravityengine$gravityComponent();

        if (gravityComponent.state().applicationBootstrapPending()) {
            /*
             * ServerPlayer load restoration has one high-level owner. A failed login
             * or replacement bootstrap retries through exactly that same owner; the
             * generic entity fallback must never reconstruct only the gravity half and
             * then publish/simulate a partially restored player.
             */
            if (e instanceof ServerPlayer player) {
                PlayerPhysicalLoadBootstrap.bootstrapLoadedPlayer(player);
                return;
            }

            /*
             * Non-player load fallback: refresh canonical load inputs without an
             * intermediate application commit, then perform exactly one derived
             * application reconstruction.
             */
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.refreshLoadedInputs(e);

            var bootstrap =
                    cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                            .bootstrapLoadedApplication(e);

            if (bootstrap.status()
                    == cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus.COMMITTED
                    || bootstrap.status()
                    == cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.TransitionStatus.UNCHANGED) {
                gravityComponent.state().clearApplicationBootstrapPending();
                GravitySyncService.syncTracking(e);
            } else {
                // Do not let ordinary per-tick assignment/update logic bypass the
                // incomplete load bootstrap.
                return;
            }
            // This bootstrap already refreshed FIELD inputs; do not sample again
            // if the first loaded tick also falls on the periodic refresh cadence.
            return;
        }
        if (e instanceof ServerPlayer sp) {
            GravityApplicationCoordinator.reconcileServerInfluence(sp);
        }
        var capabilities = cc.sighs.gravityengine.gravity.policy.GravityEntityCapabilitiesPolicy.capabilities(e);
        boolean ballistic = capabilities == cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities.BALLISTIC
                || capabilities == cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities.PASSIVE_BALLISTIC;
        if (!groundAir && !ballistic) return;

        /*
         * A field may legitimately depend on time, velocity or interval
         * length, so a stationary entity still needs a current evaluation
         * every tick. Publication stays change-gated: the assignment and
         * application commit below only runs when the evaluated truth or its
         * field-presence identity actually changed.
         */
        if (e instanceof LivingEntity || ballistic) updateResolvedGravity(e);
        if (e instanceof net.minecraft.world.entity.decoration.ArmorStand)
            GravityApplicationCoordinator.updateBody(e);
    }

    private static void updateResolvedGravity(Entity e) {
        EntityGravityComponent component = GravityEntityAccess.cast(e)
                .gravityengine$gravityComponent();
        var runtime = component.operationState();

        if (runtime.isInMove()) {
            return;
        }

        var registry = GravityFieldRuntime.get(e.level());
        GravityEvaluationContext currentContext =
                GravityEvaluationContexts.capture(
                        component.state(),
                        registry
                );

        if (component.state().assignedAuthority()
                == GravityAuthorityMode.DIRECT
                && currentContext.committedApplication()
                .plan()
                .accelerationMode()
                != GravityAccelerationMode.FIELD) {
            /*
             * DIRECT must still publish a current physical snapshot for this
             * tick. It never samples the field registry.
             */
            GravityEvaluationSnapshot direct =
                    GravityEvaluationService.evaluateCharacterOperation(
                            currentContext,
                            registry,
                            GravityAssignmentService
                                    .accelerationQuery(e),
                            component.state().appliedState().down(),
                            runtime.lastCompletedFrame()
                    );
            runtime.publishTickEvaluation(direct);
            return;
        }

        var resolved = GravityAssignmentService.evaluate(e);
        if (!resolved.authoritative()) {
            var state = GravityEntityAccess.cast(e).gravityengine$gravityComponent().state();
            if (state.invalidateFieldEvidence() || resolved.changed()) GravitySyncService.syncTracking(e);
            GravityEntityAccess.cast(e).gravityengine$gravityComponent().operationState()
                    .publishTickEvaluation(GravityAssignmentService.physicalEvaluation(e, resolved));
            return;
        }

        if (!resolved.changed()) {
            /*
             * A time/velocity-dependent field can resolve to the same
             * assignment while still requiring a fresh immutable snapshot for
             * the current query. Publish that snapshot without advancing the
             * assignment revision.
             */
            runtime.publishTickEvaluation(
                    GravityAssignmentService.physicalEvaluation(
                            e,
                            resolved
                    )
            );
            return;
        }

        var applied = cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyFieldAssignment(
                e,
                resolved.resolved(),
                resolved.fieldPresent()
        );

        /*
         * The assignment boundary advances the committed revision. Rebind the
         * already-sampled immutable evidence to that exact committed binding;
         * never publish the pre-commit authority revision or re-sample the
         * field.
         */
        runtime.publishTickEvaluation(
                GravityAssignmentService.physicalEvaluation(
                        e,
                        resolved
                )
        );

        if (applied.assignmentAccepted()) {
            GravitySyncService.syncTracking(e);
        }
    }

}
