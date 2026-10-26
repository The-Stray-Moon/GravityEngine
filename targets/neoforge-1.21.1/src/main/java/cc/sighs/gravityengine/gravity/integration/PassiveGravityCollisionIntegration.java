package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.CombinedVectorRoute;
import cc.sighs.gravityengine.gravity.collision.PassiveGravityMoveResult;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.CollisionOperationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/** Minecraft adapter for PASSIVE_SUPPORT using the entity's exact vanilla AABB. */
public final class PassiveGravityCollisionIntegration {
    private PassiveGravityCollisionIntegration() {}

    public static Vec3 collide(Entity entity, Vec3 movement) {
        PassiveGravityMoveResult result =
                resolve(entity, movement);

        GravityOperationState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();

        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity && result.indeterminate())
            throw new cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException("indeterminate falling block movement");
        runtime.setCurrentPassiveMoveResult(result);
        return MinecraftMathAdapter.toMinecraft(
                result.appliedMovement());
    }

    public static Vec3 collideUnpublished(
            Entity entity,
            Vec3 movement
    ) {
        PassiveGravityMoveResult result =
                resolve(entity, movement);

        return MinecraftMathAdapter.toMinecraft(
                result.appliedMovement()
        );
    }

    private static PassiveGravityMoveResult resolve(
            Entity entity,
            Vec3 movement
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(movement, "movement");
        GravityOperationState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();
        CollisionOperationContext operation = runtime.collisionOperation();
        if (operation == null) {
            throw new IllegalStateException(
                    "PASSIVE_SUPPORT collision requires an active movement scope"
            );
        }
        return CombinedVectorRoute.resolve(
                OrientedBox.axisAligned(
                        MinecraftCollisionGeometryAdapter.toAabb3d(
                                entity.getBoundingBox())),
                MinecraftMathAdapter.toVec3d(movement),
                runtime.activeFrame(),
                operation.scene(),
                operation.geometryContext(),
                body -> runtime.activeFrame()
        );
    }

    public static boolean isAuthoritative(PassiveGravityMoveResult result) {
        return result != null && !result.indeterminate();
    }

    /** Accepted locomotion excludes overlap recovery/depenetration. */
    public static Vec3 locomotionMovement(PassiveGravityMoveResult result) {
        Objects.requireNonNull(result, "result");
        return MinecraftMathAdapter.toMinecraft(
                result.appliedMovement()
                        .subtract(result.recoveryMovement()));
    }

    /** Fall movement uses accepted locomotion along the frozen gravity up. */
    public static double fallDistanceVertical(PassiveGravityMoveResult result, cc.sighs.gravityengine.api.math.Vec3d capturedEnvironmentalUp) {
        Objects.requireNonNull(result, "result");
        return MinecraftMathAdapter.toVec3d(locomotionMovement(result))
                .dot(capturedEnvironmentalUp);
    }

    /** A downward supporting collision is landing evidence even without endpoint support. */
    public static boolean landedOnPhysicalSupport(PassiveGravityMoveResult result) {
        return isAuthoritative(result) && result.blockedDown();
    }

    public static Vec3 projectVelocity(
            PassiveGravityMoveResult result,
            Vec3 velocity
    ) {
        if (result == null || result.indeterminate()) return velocity;
        return MinecraftMathAdapter.toMinecraft(
                CombinedVectorRoute.projectVelocity(
                        MinecraftMathAdapter.toVec3d(velocity),
                        result.blockingNormals()));
    }

    public static Optional<BlockPos> supportBlock(
            PassiveGravityMoveResult result
    ) {
        return result == null || result.indeterminate()
                ? Optional.empty()
                : result.supportBlock()
                        .map(MinecraftMathAdapter::toBlockPos);
    }

    /** Applies vanilla scalar coefficients in the frozen gravity frame. */
    public static Vec3 gravityLocalScale(
            Entity entity,
            Vec3 velocity,
            double tangentScale,
            double verticalScale
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(velocity, "velocity");

        PassiveGravityMoveResult result = completedThisTick(entity);

        if (result == null
                || result.indeterminate()
                || !result.supported()) {
            return velocity;
        }

        Vec3 up = MinecraftMathAdapter.toMinecraft(
                result.frame().up());
        double vertical = velocity.dot(up);
        Vec3 tangent = velocity.subtract(
                up.scale(vertical)
        );

        return tangent.scale(tangentScale).add(
                up.scale(vertical * verticalScale)
        );
    }

    public static boolean hasSupportedPassiveContact(Entity entity) {
        PassiveGravityMoveResult result = completedThisTick(entity);

        return result != null
                && !result.indeterminate()
                && result.supported();
    }

    private static PassiveGravityMoveResult completedThisTick(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        GravityOperationState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();

        return runtime.isInMove() ? runtime.currentPassiveMoveResult() : runtime.lastPassiveMoveResult(
                entity.level().getGameTime()
        );
    }
}
