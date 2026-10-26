package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SablePhysicsBaselineMixin {
    @WrapOperation(method = "initialize", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;init(Lorg/joml/Vector3dc;D)V"), require = 1)
    private void gravityengine$baseline(PhysicsPipeline pipeline, Vector3dc gravity,
                                       double drag, Operation<Void> original) {
        SableGravityBridge.initialized((SubLevelPhysicsSystem)(Object)this, gravity.x(),gravity.y(),gravity.z());
        original.call(pipeline, gravity, drag);
    }
}
