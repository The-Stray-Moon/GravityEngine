package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native primitive transform writes invalidate only derived Create bounds. */
@Mixin(Entity.class)
public abstract class CreateEntityTransformMixin {
    @Inject(method={"setPosRaw(DDD)V","setXRot(F)V","setYRot(F)V"},at=@At("RETURN"),require=1)
    private void gravityengine$transform(CallbackInfo ci) {
        CreateRigidCollisionProvider.motionChanged((Entity)(Object)this);
    }
}
