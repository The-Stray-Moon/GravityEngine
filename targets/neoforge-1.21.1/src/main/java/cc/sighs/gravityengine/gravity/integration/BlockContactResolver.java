package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Optional;

/** Target-only block behavior lookup. A missing dynamic address is never a world-foot lookup.
 * Geometry/identity belongs to common; live material and callbacks belong to the Level thread.
 * Results are invocation-local: equal geometry does not authorize caching a BlockState. */
public final class BlockContactResolver {
    private BlockContactResolver() {}

    /** Plot positions are absolute Level storage positions, not parent-world positions.
     * The optional rigid pose maps scaled pivot-relative geometry to world space. */
    public record Resolved(net.minecraft.world.level.Level level, BlockPos position, BlockState state, CollisionContext context,
                           GravitySupportContact contact, Optional<RigidPose> rigidPose) {
        public BlockMovementMaterialSnapshot material(Entity entity) {
            return new BlockMovementMaterialSnapshot(state.getFriction(level, position, entity),
                    state.getBlock().getSpeedFactor(), state.is(Blocks.WATER), state.is(Blocks.BUBBLE_COLUMN));
        }
    }

    /**
     * Invocation-local jump material.
     *
     * Empty means native ownership. A value of 1.0 means GE owns the query
     * but has no validated block material.
     */
    public static Optional<Float> jumpFactor(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity)
                || MovementModeIntegration.desired(entity)
                != cc.sighs.gravityengine.gravity.runtime
                .GravityOperationState.MovementMode.GROUND_AIR) {
            return Optional.empty();
        }

        var runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .operationState();

        var persistent = runtime.persistentSupportState();
        boolean dynamicSupport =
                persistent != null && !persistent.staticSupport();

        if (!dynamicSupport
                && !cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy
                .usesExactBodyCollision(entity)) {
            return Optional.empty();
        }

        Optional<GravitySupportContact> contact;

        if (runtime.isInMove()) {
            // Current negative/indeterminate evidence must not revive old support.
            contact = support(entity);
        } else if (persistent != null) {
            contact = Optional.of(new GravitySupportContact(
                    persistent.normal(),
                    cc.sighs.gravityengine.api.math.Vec3d.ZERO,
                    persistent.obstaclePoseAtCapture()
                            .transformPoint(persistent.localAnchor()),
                    persistent.geometryKind(),
                    persistent.identity()
            ));
        } else {
            contact = Optional.empty();
        }

        // Revalidate geometry with the current entity context.
        // Dynamic resolution also validates provider lifecycle/continuity.
        return Optional.of(
                contact.flatMap(c -> resolve(entity, c))
                        .map(c -> c.state().getBlock().getJumpFactor())
                        .orElse(1.0F)
        );
    }

    public static Optional<GravitySupportContact> support(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        var move = runtime.currentMoveResult();
        if (move != null) return move.indeterminate() ? Optional.empty() : move.supportContact();
        var passive = runtime.currentPassiveMoveResult();
        if (passive != null) return passive.indeterminate() ? Optional.empty() : passive.endpointContact();
        var operation = runtime.collisionOperation();
        return operation == null ? Optional.empty() : runtime.stepStartSupport()
                .map(s -> new GravitySupportContact(s.normal(), s.surfaceVelocity(), s.contactPoint(),
                        s.geometryKind(), s.faceIdentity()));
    }

    /** Landing evidence survives walking off later in the same move; it never becomes persistent support. */
    public static Optional<GravitySupportContact> landing(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .operationState();

        var passive = runtime.currentPassiveMoveResult();
        if (passive != null) {
            if (passive.indeterminate()) {
                return Optional.empty();
            }

            /*
             * Match the blocked-down landing semantics used by the passive route.
             * Unrelated wall impacts must not choose the landing material.
             */
            return passive.impacts().stream()
                    .filter(contact -> CombinedVectorRoute.isSupportNormal(
                            contact.normal(),
                            passive.frame()
                    ))
                    .filter(contact ->
                            passive.requestedMovement().dot(contact.normal())
                                    < -CollisionTolerances.ENTERING_PLANE_EPSILON)
                    .findFirst()
                    .map(CombinedVectorRoute::materialContact)
                    .or(passive::endpointContact);
        }

        var move = runtime.currentMoveResult();
        return move == null || move.indeterminate()
                ? Optional.empty()
                : move.movementSupportContact().or(move::supportContact);
    }

    public static Optional<Resolved> resolve(Entity entity, GravitySupportContact contact) {
        var identity = contact.faceIdentity();
        if (identity == null) return Optional.empty();
        if (identity.dynamicSupport()) return SableMovementCompatibility.resolveBlockContact(entity, contact)
                .or(() -> cc.sighs.gravityengine.gravity.integration.compat.create.CreateCompatibility.resolve(entity, contact));
        if (!identity.staticBlockSupport()) return Optional.empty();
        var pos = MinecraftMathAdapter.toBlockPos(identity.block());
        if (!entity.level().hasChunkAt(pos)) return Optional.empty();
        var state = entity.level().getBlockState(pos);
        var context = CollisionContext.of(entity);
        // Revalidate the actual primitive, not merely the block address.
        boolean valid = state.getCollisionShape(entity.level(), pos, context).toAabbs().stream()
                .map(b -> b.move(pos)).anyMatch(b ->
                        cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter
                                .toAabb3d(b).equals(identity.voxelPiece()));
        return valid ? Optional.of(new Resolved(entity.level(), pos, state, context, contact, Optional.empty())) : Optional.empty();
    }

    public static BlockMovementMaterialSnapshot supportMaterial(Entity entity) {
        var contact = support(entity);
        if (contact.isPresent()) return resolve(entity, contact.get()).map(c -> c.material(entity))
                .orElse(BlockMovementMaterialSnapshot.AIR);
        // The native projection can name static support even without a persistent plane witness.
        var access = GravityEntityAccess.cast(entity);
        var operation = access.gravityengine$gravityComponent().operationState().collisionOperation();
        return operation == null ? BlockMovementMaterialSnapshot.AIR : access.gravityengine$getVanillaSupportingBlock()
                .map(MinecraftMathAdapter::toCellPos).flatMap(operation.scene()::movementMaterialAt)
                .orElse(BlockMovementMaterialSnapshot.AIR);
    }

    public static Optional<Resolved> supportBlock(Entity entity) {
        return support(entity).flatMap(c -> resolve(entity, c));
    }

    public static Optional<Resolved> landingBlock(Entity entity) {
        return landing(entity).flatMap(c -> resolve(entity, c));
    }
}
