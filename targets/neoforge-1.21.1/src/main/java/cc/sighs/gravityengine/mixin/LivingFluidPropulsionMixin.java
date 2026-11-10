package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.extensions.ILivingEntityExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge 21.1.256 owns swim-speed eligibility; its directional impulse uses assigned gravity. */
@Mixin(value = ILivingEntityExtension.class, remap = false)
public interface LivingFluidPropulsionMixin {
    @WrapOperation(method = {"jumpInFluid", "sinkInFluid"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 2, allow = 2)
    private Vec3 gravityengine$fluidPropulsion(Vec3 velocity,double x,double y,double z,Operation<Vec3> original) {
        var entity=((ILivingEntityExtension)(Object)this).self();
        var impulse=new Vec3(x,y,z);
        if (GravityInfluencePolicy.usesCustomLocomotion(entity))
            impulse=MinecraftMathAdapter.toMinecraft(GravityFrameAccess.authoritativeFrame(entity)
                    .localToWorld(MinecraftMathAdapter.toVec3d(impulse)));
        return original.call(velocity,impulse.x,impulse.y,impulse.z);
    }
}
