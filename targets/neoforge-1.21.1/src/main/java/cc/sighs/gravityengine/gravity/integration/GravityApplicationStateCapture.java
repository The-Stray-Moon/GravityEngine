package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.attitude.BodyAttitudePlayerState;
import cc.sighs.gravityengine.attitude.runtime.MinecraftBodyAttitudeSnapshotAdapter;

import cc.sighs.gravityengine.gravity.policy.GravityApplicationPlanner;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * Integration-side capture translating one live Minecraft {@code Entity}
 * into the immutable {@link GravityApplicationPlanner.EntityState} input.
 *
 * <p>Players are captured through the canonical
 * {@link MinecraftBodyAttitudeSnapshotAdapter#snapshot(Player)} so the application
 * planner and the BodyAttitude ownership policy can never independently
 * derive death, controlled flight, upside-down presentation, unusual poses,
 * fluid/swimming state, etc.  Non-player semantics mirror the previous
 * production decision tree exactly.</p>
 */
public final class GravityApplicationStateCapture {
    private GravityApplicationStateCapture() {}

    public static GravityApplicationPlanner.EntityState capture(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player player) {
            return fromPlayerState(
                    MinecraftBodyAttitudeSnapshotAdapter.snapshot(player));
        }
        if (!(entity instanceof LivingEntity living)) {
            return new GravityApplicationPlanner.EntityState(
                    entity.noPhysics, false, entity.isPassenger(),
                    false, false, false, false, false, false,
                    false, false, false, false);
        }
        return new GravityApplicationPlanner.EntityState(
                entity.noPhysics,
                false,
                living.isPassenger(),
                living.isSleeping(),
                living.isFallFlying(),
                living.isInWater() || living.isInLava()
                        || living.isInFluidType(),
                living.isSwimming(),
                living.isAutoSpinAttack(),
                living.onClimbable(),
                false,
                false,
                false,
                false);
    }

    /**
     * Package-visible translation of the one immutable BodyAttitude player
     * snapshot into planner input; kept as a focused seam so the flag mapping
     * can be tested without a live Minecraft player.
     */
    static GravityApplicationPlanner.EntityState fromPlayerState(
            BodyAttitudePlayerState attitude
    ) {
        Objects.requireNonNull(attitude, "attitude");
        return new GravityApplicationPlanner.EntityState(
                attitude.noPhysics(),
                attitude.spectator(),
                attitude.passenger(),
                attitude.sleeping(),
                attitude.fallFlying(),
                attitude.actualFluidLocomotion(),
                attitude.swimmingPresentation(),
                attitude.autoSpinAttack(),
                attitude.climbing(),
                attitude.deadOrDying(),
                attitude.controlledFlight(),
                attitude.upsideDownPresentation(),
                attitude.otherVanillaPose());
    }
}