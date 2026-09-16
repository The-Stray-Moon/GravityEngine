package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.CombinedVectorRoute;
import cc.sighs.gravityengine.gravity.collision.MinecraftGeometryAdapter;
import cc.sighs.gravityengine.gravity.collision.PassiveGravityMoveResult;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState.CollisionOperationContext;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.Optional;

/** Minecraft adapter for PASSIVE_SUPPORT using the entity's exact vanilla AABB. */
public final class PassiveGravityCollisionIntegration {
    private PassiveGravityCollisionIntegration() {}

    public static Vec3 collide(Entity entity, Vec3 movement) {
        PassiveGravityMoveResult result =
                resolve(entity, movement);

        GravityRuntimeState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime();

        runtime.setCurrentPassiveMoveResult(result);
        runtime.setPreResolved(
                movement,
                MinecraftGeometryAdapter.toMinecraft(
                        result.appliedMovement()));
        return MinecraftGeometryAdapter.toMinecraft(
                result.appliedMovement());
    }

    public static Vec3 collideUnpublished(
            Entity entity,
            Vec3 movement
    ) {
        PassiveGravityMoveResult result =
                resolve(entity, movement);

        return MinecraftGeometryAdapter.toMinecraft(
                result.appliedMovement()
        );
    }

    private static PassiveGravityMoveResult resolve(
            Entity entity,
            Vec3 movement
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(movement, "movement");
        GravityRuntimeState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime();
        CollisionOperationContext operation = runtime.collisionOperation();
        if (operation == null) {
            throw new IllegalStateException(
                    "PASSIVE_SUPPORT collision requires an active movement scope"
            );
        }
        return CombinedVectorRoute.resolve(
                OrientedBox.axisAligned(
                        MinecraftGeometryAdapter.toAabb3d(
                                entity.getBoundingBox())),
                MinecraftGeometryAdapter.toJoml(
                        movement, new Vector3d()),
                runtime.activeFrame(),
                operation.scene(),
                operation.geometryContext()
        );
    }

    public static boolean isAuthoritative(PassiveGravityMoveResult result) {
        return result != null && !result.indeterminate();
    }

    /** Accepted locomotion excludes overlap recovery/depenetration. */
    public static Vec3 locomotionMovement(PassiveGravityMoveResult result) {
        Objects.requireNonNull(result, "result");
        return MinecraftGeometryAdapter.toMinecraft(
                result.appliedMovement().sub(result.recoveryMovement()));
    }

    /** Fall movement uses accepted locomotion along the frozen gravity up. */
    public static double fallDistanceVertical(PassiveGravityMoveResult result) {
        Objects.requireNonNull(result, "result");
        return locomotionMovement(result).dot(result.frame().up());
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
        return MinecraftGeometryAdapter.toMinecraft(
                CombinedVectorRoute.projectVelocity(
                        MinecraftGeometryAdapter.toJoml(
                                velocity, new Vector3d()),
                        result.blockingNormals()));
    }

    public static Optional<BlockPos> supportBlock(
            PassiveGravityMoveResult result
    ) {
        return result == null || result.indeterminate()
                ? Optional.empty() : result.supportBlock();
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

        Vec3 up = result.frame().up();
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

    public static boolean hasDefaultDownPassiveFrame(Entity entity) {
        PassiveGravityMoveResult result = completedThisTick(entity);

        /*
         * No completed passive solve means passive arbitrary-contact ownership was
         * not established for the latest move. Vanilla/default-down semantics are
         * therefore the conservative answer for vanilla landing/placement gates.
         */
        return result == null
                || result.frame()
                .down()
                .distanceToSqr(
                        cc.sighs.gravityengine.gravity.GravityState.DEFAULT_DOWN
                )
                <= 1.0E-12D;
    }

    private static PassiveGravityMoveResult completedThisTick(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        GravityRuntimeState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime();

        return runtime.lastPassiveMoveResult(
                entity.level().getGameTime()
        );
    }
}
