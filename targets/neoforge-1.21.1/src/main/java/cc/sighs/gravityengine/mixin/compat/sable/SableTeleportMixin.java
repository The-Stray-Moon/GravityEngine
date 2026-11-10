package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"dev.ryanhcode.sable.physics.impl.none.StaticPhysicsPipeline",
        "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline"}, remap = false)
public abstract class SableTeleportMixin {
    @Inject(method = "teleport", at = @At("RETURN"), require = 1)
    private void gravityengine$teleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc rotation, CallbackInfo ci) {
        if (body instanceof SubLevel subLevel) SableRigidCollisionProvider.teleported(subLevel);
    }
}
