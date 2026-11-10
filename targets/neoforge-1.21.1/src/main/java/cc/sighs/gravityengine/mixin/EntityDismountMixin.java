package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Final native placement also validates fallbacks that skipped DismountHelper. */
@Mixin(Entity.class)
public abstract class EntityDismountMixin {
    @WrapMethod(method="dismountTo",require=1)
    private void gravityengine$dismountPlacement(double x,double y,double z,Operation<Void> original) {
        if((Object)this instanceof LivingEntity actor && CharacterPlacementValidation.owns(actor)
                && !CharacterPlacementValidation.clearAt(actor,new Vec3(x,y,z))) {
            CharacterPlacementValidation.rejectDismountPlacement(actor);
            return;
        }
        original.call(x,y,z);
    }
}
