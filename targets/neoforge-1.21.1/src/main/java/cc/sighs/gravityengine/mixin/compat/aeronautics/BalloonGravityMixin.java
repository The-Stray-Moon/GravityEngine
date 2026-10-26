package cc.sighs.gravityengine.mixin.compat.aeronautics;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon", remap = false)
public abstract class BalloonGravityMixin {
    @Shadow private double totalLift;
    @Shadow @Final private static Vector3d worldCenter;
    @Shadow @Final private static Vector3d localAveragePosition;
    @Shadow @Final private static Vector3d gradient;
    @Shadow @Final private static Vector3d gravity;
    @WrapOperation(method="applyForces",at=@At(value="INVOKE",target="Lorg/joml/Vector3d;lengthSquared()D"),require=1)
    private double gravityengine$zeroNativeGravity(Vector3d value,Operation<Double> original,@Local(index=4) ServerSubLevel body) {
        double squared=original.call(value);
        // Retain pressure eligibility, but let a nonzero GE field contribute
        // even in a dimension whose native gravity is zero.
        return squared==0 && SableGravityBridge.hasStep(body)?1:squared;
    }
    @WrapOperation(method = "applyForces", at = @At(value = "INVOKE", target =
            "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyLinearAndAngularImpulse(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"), require = 1)
    private void gravityengine$lift(dev.ryanhcode.sable.api.physics.force.ForceTotal total,
            Vector3dc force,Vector3dc torque,Operation<Void> original,@Local(index=4) ServerSubLevel body,
            @Local(index=7) double pressure,@Local(index=3) int capacity,@Local(argsOnly=true) double seconds) {
        original.call(total,force,torque);
        if(!SableGravityBridge.hasStep(body)) return;
        var pose=body.logicalPose();
        var sampled=pose.orientation().transform(gravity,new Vector3d());
        SableGravityBridge.liftGravity(body,worldCenter,sampled,seconds);
        pose.orientation().transformInverse(sampled);
        var difference=sampled.sub(gravity).mul(-totalLift);
        // At this seam native gradient already includes the translated outer
        // product. Reuse its pressure moment without evaluating providers twice.
        var torqueDifference=new Vector3d(localAveragePosition).mul(pressure)
                .fma(1.0/capacity,gradient).cross(difference).mul(seconds);
        SableGravityBridge.stageLocalImpulse(body,difference.mul(pressure*seconds),torqueDifference);
    }
}
