package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gravity and current-move contact facts only; Vanilla owns the complete lifecycle. */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityGravityMixin implements cc.sighs.gravityengine.gravity.minecraft.access.GravityFallingBlockAccess {
    @org.spongepowered.asm.mixin.Unique
    private static final net.minecraft.network.syncher.EntityDataAccessor<net.minecraft.nbt.CompoundTag> gravityengine$ballistic =
            net.minecraft.network.syncher.SynchedEntityData.defineId(FallingBlockEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.COMPOUND_TAG);

    @org.spongepowered.asm.mixin.injection.Inject(method = "defineSynchedData", at = @At("TAIL"), require = 1)
    private void gravityengine$defineBallistic(net.minecraft.network.syncher.SynchedEntityData.Builder builder,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        builder.define(gravityengine$ballistic, new net.minecraft.nbt.CompoundTag());
    }
    @Override public FallingBlockBallisticSnapshot gravityengine$ballisticSnapshot() {
        return FallingBlockBallisticSnapshot.decode(((FallingBlockEntity) (Object) this).getEntityData().get(gravityengine$ballistic));
    }
    @Override public void gravityengine$publishBallisticSnapshot(FallingBlockBallisticSnapshot snapshot) {
        var entity = (FallingBlockEntity) (Object) this;
        if (entity.level().isClientSide) throw new IllegalStateException("client cannot publish ballistic authority");
        if (!snapshot.samePhysics(gravityengine$ballisticSnapshot())) entity.getEntityData().set(gravityengine$ballistic, snapshot.encode());
    }

    @WrapMethod(method = "tick", require = 1)
    private void gravityengine$tickScope(Operation<Void> original) {
        FallingBlockEntity entity = (FallingBlockEntity) (Object) this;
        try (var tick = FallingBlockTickIntegration.beginTick(entity)) {
            GravityOperation scope;
            try {
                scope = FallingBlockTickIntegration.open(entity);
            } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                    | cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException failure) {
                FallingBlockTickIntegration.unavailable(entity, failure);
                scope = null;
            }
            try (var ignored = scope) { original.call(); }
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void gravityengine$observeMove(FallingBlockEntity entity, MoverType type,
            Vec3 movement, Operation<Void> original) {
        try { original.call(entity, type, movement); }
        catch (FallingBlockTickIntegration.MoveUnavailable failure) {
            FallingBlockTickIntegration.retryNativeMove(entity, failure);
            original.call(entity, type, entity.getDeltaMovement());
        }
        FallingBlockTickIntegration.afterMove(entity);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;applyGravity()V"), require = 1)
    private void gravityengine$gravity(FallingBlockEntity entity, Operation<Void> original) {
        if (!FallingBlockTickIntegration.applyGravity(entity, () -> original.call(entity))) original.call(entity);
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/item/"
                                    + "FallingBlockEntity;onGround()Z"
            ),
            require = 1
    )
    private boolean gravityengine$landing(
            FallingBlockEntity entity,
            Operation<Boolean> original
    ) {
        boolean vanilla =
                original.call(entity);

        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(entity);

        return vanilla
                || impact != null
                && impact.landed();
    }

    /**
     * Rotate Vanilla FallingBlock's:
     *
     *     velocity.multiply(0.7, -0.5, 0.7)
     *
     * into the selected discrete gravity frame.
     *
     * <p>For Direction.DOWN this is mathematically identical to Vanilla.
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/phys/Vec3;"
                                    + "multiply(DDD)"
                                    + "Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 1,
            allow = 1
    )
    private Vec3 gravityengine$installationVelocity(
            Vec3 velocity,
            double x,
            double y,
            double z,
            Operation<Vec3> original
    ) {
        FallingBlockEntity entity =
                (FallingBlockEntity) (Object) this;

        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(entity);

        if (impact == null
                || !impact.landed()
                || Math.abs(x - z) > 1.0E-12D) {
            return original.call(
                    velocity,
                    x,
                    y,
                    z
            );
        }

        var axis =
                impact.down().getNormal();

        Vec3 down =
                new Vec3(
                        axis.getX(),
                        axis.getY(),
                        axis.getZ()
                );

        double alongDown =
                velocity.dot(down);

        Vec3 tangent =
                velocity.subtract(
                        down.scale(alongDown)
                );

        /*
         * x/z are Vanilla's tangential multiplier (0.7).
         * y is Vanilla's gravity-axis multiplier (-0.5).
         */
        return tangent.scale(x)
                .add(
                        down.scale(
                                alongDown * y
                        )
                );
    }

    /**
     * Static collision identity owns the exact installation cell.
     *
     * Never recover this with floor(entity.position): the entity position is a
     * continuous collision result and may sit on either side of an integer
     * boundary.
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/entity/item/"
                                    + "FallingBlockEntity;"
                                    + "blockPosition()"
                                    + "Lnet/minecraft/core/BlockPos;"
            ),
            require = 1,
            allow = 1
    )
    private BlockPos gravityengine$placementPosition(
            FallingBlockEntity entity,
            Operation<BlockPos> original
    ) {
        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(entity);

        return impact != null
                && impact.installable()
                ? impact.placementPos()
                : original.call(entity);
    }

    /**
     * Replace only the installation-support operand corresponding to Vanilla's
     * blockpos.below().
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/core/BlockPos;"
                                    + "below()"
                                    + "Lnet/minecraft/core/BlockPos;"
            ),
            require = 1,
            allow = 1
    )
    private BlockPos gravityengine$placementSupport(
            BlockPos position,
            Operation<BlockPos> original
    ) {
        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(
                                (FallingBlockEntity)
                                        (Object) this
                        );

        if (impact == null
                || !impact.installable()
                || !position.equals(
                impact.placementPos()
        )) {
            return original.call(position);
        }

        return impact.supportPos();
    }

    /**
     * Keep Vanilla replacement policy, but expose the actual gravity-relative
     * installation face to DirectionalPlaceContext.
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/block/state/"
                                    + "BlockState;"
                                    + "canBeReplaced("
                                    + "Lnet/minecraft/world/item/context/"
                                    + "BlockPlaceContext;"
                                    + ")Z"
            ),
            require = 1,
            allow = 1
    )
    private boolean gravityengine$placementReplacement(
            BlockState state,
            BlockPlaceContext context,
            Operation<Boolean> original
    ) {
        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(
                                (FallingBlockEntity)
                                        (Object) this
                        );

        if (impact == null
                || !impact.installable()
                || !context.getClickedPos()
                .equals(
                        impact.placementPos()
                )) {
            return original.call(
                    state,
                    context
            );
        }

        FallingBlockEntity entity =
                (FallingBlockEntity)
                        (Object) this;

        return original.call(
                state,
                new DirectionalPlaceContext(
                        entity.level(),
                        impact.placementPos(),
                        impact.down(),
                        ItemStack.EMPTY,
                        impact.down()
                                .getOpposite()
                )
        );
    }

    /**
     * Dynamic support and non-closed static conversion both use Vanilla's existing
     * failed-installation transaction.
     *
     * <p>Crucially, this occurs before setBlock(), so no transient FallingBlock
     * BlockState is ever published to clients.
     */
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/world/level/block/state/"
                                    + "BlockState;"
                                    + "canSurvive("
                                    + "Lnet/minecraft/world/level/LevelReader;"
                                    + "Lnet/minecraft/core/BlockPos;"
                                    + ")Z"
            ),
            require = 1
    )
    private boolean gravityengine$installationSurvival(
            BlockState state,
            LevelReader level,
            BlockPos position,
            Operation<Boolean> original
    ) {
        FallingBlockLandingContext impact =
                FallingBlockTickIntegration
                        .landing(
                                (FallingBlockEntity)
                                        (Object) this
                        );

        if (impact != null
                && impact.landed()) {
            /*
             * Dynamic/rigid contact cannot install a parent-world block.
             *
             * A static contact whose prospective BlockState would immediately
             * re-enter FallingBlockEntity is also rejected here.
             */
            if (!impact.installable()
                    || !impact.blockConversionAllowed()) {
                return false;
            }

            /*
             * The collision identity, not floor(entity.position), owns the target
             * cell.
             */
            if (!position.equals(
                    impact.placementPos()
            )) {
                return false;
            }
        }

        return original.call(
                state,
                level,
                position
        );
    }
}
