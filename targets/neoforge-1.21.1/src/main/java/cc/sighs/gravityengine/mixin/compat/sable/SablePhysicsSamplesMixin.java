package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SablePhysicsSamplesMixin {
    @Inject(method = "updateAllPoses", at = @At("RETURN"), require = 1)
    private void gravityengine$sample(ServerSubLevelContainer container, CallbackInfo ci) {
        var system = (SubLevelPhysicsSystem)(Object)this;
        for (var body : container.getAllSubLevels()) if (!body.isRemoved())
            SableRigidCollisionProvider.physicsSample(body, system.getPartialPhysicsTick(), system);
    }
}
