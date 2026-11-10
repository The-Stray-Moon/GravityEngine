package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MergedMassTracker.class, remap = false)
public abstract class SableMassRebaseMixin {
    @WrapOperation(method = "uploadData", at = @At(value = "INVOKE", target =
            "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;teleport(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;Lorg/joml/Vector3dc;Lorg/joml/Quaterniondc;)V"), require = 1)
    private void gravityengine$rebase(PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3dc position,
                                      Quaterniondc rotation, Operation<Void> original) {
        SableRigidCollisionProvider.rebase((SubLevel)body, () -> original.call(pipeline, body, position, rotation));
    }
}
