package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics;
import cc.sighs.gravityengine.gravity.movement.SneakEdgePreventionService;
import cc.sighs.gravityengine.gravity.movement.TravelCapturePlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.player.CharacterControlRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

public final class LivingGravityIntegration {
    private LivingGravityIntegration() {}

    /** Captures the one travel operation; Vanilla still owns its branch sequencing. */
    public static GravityTravelContext openTravel(LivingEntity entity, Vec3 travelVector) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(travelVector);

        if (!entity.isControlledByLocalInstance()) return null;
        if (entity.level().isClientSide()) {
            if (!(entity instanceof Player)) {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                        .updateReplicaBody(entity);
            } else {
                // Player application/body commits remain server-owned. Native
                // locomotion mode belongs to the predicting client, however:
                // refresh it before travel captures support. Its nested move
                // cannot change modes inside this operation (notably after a
                // native passenger removal).
                MovementModeIntegration.update(entity);
            }
        } else {
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator
                    .updateBody(entity);
        }

        if (entity.level().isClientSide()
                && entity instanceof Player
                && MovementModeIntegration.desired(entity)
                == cc.sighs.gravityengine.gravity.runtime.GravityOperationState
                .MovementMode.NATIVE_FALLBACK
                && !GravityInfluencePolicy.hasExternalCollisionProviders(entity)) {
            return null;
        }

        var plan = GravityInfluencePolicy.committedPlan(entity);
        if (!plan.usesCustomMoveSolver() && !GravityInfluencePolicy.hasExternalCollisionProviders(entity)) {
            return null;
        }

