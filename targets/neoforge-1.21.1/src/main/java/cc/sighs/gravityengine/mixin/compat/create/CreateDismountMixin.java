package cc.sighs.gravityengine.mixin.compat.create;

import cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Both server dismountTo and Sable's local-player seat path consume this result. */
@Mixin(value={com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
        com.simibubi.create.content.contraptions.actors.seat.SeatEntity.class},remap=false)
public abstract class CreateDismountMixin {
    @WrapMethod(method="getDismountLocationForPassenger",require=1)
    private Vec3 gravityengine$validatedSeatExit(LivingEntity actor,Operation<Vec3> original) {
        if(!CharacterPlacementValidation.owns(actor)) return original.call(actor);
        try(var selection=CharacterPlacementValidation.beginDismountSelection(actor)) {
            try {
                var candidate=original.call(actor);
                if(CharacterPlacementValidation.clearAt(actor,candidate)) return candidate;
                selection.restorePose();return actor.position();
            } catch(RuntimeException | Error failure) {selection.restorePose();throw failure;}
        }
    }
}
