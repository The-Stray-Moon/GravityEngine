package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.1 selects a candidate only after native passenger removal.
 * Keep that lifecycle native, while rolling back rejected speculative pose changes. */
@Mixin(LivingEntity.class)
public abstract class LivingDismountMixin {
    @WrapMethod(method="dismountVehicle",require=1)
    private void gravityengine$dismountSelection(Entity vehicle,Operation<Void> original) {
        var actor=(LivingEntity)(Object)this;
        if(!CharacterPlacementValidation.owns(actor)) {original.call(vehicle);return;}
        try(var selection=CharacterPlacementValidation.beginDismountSelection(actor)) {
            try {original.call(vehicle);}
            catch(RuntimeException | Error failure) {selection.restorePose();throw failure;}
        }
    }
}