        var authorityFrame = GravityFrameAccess.authoritativeFrame(entity);
        var samplePoint = GravityEntityGeometry.bodyCenter(entity);
        Vec3 axes = free3dInput(travelVector.x, travelVector.z,
                GravityLivingAccess.cast(entity).gravityengine$isJumping(),
                entity instanceof CharacterControlAccess.LocalInput local
                        ? local.gravityengine$descendHeld() : entity.isShiftKeyDown());
        var gravityComponent = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        var operationRuntime = gravityComponent.operationState();
        Optional<SupportTransport> supportTransportPreflight =
                gravityComponent.moveInterop().subLevelMoveEvidence() == null
                        ? EngineSupportTransportIntegration.preflight(
                                entity,
                                operationRuntime,
                                entity.level().getGameTime(),
                                1.0D
                        )
                        : Optional.empty();
        cc.sighs.gravityengine.api.math.Vec3d transportDisplacement =
                supportTransportPreflight
                        .map(SupportTransport::displacement)
                        .orElse(cc.sighs.gravityengine.api.math.Vec3d.ZERO);
        Optional<Aabb3d> supportRelativeBounds =
                supportTransportPreflight
                        .flatMap(SupportTransport::trajectory)
                        .filter(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::requiresGeneralSweep
                        )
                        .map(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::relativeBounds
                        );
        // The resolved step may remove provisional sneak slowdown. Capture
        // the full normalized input bound before that semantic handoff.
        TravelCapturePlan ordinary = captureTravelPlan(entity,
                entity instanceof CharacterControlAccess.LocalInput ? new Vec3(1, 1, 1) : axes,
                transportDisplacement,
                supportRelativeBounds);
        Vec3 incomingVelocity = entity.getDeltaMovement();
        boolean elytra = entity.isFallFlying();
        Aabb3d incomingBounds = MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox());
        double maxStep = entity.maxUpStep();
        TravelCapturePlan[] capture = new TravelCapturePlan[1];
        var op = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(entity, GravityOperationType.TRAVEL,
                samplePoint, 1.0D, (sample, proposedFrame) -> {
                    // Bound every possible look/reference orientation, including a
                    // geometry transition selecting the installed fallback frame.
                    cc.sighs.gravityengine.api.math.Vec3d aerodynamicAcceleration =
                            entity.isNoGravity()
                                    ? cc.sighs.gravityengine.api.math.Vec3d.ZERO
                                    : sample.accelerationVector();

                    double aerodynamicBound = elytra
                            ? ElytraAerodynamics.maximumRequestMagnitude(
                            MinecraftMathAdapter.toVec3d(
                                    incomingVelocity),
                            aerodynamicAcceleration
                    )
                            : 0.0D;
                    capture[0] = aerodynamicBound > 0
                            ? supportRelativeBounds.map(
                                    relative ->
                                            TravelCapturePlan
                                                    .buildIsotropic(
                                                            incomingBounds,
                                                            cc.sighs.gravityengine.api.math.Vec3d.ZERO,
                                                            aerodynamicBound,
                                                            transportDisplacement,
                                                            relative,
                                                            maxStep
                                                    )
                            ).orElseGet(
                                    () -> TravelCapturePlan
                                            .buildIsotropic(
                                                    incomingBounds,
                                                    cc.sighs.gravityengine.api.math.Vec3d.ZERO,
                                                    aerodynamicBound,
                                                    transportDisplacement,
                                                    maxStep
                                            )
                            )
                            : ordinary;
                    return capture[0].domain();
                });
        try {
            EngineSupportTransportIntegration.stageResolvedTransport(
                    operationRuntime,
                    supportTransportPreflight
            );
            var character = CharacterControlRuntime.resolve(
                    entity,
                    op.gravitySnapshot().frame(),
                    op.controlTerminalSupportAtStepStart()
            );
            Vec3 resolvedTravel = entity instanceof CharacterControlAccess.LocalInput local
                    ? local.gravityengine$resolveSneakInput(character, travelVector) : travelVector;
            Vec3 input = character.locomotion()
                    == cc.sighs.gravityengine.gravity.movement
                    .CharacterLocomotionTechnique.FREE_3D
                    ? new Vec3(resolvedTravel.x, axes.y, resolvedTravel.z)
                    : resolvedTravel;
            return new GravityTravelContext(entity, input, op, character, capture[0],
                    cc.sighs.gravityengine.look.PlayerLookIntegration.capture(entity, op.gravitySnapshot().frame()),
                    MovementEnvironmentCapture.capture(entity, operationRuntime.collisionOperation().workTracker()));
        } catch (RuntimeException | Error failure) {
            op.close();
            throw failure;
        }
    }

    public static Vec3 moveRelative(
            Entity entity,
            float speed,
            Vec3 input
    ) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(input);

        /*
         * moveRelative is locomotion input integration, not collision-body
         * ownership. A pending reference-geometry transition may keep an exact body
         * and custom Entity.move collision while motionMode remains VANILLA.
         * In that state Vanilla must retain its own moveRelative semantics.
         */
        if (!GravityInfluencePolicy.usesCustomLocomotion(entity)) {
            return null;
        }

        GravityFrame frame =
                GravityFrameAccess.installedLocomotionFrame(entity);

        Vec3 self = cc.sighs.gravityengine.gravity.integration.GravityMovementInput.calculateRelativeMovement(entity, speed, input, frame);
        GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState()
                .recordSelfWalk(
                        entity.level().getGameTime(),
                        MinecraftMathAdapter.toVec3d(self));
        return entity.getDeltaMovement().add(self);
    }

    /** Runs only after Vanilla accepts its jump, before the jump event. */
    public static void commitJump(
            LivingEntity entity,
            cc.sighs.gravityengine.gravity.integration.vanilla.VanillaPropulsionBridge.Jump jump
    ) {
        var runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();
        long tick = entity.level().getGameTime();
        Vec3 jumpDelta = entity.getDeltaMovement().subtract(jump.before());
        var releaseVelocity = jump.releaseVelocity();
        if (releaseVelocity.lengthSquared() > 0.0D) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(
                    MinecraftMathAdapter.toMinecraft(releaseVelocity)));
            runtime.recordSupportMotion(tick, releaseVelocity);
        }
        runtime.recordSelfWalk(tick, MinecraftMathAdapter.toVec3d(jumpDelta));
        // A nested jump must not leave an unconsumed platform displacement.
        runtime.clearEngineSupportTransport();
        runtime.clearRestingContactSnapshot();
        runtime.clearPersistentSupportState();
        if (entity instanceof Player player) {
            ((CharacterControlAccess) player).gravityengine$characterControl().invalidatePlan();
        }
    }

    public static TravelCapturePlan captureTravelPlan(
            LivingEntity entity,
            Vec3 effectiveTravelInput
    ) {
        return captureTravelPlan(
                entity,
                effectiveTravelInput,
                cc.sighs.gravityengine.api.math.Vec3d.ZERO
        );
    }

    public static TravelCapturePlan captureTravelPlan(
            LivingEntity entity,
            Vec3 effectiveTravelInput,
            cc.sighs.gravityengine.api.math.Vec3d transportDisplacement
    ) {
        return captureTravelPlan(
                entity,
                effectiveTravelInput,
                transportDisplacement,
                Optional.empty()
        );
    }

    private static TravelCapturePlan captureTravelPlan(
            LivingEntity entity,
            Vec3 effectiveTravelInput,
            cc.sighs.gravityengine.api.math.Vec3d transportDisplacement,
            Optional<Aabb3d> supportRelativeBounds
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(
                effectiveTravelInput,
                "effectiveTravelInput"
        );
        TravelCapturePlan.requireFinite(
                MinecraftMathAdapter.toVec3d(effectiveTravelInput),
                "effectiveTravelInput");

        Aabb3d bounds = MinecraftCollisionGeometryAdapter.toAabb3d(
                entity.getBoundingBox()
        );
        cc.sighs.gravityengine.api.math.Vec3d currentVelocity =
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement());
        TravelCapturePlan.requireFinite(currentVelocity, "currentVelocity");

        double speedCap = entity.onGround()
                ? entity.getSpeed() * TravelCapturePlan.MAX_GROUNDED_SPEED_FACTOR
                : GravityLivingAccess.cast(entity)
                .gravityengine$getFlyingSpeed();
        // Capture precedes current-scene support classification. A synchronized
        // exact body may reacquire traction even when native onGround was cleared;
        // cover both possible input speeds before freezing the scene.
        if (GravityInfluencePolicy.usesExactBodyCollision(entity)
                || GravityInfluencePolicy.hasExternalCollisionProviders(entity)) {
            speedCap = Math.max(Math.abs(speedCap), Math.max(
                    Math.abs(entity.getSpeed() * TravelCapturePlan.MAX_GROUNDED_SPEED_FACTOR),
                    Math.abs(GravityLivingAccess.cast(entity).gravityengine$getFlyingSpeed())));
        }

        /*
         * GravityPhysics.calculateRelativeMovement normalizes an input whose
         * length exceeds 1 before multiplying by speed. Its resulting world
         * vector therefore has this maximum magnitude regardless of reference
         * orientation or semantic look heading.
         */
        double inputLengthSquared = effectiveTravelInput.lengthSqr();
        double normalizedInputMagnitude =
                inputLengthSquared <= 0.0D
                        ? 0.0D
                        : Math.min(1.0D, Math.sqrt(inputLengthSquared));

        double maxLocomotionContribution =
                Math.abs(speedCap) * normalizedInputMagnitude
                        + GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState()
                                .supportVelocityContribution().length();

        return supportRelativeBounds.map(
                relative -> TravelCapturePlan.buildIsotropic(
                        bounds,
                        currentVelocity,
                        maxLocomotionContribution,
                        transportDisplacement,
                        relative,
                        entity.maxUpStep()
                )
        ).orElseGet(
                () -> TravelCapturePlan.buildIsotropic(
                        bounds,
                        currentVelocity,
                        maxLocomotionContribution,
                        transportDisplacement,
                        entity.maxUpStep()
                )
        );
    }

    /** Vanilla owns policy; this pre-move probe borrows the movement scene and
     * exact character body. It publishes no grounding/support history. */
    public static Vec3 applyPlayerSneakEdge(
            Player player, Vec3 displacement, net.minecraft.world.entity.MoverType mover,
            boolean stayingOnGroundSurface
    ) {
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().operationState();
        GravityFrame frame = runtime.activeFrame();
        var step = ((CharacterControlAccess) player).gravityengine$characterControl().at(player.tickCount);
        if (!sneakEdgeEligible(mover, player.getAbilities().flying,
                frame.worldToLocal(
                        MinecraftMathAdapter.toVec3d(displacement))
                        .y(),
                stayingOnGroundSurface,
                step != null && step.ownsDescendInput())) return displacement;
        var operation = runtime.collisionOperation();
        if (operation == null || operation.frame() != frame) {
            throw new IllegalStateException("sneak-edge requires the borrowed movement frame and scene");
        }
        CollisionBody body = GravityEntityGeometry.body(player);
        float stepHeight = player.maxUpStep();
        // Entity.move's checkFallDamage operand already accumulates gravity-relative
        // locomotion vertical (GravityMoveResult.fallDistanceVertical(capturedEnvironmentalUp)).
        if (!isAboveGround(player.onGround(), player.fallDistance, stepHeight, body,
                frame, operation.scene())) {
            return displacement;
        }
        return MinecraftMathAdapter.toMinecraft(
                SneakEdgePreventionService.constrainTangentMovement(
                        frame,
                        body,
                        MinecraftMathAdapter.toVec3d(displacement),
                        SneakEdgePreventionService.sceneProbe(
                                operation.scene(), frame, stepHeight)));
    }

    static boolean sneakEdgeEligible(net.minecraft.world.entity.MoverType mover, boolean flying,
            double localVertical, boolean stayingOnGroundSurface, boolean descendOwned) {
        return !flying && !(localVertical > 0.0D)
                && (mover == net.minecraft.world.entity.MoverType.SELF
                    || mover == net.minecraft.world.entity.MoverType.PLAYER)
                && stayingOnGroundSurface && !descendOwned;
    }

    static boolean isAboveGround(boolean onGround, float fallDistance, float maxUpStep,
            CollisionBody body,
            GravityFrame frame, cc.sighs.gravityengine.gravity.collision.CollisionScene scene) {
        return onGround || fallDistance < maxUpStep
                && SneakEdgePreventionService.sceneProbe(scene, frame, maxUpStep - fallDistance).hasSupport(body);
    }

    /**
     * Spatial mapping of the current Vanilla control carriers; no transport or
     * actor-step identity. Package-visible for focused input-semantics tests.
     */
    static Vec3 free3dInput(double strafe, double forward, boolean jump, boolean descend) {
        return new Vec3(strafe, (jump ? 1.0D : 0.0D) - (descend ? 1.0D : 0.0D), forward);
    }

}
