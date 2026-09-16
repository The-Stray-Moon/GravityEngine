package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics;
import cc.sighs.gravityengine.gravity.movement.SneakEdgePreventionService;
import cc.sighs.gravityengine.gravity.movement.TravelCapturePlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.player.CharacterControlRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class LivingGravityIntegration {
    private LivingGravityIntegration() {}

    /** Captures the one travel operation; Vanilla still owns its branch sequencing. */
    public static GravityTravelContext openTravel(LivingEntity entity, Vec3 travelVector) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(travelVector);

        if (!entity.isControlledByLocalInstance()) return null;
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(entity);

        var plan = GravityInfluencePolicy.committedPlan(entity);
        if (!plan.usesCustomMoveSolver()) {
            return null;
        }

        var authorityFrame = GravityFrameAccess.authoritativeFrame(entity);
        var samplePoint = GravityEntityGeometry.bodyCenter(entity, authorityFrame);
        Vec3 axes = free3dInput(travelVector.x, travelVector.z,
                GravityLivingAccess.cast(entity).gravityengine$isJumping(),
                entity instanceof CharacterControlAccess.LocalInput local
                        ? local.gravityengine$descendHeld() : entity.isShiftKeyDown());
        // The resolved step may remove provisional sneak slowdown. Capture
        // the full normalized input bound before that semantic handoff.
        TravelCapturePlan ordinary = captureTravelPlan(entity,
                entity instanceof CharacterControlAccess.LocalInput ? new Vec3(1, 1, 1) : axes);
        Vec3 incomingVelocity = entity.getDeltaMovement();
        boolean elytra = entity.isFallFlying();
        Aabb3d incomingBounds = MinecraftGeometryAdapter.toAabb3d(entity.getBoundingBox());
        double maxStep = entity.maxUpStep();
        TravelCapturePlan[] capture = new TravelCapturePlan[1];
        var op = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(entity, GravityOperationType.TRAVEL,
                samplePoint, 1.0D, (sample, proposedFrame) -> {
                    // Bound every possible look/reference orientation, including a
                    // geometry transition selecting the installed fallback frame.
                    Vec3 aerodynamicAcceleration =
                            entity.isNoGravity()
                                    ? Vec3.ZERO
                                    : sample.accelerationVector();

                    double aerodynamicBound = elytra
                            ? ElytraAerodynamics.maximumRequestMagnitude(
                            incomingVelocity,
                            aerodynamicAcceleration
                    )
                            : 0.0D;
                    capture[0] = aerodynamicBound > 0 ? TravelCapturePlan.buildIsotropic(
                            incomingBounds, Vec3.ZERO, aerodynamicBound, maxStep) : ordinary;
                    return capture[0].domain();
                });
        try {
            var character = CharacterControlRuntime.resolve(
                    entity,
                    op.frame(),
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
                    cc.sighs.gravityengine.look.PlayerLookIntegration.capture(entity, op.frame()));
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
                GravityFrameAccess.authoritativeFrame(entity);

        Vec3 self = cc.sighs.gravityengine.gravity.integration.GravityMovementInput.calculateRelativeMovement(entity, speed, input, frame);
        GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime()
                .recordSelfWalk(entity.level().getGameTime(), self);
        return entity.getDeltaMovement().add(self);
    }

    /** Runs at Vanilla's accepted jump boundary, before its event callback. */
    public static void commitJump(LivingEntity entity, Vec3 before) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        runtime.recordSelfWalk(entity.level().getGameTime(), entity.getDeltaMovement().subtract(before));
        runtime.clearRestingContactSnapshot();
        if (entity instanceof Player player) {
            ((CharacterControlAccess) player).gravityengine$characterControl().invalidatePlan();
        }
    }

    public static TravelCapturePlan captureTravelPlan(
            LivingEntity entity,
            Vec3 effectiveTravelInput
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(
                effectiveTravelInput,
                "effectiveTravelInput"
        );
        TravelCapturePlan.requireFinite(effectiveTravelInput, "effectiveTravelInput");

        Aabb3d bounds = MinecraftGeometryAdapter.toAabb3d(
                entity.getBoundingBox()
        );
        Vec3 currentVelocity = entity.getDeltaMovement();
        TravelCapturePlan.requireFinite(currentVelocity, "currentVelocity");

        double speedCap = entity.onGround()
                ? entity.getSpeed() * TravelCapturePlan.MAX_GROUNDED_SPEED_FACTOR
                : GravityLivingAccess.cast(entity)
                .gravityengine$getFlyingSpeed();

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
                Math.abs(speedCap) * normalizedInputMagnitude;

        return TravelCapturePlan.buildIsotropic(
                bounds,
                currentVelocity,
                maxLocomotionContribution,
                entity.maxUpStep()
        );
    }

    /** Vanilla owns policy; this pre-move probe borrows the movement scene and
     * exact character body. It publishes no grounding/support history. */
    public static Vec3 applyPlayerSneakEdge(
            Player player, Vec3 displacement, net.minecraft.world.entity.MoverType mover,
            boolean stayingOnGroundSurface
    ) {
        var runtime = GravityEntityAccess.cast(player).gravityengine$gravityComponent().runtime();
        GravityFrame frame = runtime.activeFrame();
        var step = ((CharacterControlAccess) player).gravityengine$characterControl().at(player.tickCount);
        if (!sneakEdgeEligible(mover, player.getAbilities().flying,
                frame.worldToLocal(displacement).y, stayingOnGroundSurface,
                step != null && step.ownsDescendInput())) return displacement;
        var operation = runtime.collisionOperation();
        if (operation == null || operation.frame() != frame) {
            throw new IllegalStateException("sneak-edge requires the borrowed movement frame and scene");
        }
        CollisionBody body = GravityEntityGeometry.body(player, frame);
        float stepHeight = player.maxUpStep();
        // Entity.move's checkFallDamage operand already accumulates gravity-relative
        // locomotion vertical (GravityMoveResult.fallDistanceVertical()).
        if (!isAboveGround(player.onGround(), player.fallDistance, stepHeight, body,
                frame, operation.scene())) {
            return displacement;
        }
        return SneakEdgePreventionService.constrainTangentMovement(frame, body, displacement,
                SneakEdgePreventionService.sceneProbe(operation.scene(), frame, stepHeight));
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
