package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts;

import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationService;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.ballistic.BallisticGravityIntegrator;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Minecraft adapter for one vanilla ballistic gravity call. */
public final class BallisticGravityIntegration {
    private BallisticGravityIntegration() {}

    /**
     * Applies field acceleration exactly once when an active field exists.
     * Returns false when the caller must invoke the original vanilla gravity.
     */
    public static boolean applyGravity(Entity entity, double intervalTicks) {
        Objects.requireNonNull(entity, "entity");
        if (entity.level().isClientSide()) {
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                    .updateReplicaBody(entity);
        } else {
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                    .updateBody(entity);
        }
        var plan = GravityInfluencePolicy.committedPlan(entity);
        var kind = plan.kind();
        if ((kind != GravityApplicationPlan.Kind.BALLISTIC
                && kind != GravityApplicationPlan.Kind.PASSIVE)
                || plan.accelerationMode() == GravityAccelerationMode.NONE
                || entity.isNoGravity()) {
            return false;
        }

        Vec3 samplePoint = entity.getBoundingBox().getCenter();
        Vec3 velocity = entity.getDeltaMovement();
        var query = new AccelerationQuery(
                MinecraftMathAdapter.toVec3d(samplePoint),
                MinecraftMathAdapter.toVec3d(velocity),
                entity.level().getGameTime(),
                intervalTicks,
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent()
                        .state()
                        .appliedState()
        );

        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        var runtime = component.operationState();
        var registry = GravityFieldRuntime.get(entity.level());
        var evaluationContext =
                GravityEvaluationContexts.capture(
                        component.state(),
                        registry
                );
        var evaluationQuery = new cc.sighs.gravityengine.api.field
                .GravityFieldQuery(
                query.samplePoint(),
                query.velocity(),
                query.gameTick(),
                query.intervalTicks()
        );

        /*
         * One physical gravity snapshot owns this ballistic integration step.
         * Reuse is allowed only for the exact query and committed application
         * context. An active operation must supply its captured evaluation;
         * standalone calls may evaluate once through the plan-generic service.
         */
        var captured = runtime.isInMove()
                ? runtime.activeOperationEvaluation() : runtime.gravityEvaluationFor(evaluationQuery);
        var reusable = captured
                        .filter(candidate -> runtime.isInMove() || !candidate.context().usesFieldRegistry())
                        .filter(candidate ->
                                GravityEvaluationService.reusable(
                                        candidate,
                                        evaluationContext,
                                        query
                                ).isPresent()
                        );
        if (runtime.isInMove() && reusable.isEmpty()) {
            throw new IllegalStateException("ballistic gravity must consume its operation's original physical query");
        }
        GravityEvaluationSnapshot evaluation = reusable.orElseGet(() ->
                                GravityEvaluationService.evaluateForPlan(
                                        evaluationContext,
                                        registry,
                                        query,
                                        component.state()
                                                .appliedState()
                                                .down(),
                                        runtime.lastCompletedFrame()
                                )
                        );
        if (evaluation.committedApplication()
                .plan()
                .accelerationMode()
                == GravityAccelerationMode.FIELD
                && !evaluation.hasActiveField()
                && evaluation.fieldCoverage() != cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) {
            /*
             * The wrapped vanilla call remains the owner when FIELD authority
             * has no active contribution. Do not fabricate a replacement.
             */
            return false;
        }
        if (!runtime.isInMove()) {
            runtime.publishTickEvaluation(evaluation);
        }
        entity.setDeltaMovement(MinecraftMathAdapter.toMinecraft(
                BallisticGravityIntegrator.integrate(
                        MinecraftMathAdapter.toVec3d(velocity),
                        evaluation
                )));
        return true;
    }

}
