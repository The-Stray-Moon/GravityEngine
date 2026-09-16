package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorBridge;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaActorSnapshot;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaProjectileBridge;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Converts player/mob launch angles at the explicit projectile boundary.
 *
 * <p>One {@code shootFromRotation} invocation captures one immutable
 * {@link VanillaActorSnapshot}; launch direction is semantic view, and
 * grounded inherited-vertical removal uses the reference frame.  Vanilla
 * {@code Projectile.shoot}, velocity magnitude, inaccuracy, RNG and known
 * movement inheritance remain Vanilla-owned.</p>
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "shootFromRotation", at = @At("HEAD"), cancellable = true, require = 1)
    private void gravityengine$shootFromOneActorSnapshot(
            Entity shooter,
            float xRot,
            float yRot,
            float pitchOffset,
            float velocity,
            float inaccuracy,
            CallbackInfo ci
    ) {
        VanillaActorSnapshot actor = VanillaActorBridge.capture(
                shooter, yRot, xRot);
        if (!actor.transformedLook()
                && !actor.nonDefaultReferenceFrame()) {
            return;
        }
        Projectile projectile = (Projectile) (Object) this;
        Vec3 direction = VanillaProjectileBridge.launchDirection(
                actor, pitchOffset);
        projectile.shoot(
                direction.x, direction.y, direction.z, velocity, inaccuracy
        );
        Vec3 inherited = VanillaProjectileBridge.groundedInheritedMotion(
                actor,
                shooter.getKnownMovement(),
                shooter.onGround()
        );
        projectile.setDeltaMovement(
                projectile.getDeltaMovement().add(inherited));
        ci.cancel();
    }
}
