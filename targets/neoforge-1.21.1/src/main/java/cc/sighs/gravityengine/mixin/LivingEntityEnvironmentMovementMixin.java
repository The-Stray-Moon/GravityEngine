package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.GroundAirGravityMovementHandler;
import cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** Native fluid/climbing eligibility, effects and coefficients remain in travel.
 * Only directional operands use the operation's gravity reference. Entity pose
 * and persistent velocity always remain world-space. Audited 21.1.256 bytecode. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEnvironmentMovementMixin {
    @org.spongepowered.asm.mixin.Shadow private java.util.Optional<net.minecraft.core.BlockPos> lastClimbablePos;
    private MovementEnvironmentCapture.Snapshot gravityengine$environment() {
        return gravityengine$environmentOwned()?MovementEnvironmentCapture.current((LivingEntity)(Object)this):null;
    }
    private boolean gravityengine$environmentOwned() {
        return GravityInfluencePolicy.usesCustomLocomotion((LivingEntity)(Object)this);
    }
    private Vec3 gravityengine$local(Vec3 world) {
        return MinecraftMathAdapter.toMinecraft(GravityFrameAccess.authoritativeFrame((LivingEntity)(Object)this)
                .worldToLocal(MinecraftMathAdapter.toVec3d(world)));
    }
    private Vec3 gravityengine$world(Vec3 local) {
        return MinecraftMathAdapter.toMinecraft(GravityFrameAccess.authoritativeFrame((LivingEntity)(Object)this)
                .localToWorld(MinecraftMathAdapter.toVec3d(local)));
    }

    @WrapMethod(method = "onClimbable", require = 1)
    private boolean gravityengine$capturedClimb(Operation<Boolean> original) {
        var environment=gravityengine$environment();
        if(environment==null) return original.call();
        if(environment.climbables().isEmpty()) return false;
        lastClimbablePos=java.util.Optional.of(environment.climbables().getFirst().address());
        return true;
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInWater()Z"), require = 2, allow = 2)
    private boolean gravityengine$capturedWater(LivingEntity entity,Operation<Boolean> original) {
        var environment=gravityengine$environment();
        return environment==null?original.call(entity):environment.fluids().stream().anyMatch(f->f.state().is(net.minecraft.tags.FluidTags.WATER));
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInLava()Z"), require = 1, allow = 1)
    private boolean gravityengine$capturedLava(LivingEntity entity,Operation<Boolean> original) {
        var environment=gravityengine$environment();
        return environment==null?original.call(entity):environment.fluids().stream().anyMatch(f->f.state().is(net.minecraft.tags.FluidTags.LAVA));
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"), require = 1, allow = 1)
    private net.minecraft.world.level.material.FluidState gravityengine$capturedFluid(net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos position,Operation<net.minecraft.world.level.material.FluidState> original) {
        var environment=gravityengine$environment();
        if(environment==null) return original.call(level,position);
        return environment.fluids().isEmpty()?net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                :environment.fluids().getFirst().state();
    }

    @WrapMethod(method = "getFluidFallingAdjustedMovement", require = 1)
    private Vec3 gravityengine$fluidFall(double gravity, boolean falling, Vec3 velocity, Operation<Vec3> original) {
        return gravityengine$environmentOwned()
                ? gravityengine$world(original.call(gravity, falling, gravityengine$local(velocity)))
                : original.call(gravity, falling, velocity);
    }
    @WrapMethod(method = "handleOnClimbable", require = 1)
    private Vec3 gravityengine$climb(Vec3 velocity, Operation<Vec3> original) {
        if(!gravityengine$environmentOwned()) return original.call(velocity);
        var environment=gravityengine$environment();
        var surface=environment==null || environment.climbables().isEmpty()?Vec3.ZERO
                :MinecraftMathAdapter.toMinecraft(environment.climbables().getFirst().materialVelocity());
        return gravityengine$world(original.call(gravityengine$local(velocity.subtract(surface)))).add(surface);
    }
    @WrapOperation(method = "handleOnClimbable", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getInBlockState()Lnet/minecraft/world/level/block/state/BlockState;"), require = 1, allow = 1)
    private net.minecraft.world.level.block.state.BlockState gravityengine$climbingBlock(LivingEntity entity,
            Operation<net.minecraft.world.level.block.state.BlockState> original) {
        var environment=gravityengine$environment();
        return environment==null || environment.climbables().isEmpty()?original.call(entity)
                :environment.climbables().getFirst().state();
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"),
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V")), require = 2, allow = 2)
    private Vec3 gravityengine$fluidDrag(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        if(!gravityengine$environmentOwned()) return original.call(velocity,x,y,z);
        var environment=gravityengine$environment();
        var surface=environment==null?Vec3.ZERO:MinecraftMathAdapter.toMinecraft(environment.fluidMaterialVelocity());
        return gravityengine$world(original.call(gravityengine$local(velocity.subtract(surface)),x,y,z)).add(surface);
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"),
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V")), require = 1, allow = 1)
    private Vec3 gravityengine$lavaGravity(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        var increment=gravityengine$environmentOwned()?gravityengine$world(new Vec3(x,y,z)):new Vec3(x,y,z);
        return original.call(velocity,increment.x,increment.y,increment.z);
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getY()D"),
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V")), require = 4, allow = 4)
    private double gravityengine$fluidHeightDelta(LivingEntity entity, Operation<Double> original) {
        return gravityengine$environmentOwned()?gravityengine$local(entity.position()).y:original.call(entity);
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFree(DDD)Z"), require = 2, allow = 2)
    private boolean gravityengine$fluidExit(LivingEntity entity, double x, double y, double z, Operation<Boolean> original) {
        if (!gravityengine$environmentOwned()) return original.call(entity,x,y,z);
        var velocity=entity.getDeltaMovement();
        var probe=velocity.add(gravityengine$world(new Vec3(0,y-velocity.y,0)));
        return original.call(entity,probe.x,probe.y,probe.z);
    }
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"),
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V")), require = 2, allow = 2)
    private void gravityengine$fluidExitLift(LivingEntity entity,double x,double y,double z,Operation<Void> original) {
        var velocity=gravityengine$environmentOwned()?GroundAirGravityMovementHandler.verticalVelocity(entity,y):new Vec3(x,y,z);
        original.call(entity,velocity.x,velocity.y,velocity.z);
    }
    @WrapOperation(method = "travel", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"),
            slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;checkSlowFallDistance()V")), require = 1, allow = 1)
    private Vec3 gravityengine$fluidClimbLift(double x,double y,double z,Operation<Vec3> original) {
        return gravityengine$environmentOwned()?GroundAirGravityMovementHandler.verticalVelocity((LivingEntity)(Object)this,y):original.call(x,y,z);
    }
    @WrapOperation(method = "jumpInLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 gravityengine$fluidJump(Vec3 velocity,double x,double y,double z,Operation<Vec3> original) {
        var increment=gravityengine$environmentOwned()?gravityengine$world(new Vec3(x,y,z)):new Vec3(x,y,z);
        return original.call(velocity,increment.x,increment.y,increment.z);
    }
}
