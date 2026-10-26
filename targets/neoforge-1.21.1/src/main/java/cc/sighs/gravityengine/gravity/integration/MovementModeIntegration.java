package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** The Level owner/predicting client observes native state before application/body handoff.
 * The application coordinator still owns legal geometry installation; a deferred handoff
 * must keep exact collision until that geometry has actually been replaced. */
public final class MovementModeIntegration {
    private MovementModeIntegration() {}

    public static MovementMode desired(Entity entity) {
        var state = GravityApplicationStateCapture.capture(entity);
        if (state.noPhysics() || state.spectator() || state.passenger() || state.sleeping()
                || state.autoSpinAttack() || state.deadOrDying() || state.controlledFlight()
                || state.upsideDownPresentation() || state.otherVanillaPose()) return MovementMode.NATIVE_FALLBACK;
        if (state.actualFluidLocomotion() || state.swimmingPresentation()) return MovementMode.SWIMMING;
        if (state.climbing()) return MovementMode.CLIMBING;
        return state.fallFlying() ? MovementMode.ELYTRA : MovementMode.GROUND_AIR;
    }

    public static void update(Entity entity) {
        if (!(entity instanceof LivingEntity)) return;
        /*
         * Vanilla gives movement mutation to the server simulation or to the
         * client that actually controls the entity. A remote client replica
         * consumes synchronized movement; it never transitions deltaMovement,
         * support or ground state here.
         */
        if (!entity.isControlledByLocalInstance()) return;
        var access = GravityEntityAccess.cast(entity);
        var runtime = access.gravityengine$gravityComponent().operationState();
        if (runtime.isInMove() || runtime.isApplyingGeometry()) return;
        var next = desired(entity);
        if (runtime.movementMode() == next) return;
        boolean hadSupport = runtime.persistentSupportState() != null || runtime.restingContactSnapshot() != null;
        var release = EngineSupportTransportIntegration.captureJumpReleaseVelocity(entity, runtime);
        var increment = runtime.transitionMovementMode(next, release);
        if (increment.lengthSquared() != 0) entity.setDeltaMovement(entity.getDeltaMovement().add(MinecraftMathAdapter.toMinecraft(increment)));
        if (hadSupport) {
            access.gravityengine$setVanillaSupportingBlock(null);
            entity.setOnGround(false);
        }
    }

    public static boolean allowsExternalCollision(Entity entity) {
        return !entity.isRemoved()
                && !(entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                    && ArmorStandIntegration.nativeNoPhysics(entity,entity.noPhysics))
                && (entity instanceof LivingEntity || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity)
                && desired(entity) != MovementMode.NATIVE_FALLBACK;
    }
}
