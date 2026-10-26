package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Create's client seat packet leaves a native one-shot position tag. Its later
 * absMoveTo bypasses dismountTo and must validate the same projected body.
 * Create still consumes the tag and clears its ground flag, even on rejection. */
@Mixin(value=ContraptionHandler.class,remap=false)
public abstract class CreateDeferredDismountMixin {
    @WrapOperation(method="entitiesWhoJustDismountedGetSentToTheRightLocation",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/LivingEntity;absMoveTo(DDDFF)V"),require=1)
    private static void gravityengine$deferredPlacement(LivingEntity actor,double x,double y,double z,
                                                       float yaw,float pitch,Operation<Void> original) {
        if(!CharacterPlacementValidation.owns(actor)) {
            original.call(actor,x,y,z,yaw,pitch);return;
        }
        var anchor=SableMovementCompatibility.projectOut(actor,new Vec3(x,y,z));
        if(CharacterPlacementValidation.clearAt(actor,anchor))
            original.call(actor,anchor.x,anchor.y,anchor.z,yaw,pitch);
    }
}
