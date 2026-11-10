package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.ArmorStandIntegration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** All four native Entity consumers of noPhysics, including both subjects in push. */
@Mixin(Entity.class)
public abstract class ArmorStandNativePhysicsMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="setRemoved",at=@At("HEAD"),require=1)
    private void gravityengine$removed(Entity.RemovalReason reason, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((Object)this instanceof cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess stand)
            stand.gravityengine$clearPendingDimensions();
    }
    @org.spongepowered.asm.mixin.injection.Inject(method="setLevel",at=@At("HEAD"),require=1)
    private void gravityengine$changedLevel(net.minecraft.world.level.Level level, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (((Entity)(Object)this).level()!=level
                && (Object)this instanceof cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess stand)
            stand.gravityengine$clearPendingDimensions();
    }
    @WrapOperation(method={"move","push(Lnet/minecraft/world/entity/Entity;)V","isInWall","refreshDimensions"},
            at=@At(value="FIELD",target="Lnet/minecraft/world/entity/Entity;noPhysics:Z",opcode=180),require=5,allow=5)
    private boolean gravityengine$nativeDerivedPhysics(Entity entity, Operation<Boolean> original) {
        return ArmorStandIntegration.nativeNoPhysics(entity,original.call(entity));
    }
}
