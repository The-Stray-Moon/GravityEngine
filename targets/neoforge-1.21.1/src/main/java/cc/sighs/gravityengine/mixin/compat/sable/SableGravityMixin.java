package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableGravityMixin implements SableGravityBridge.StepAccess {
    @org.spongepowered.asm.mixin.Unique private SableGravityBridge.ForceStep gravityengine$step;
    public SableGravityBridge.ForceStep gravityengine$forceStep() { return gravityengine$step; }
    public void gravityengine$forceStep(SableGravityBridge.ForceStep step) { gravityengine$step=step; }
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="prePhysicsTick",require=1)
    private void gravityengine$liftEvidence(SubLevelPhysicsSystem system,RigidBodyHandle handle,double seconds,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var body=(ServerSubLevel)(Object)this;
        SableGravityBridge.beginStep(body,seconds);
        boolean succeeded=false;
        try {
            original.call(system,handle,seconds);
            succeeded=true;
        } finally {
            if(!succeeded) SableGravityBridge.discardStep(body);
        }
    }
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="applyQueuedForces",require=1)
    private void gravityengine$gravity(SubLevelPhysicsSystem system, RigidBodyHandle handle, double seconds,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var body=(ServerSubLevel)(Object)this;
        try {
            original.call(system,handle,seconds);
            SableGravityBridge.apply(body,system,handle,seconds);
        } finally {SableGravityBridge.discardStep(body);}
    }
}
