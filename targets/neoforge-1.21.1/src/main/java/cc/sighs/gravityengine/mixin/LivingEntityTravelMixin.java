package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.movement.CharacterLocomotionTechnique;
import cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/**
 * 1.21.1 / 21.1.249 travel bridge. The original method selects effects,
 * friction, damage and landing policy. One invocation owns one captured
 * operation; only local scalar/vector carriers are reference-space values.
 * All actual entity velocity reads/writes and Entity.move remain world-space.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
    /**
     * Only bridges travel to its native movement helper, which is a separate
     * Java invocation and cannot borrow travel's @Share local. Save/restore in
     * the enclosing finally makes nested travel reentrant; nothing survives a
     * travel call or enters runtime history. It is the already-validated input,
     * not another mutable look sample.
     */
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityengine$travelContribution;

    @WrapOperation(method = "handleRelativeFrictionAndCalculateMovement", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void gravityengine$capturedInput(LivingEntity entity, float speed, Vec3 input, Operation<Void> original) {
        Vec3 contribution = gravityengine$travelContribution;
        if (contribution == null) {
            original.call(entity, speed, input);
        } else {
            cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime()
                    .recordSelfWalk(entity.level().getGameTime(), contribution);
            entity.setDeltaMovement(entity.getDeltaMovement().add(contribution));
        }
    }

    @WrapOperation(method = "handleRelativeFrictionAndCalculateMovement", at = @At(value = "NEW",
            target = "(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$referenceLift(double x, double y, double z, Operation<Vec3> original) {
        var entity = (LivingEntity) (Object) this;
        return cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomLocomotion(entity)
                ? GroundAirGravityMovementHandler.verticalVelocity(entity, y) : original.call(x, y, z);
    }

    @WrapMethod(method = "travel", require = 1)
    private void gravityengine$travelScope(Vec3 input, Operation<Void> original,
            @Share("travel") LocalRef<GravityTravelContext> shared,
            @Share("friction") LocalFloatRef friction,
            @Share("groundInput") LocalRef<Vec3> groundInput) {
        LivingEntity entity = (LivingEntity) (Object) this;
        Vec3 previousContribution = gravityengine$travelContribution;
        gravityengine$travelContribution = null;
        try (var context = LivingGravityIntegration.openTravel(entity, input)) {
            shared.set(context);
            if (context == null) {
                original.call(input);
            } else if (context.characterPlan().locomotion() == CharacterLocomotionTechnique.FREE_3D) {
                Free3dCharacterMovementHandler.travel(context);
            } else {
                if (context.characterPlan().locomotion() == CharacterLocomotionTechnique.GROUND_AIR
                        && cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(entity)) {
                    try {
                        friction.set(GroundAirGravityMovementHandler.friction(entity, context.runtime()));
                    } catch (CollisionSceneCoverageException uncovered) {
                        cc.sighs.gravityengine.gravity.debug.GravityDebugLog.log(entity,
                                "material-coverage-fail-closed", "%s", uncovered.getMessage());
                        entity.calculateEntityAnimation(entity instanceof net.minecraft.world.entity.animal.FlyingAnimal);
                        return;
                    }
                    groundInput.set(GroundAirGravityMovementHandler.inputContribution(context, context.input(), friction.get()));
                    if (context.capturePlan() != null && !context.capturePlan().covers(
                            entity.getDeltaMovement().add(groundInput.get()))) {
                        entity.calculateEntityAnimation(entity instanceof net.minecraft.world.entity.animal.FlyingAnimal);
                        return;
                    }
                }
                original.call(context.input());
            }
        } finally {
            gravityengine$travelContribution = previousContribution;
            shared.set(null);
        }
    }

    /** Native effects selected slot 2 gravity before entering the Elytra branch. */
    @Inject(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V"), cancellable = true, require = 1)
    private void gravityengine$elytraOperands(Vec3 input, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
            @Local(index = 2) double magnitude, @Share("travel") LocalRef<GravityTravelContext> shared,
            @Share("elytraVelocity") LocalRef<Vec3> calculated) {
        var context = shared.get();
        if (context == null) return;
        Vec3 velocity = ElytraGravityMovementHandler.velocity(context, magnitude);
        if (context.capturePlan() != null && !context.capturePlan().covers(velocity)) {
            context.entity().calculateEntityAnimation(context.entity() instanceof net.minecraft.world.entity.animal.FlyingAnimal);
            ci.cancel();
        } else calculated.set(velocity);
    }

    /** Native animation interpolation consumes gravity-tangent displacement. */
    @WrapOperation(method = "calculateEntityAnimation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;length(DDD)D"), require = 1)
    private double gravityengine$animationDistance(double x, double y, double z, Operation<Double> original,
            @Local(argsOnly = true) boolean includeHeight) {
        var entity = (LivingEntity) (Object) this;
        if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(entity)) {
            return original.call(x, y, z);
        }
        Vec3 local = cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBlockResponse.local(entity,
                new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo));
        return original.call(local.x, includeHeight ? local.y : 0.0D, local.z);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getGravity()D"), require = 1)
    private double gravityengine$gravityMagnitude(LivingEntity entity, Operation<Double> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        var context = shared.get();
        return context == null ? original.call(entity)
                : entity.isNoGravity() ? 0.0D : context.sample().accelerationVector().length();
    }

    /** The first Vec3.y read selects Vanilla slow-falling, before any travel branch. */
    @WrapOperation(method = "travel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/phys/Vec3;y:D", ordinal = 0), require = 1)
    private double gravityengine$fallingVertical(Vec3 velocity, Operation<Double> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        return shared.get() == null ? original.call(velocity)
                : shared.get().frame().worldToLocal(velocity).y;
    }

    /**
     * These three Vanilla positions feed the material expression and chunk
     * availability only. No-support uses the actor chunk location as a carrier,
     * never as an invented support block. The material itself is scene-owned.
     */
    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;"), require = 3)
    private BlockPos gravityengine$materialPosition(LivingEntity entity, Operation<BlockPos> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        return shared.get() == null
                || !cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(entity)
                ? original.call(entity) : entity.blockPosition();
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1)
    private BlockState gravityengine$materialCarrier(Level level, BlockPos pos, Operation<BlockState> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        // Exact collision consumes captured support material. Native collision keeps
        // Vanilla's material lookup, including stale support after teleport until move updates it.
        return shared.get() == null
                || !cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(shared.get().entity())
                ? original.call(level, pos) : Blocks.AIR.defaultBlockState();
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F"), require = 1)
    private float gravityengine$sceneFriction(BlockState state, LevelReader level, BlockPos pos, Entity entity,
            Operation<Float> original, @Share("travel") LocalRef<GravityTravelContext> shared,
            @Share("friction") LocalFloatRef friction) {
        return shared.get() == null
                || !cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(entity)
                ? original.call(state, level, pos, entity) : friction.get();
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;hasChunkAt(Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean gravityengine$capturedAvailability(Level level, BlockPos pos, Operation<Boolean> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        // Custom scene coverage was checked before moving. Do not enter the
        // incompatible world-Y missing-chunk velocity fallback after that move.
        return shared.get() != null
                && cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomCollision(shared.get().entity())
                || original.call(level, pos);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$referenceVelocity(LivingEntity entity, Vec3 input, float friction,
            Operation<Vec3> original, @Share("travel") LocalRef<GravityTravelContext> shared,
            @Share("groundInput") LocalRef<Vec3> groundInput) {
        Vec3 previous = gravityengine$travelContribution;
        gravityengine$travelContribution = groundInput.get();
        try {
            Vec3 world = original.call(entity, input, friction);
            return shared.get() == null ? world : shared.get().frame().worldToLocal(world);
        } finally {
            gravityengine$travelContribution = previous;
        }
    }

    /**
     * Exact bytecode seam: ground/air gravity branch's first DSTORE after
     * hasChunkAt (slot 10), before shouldDiscardFriction. Slot 9 is the
     * post-move vector and slot 2 is Vanilla's already-capped gravity.
     * Levitation's earlier store is outside this slice and stays Vanilla-owned.
     */
    @ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0), index = 10,
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;hasChunkAt(Lnet/minecraft/core/BlockPos;)Z"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;shouldDiscardFriction()Z")), require = 1)
    private double gravityengine$vectorAcceleration(double nativeVertical,
            @Local(index = 9) LocalRef<Vec3> movement, @Local(index = 2) double magnitude,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        if (shared.get() == null) return nativeVertical;
        Vec3 accelerated = GroundAirGravityMovementHandler.accelerate(shared.get(), movement.get(), magnitude);
        movement.set(accelerated);
        return accelerated.y;
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;shouldDiscardFriction()Z"), require = 1)
    private boolean gravityengine$contactBeforeFriction(LivingEntity entity, Operation<Boolean> original,
            @Local(index = 9) LocalRef<Vec3> movement, @Local(index = 10) LocalDoubleRef vertical,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        if (shared.get() != null) {
            Vec3 value = movement.get();
            Vec3 constrained = GroundAirGravityMovementHandler.beforeFriction(shared.get(),
                    new Vec3(value.x, vertical.get(), value.z));
            movement.set(constrained);
            vertical.set(constrained.y);
        }
        return original.call(entity);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;shouldDiscardFriction()Z")), require = 2)
    private void gravityengine$worldVelocityCommit(LivingEntity entity, double x, double y, double z,
            Operation<Void> original, @Share("travel") LocalRef<GravityTravelContext> shared) {
        Vec3 world = shared.get() == null ? new Vec3(x, y, z)
                : GroundAirGravityMovementHandler.worldVelocity(shared.get(), new Vec3(x, y, z));
        original.call(entity, world.x, world.y, world.z);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;", ordinal = 0)), require = 1)
    private void gravityengine$elytraVelocity(LivingEntity entity, Vec3 velocity, Operation<Void> original,
            @Share("elytraVelocity") LocalRef<Vec3> calculated) {
        original.call(entity, calculated.get() == null ? velocity : calculated.get());
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D"), require = 2)
    private double gravityengine$elytraCollisionSpeed(Vec3 velocity, Operation<Double> original,
            @Share("travel") LocalRef<GravityTravelContext> shared) {
        return shared.get() == null ? original.call(velocity)
                : ElytraAerodynamics.tangentSpeed(velocity, shared.get().frame().up());
    }
}
