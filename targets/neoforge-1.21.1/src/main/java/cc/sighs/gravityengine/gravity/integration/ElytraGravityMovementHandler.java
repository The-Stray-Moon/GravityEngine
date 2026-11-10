package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.movement.ElytraAerodynamics;
import net.minecraft.world.phys.Vec3;

/** Aerodynamic world velocity only; Vanilla owns movement, damage, landing and animation. */
public final class ElytraGravityMovementHandler {
    private ElytraGravityMovementHandler() {}

    public static Vec3 velocity(GravityTravelContext context, double selectedGravity) {
        var entity = context.entity();
        Vec3 acceleration =
                MinecraftMathAdapter.toMinecraft(
                        context.sample().accelerationVector()
                );
        if (selectedGravity == 0.0D) acceleration = Vec3.ZERO;
        else if (selectedGravity < acceleration.length()) acceleration = acceleration.scale(selectedGravity / acceleration.length());
        return MinecraftMathAdapter.toMinecraft(
                ElytraAerodynamics.step(
                        MinecraftMathAdapter.toVec3d(
                                entity.getDeltaMovement()),
                        context.look().forward(),
                        context.frame().up(),
                        MinecraftMathAdapter.toVec3d(acceleration),
                        false));
    }
}
