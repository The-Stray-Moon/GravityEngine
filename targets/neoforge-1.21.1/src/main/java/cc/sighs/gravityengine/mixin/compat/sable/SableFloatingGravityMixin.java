package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableFloatingForces;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockCluster;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = FloatingBlockController.class, remap = false)
public abstract class SableFloatingGravityMixin {
    @Shadow @Final private ServerSubLevel subLevel;
    @Shadow @Final private java.util.List<FloatingClusterContainer> containers;
    @Shadow @Final private static Vector3d localGravity;

    @org.spongepowered.asm.mixin.injection.Inject(method="physicsTick",at=@At("RETURN"),require=1)
    private void gravityengine$zeroNativeLift(double partial,double seconds,Vector3dc velocity,Vector3dc spin,
            Vector3d linear,Vector3d angular,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(localGravity.lengthSquared()!=0 || !SableGravityBridge.hasStep(subLevel)) return;
        var difference=new Vector3d();var torque=new Vector3d();
        SableFloatingForces.lift(subLevel,containers,localGravity,seconds,difference,torque);
        SableGravityBridge.stageLocalImpulse(subLevel,difference,torque);
    }

    @WrapOperation(method="physicsTick",at=@At(value="INVOKE",target=
            "Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockController;applyFriction(Ldev/ryanhcode/sable/physics/floating_block/FloatingClusterContainer;Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockCluster;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;Lorg/joml/Vector3d;)V"),require=1)
    private void gravityengine$friction(FloatingBlockController controller,FloatingClusterContainer container,
            FloatingBlockCluster cluster,Vector3dc gravity,Vector3dc linear,Vector3dc angular,
            Vector3d force,Vector3d torque,Operation<Void> original,@Local(argsOnly=true,ordinal=1) double seconds) {
        original.call(controller,container,cluster,gravity,linear,angular,force,torque);
        if(!SableGravityBridge.hasStep(subLevel)) return;
        var sampled=SableFloatingForces.gravity(subLevel,container,cluster,gravity,seconds);
        var predicted=new Vector3d(linear).fma(seconds/2.1,new Vector3d(gravity).sub(sampled));
        var correctedForce=new Vector3d();var correctedTorque=new Vector3d();
        original.call(controller,container,cluster,sampled,predicted,angular,correctedForce,correctedTorque);
        SableGravityBridge.stageLocalImpulse(subLevel,correctedForce.sub(force).mul(seconds),correctedTorque.sub(torque).mul(seconds));
    }

    @WrapOperation(method="physicsTick",at=@At(value="INVOKE",target=
            "Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockController;applyLift(Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;D)V"),require=1)
    private void gravityengine$lift(FloatingBlockController controller,Vector3d gravity,Vector3d linear,
            Vector3d angular,double seconds,Operation<Void> original) {
        var beforeLinear=new Vector3d(linear);var beforeAngular=new Vector3d(angular);
        original.call(controller,gravity,linear,angular,seconds);
        if(!SableGravityBridge.hasStep(subLevel)) return;
        var correctedLinear=new Vector3d();var correctedAngular=new Vector3d();
        SableFloatingForces.lift(subLevel,containers,gravity,seconds,correctedLinear,correctedAngular);
        SableGravityBridge.stageLocalImpulse(subLevel,correctedLinear.sub(new Vector3d(linear).sub(beforeLinear)),
                correctedAngular.sub(new Vector3d(angular).sub(beforeAngular)));
    }
}
