package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics;
import net.minecraft.world.phys.Vec3;

/** Aerodynamic world velocity only; Vanilla owns movement, damage, landing and animation. */
public final class ElytraGravityMovementHandler {
    private ElytraGravityMovementHandler() {}

    public static Vec3 velocity(GravityTravelContext context, double selectedGravity) {
        var entity = context.entity();
        Vec3 acceleration = context.sample().accelerationVector();
        if (selectedGravity == 0.0D) acceleration = Vec3.ZERO;
        else if (selectedGravity < acceleration.length()) acceleration = acceleration.scale(selectedGravity / acceleration.length());
        return ElytraAerodynamics.step(entity.getDeltaMovement(),
                context.look().forward(),
                context.frame().up(),
                acceleration, false);
    }
}
