package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityAccelerationResolver;
import cc.sighs.gravityengine.gravity.ballistic.BallisticGravityIntegrator;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Minecraft adapter for one vanilla ballistic gravity call. */
public final class BallisticGravityIntegration {
    private BallisticGravityIntegration() {}

    /**
     * Applies field acceleration exactly once when an active field exists.
     * Returns false when the caller must invoke the original vanilla gravity.
     */
    public static boolean applyGravity(Entity entity, double intervalTicks) {
        Objects.requireNonNull(entity, "entity");
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(entity);
        var plan = GravityInfluencePolicy.committedPlan(entity);
        var kind = plan.kind();
        if ((kind != GravityApplicationPlan.Kind.BALLISTIC
                && kind != GravityApplicationPlan.Kind.PASSIVE)
                || plan.accelerationMode() == GravityAccelerationMode.NONE
                || entity.isNoGravity()) {
            return false;
        }

        Vec3 samplePoint = entity.getBoundingBox().getCenter();
        Vec3 velocity = entity.getDeltaMovement();
        var query = new AccelerationQuery(
                entity,
                samplePoint,
                velocity,
                entity.level().getGameTime(),
                intervalTicks
        );
        // The exact vanilla acceleration magnitude is owned by the wrapped
        // call. This value is used only for the pure no-field resolution; the
        // adapter deliberately calls vanilla instead of reconstructing it.
        var resolution = GravityAccelerationResolver.resolve(query, plan, Vec3.ZERO);
        if (!resolution.replacesVanilla()) {
            return false;
        }
        entity.setDeltaMovement(BallisticGravityIntegrator.integrate(
                velocity, resolution.acceleration(), intervalTicks
        ));
        return true;
    }

}