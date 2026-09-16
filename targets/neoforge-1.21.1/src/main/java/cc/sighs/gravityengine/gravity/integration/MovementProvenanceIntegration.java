package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Minecraft movement-provenance adapter.
 *
 * <p>Minecraft-specific request identity terminates here. Downstream
 * provenance/collision code sees only the immutable semantic Channel.</p>
 *
 * <p>This owner also records already-accepted external impulses (knockback,
 * explosions, pushes) that replace or suppress a Vanilla velocity write.
 * Provenance is recorded only after the Vanilla/NeoForge producer finished
 * event cancellation, resistance and strength modification, and only for
 * entities whose movement evidence is GravityEngine-owned.  It never decides
 * combat direction, attack strength, event policy, resistance or any
 * gravity/reference transform; those stay at their producing owners.</p>
 */
public final class MovementProvenanceIntegration {
    private MovementProvenanceIntegration() {}

    public static MovementEvidence capture(
            Entity entity,
            MoverType type,
            Vec3 wrapper
    ) {
        Objects.requireNonNull(entity, "entity");

        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        OwnedMotion evidence =
                runtime.consumeMovementEvidence(
                        entity.level().getGameTime()
                );

        return capture(type, wrapper, evidence);
    }

    public static MovementEvidence capture(
            MoverType type,
            Vec3 wrapper,
            OwnedMotion evidence
    ) {
        Objects.requireNonNull(type, "type");

        return MovementEvidence.capture(
                channelFor(type),
                wrapper,
                evidence
        );
    }

    /**
     * Body-heading displacement for the locomotion portion of this tick.
     *
     * <p>Custom-body movement uses only the locomotion portion published by
     * the authoritative custom solve. Missing same-tick evidence fails closed
     * to zero, so recovery/support transport can never be reconstructed from
     * the entity's total position delta. Vanilla bodies retain Vanilla's
     * delta.</p>
     */
    public static Vec3 bodyHeadingDisplacement(LivingEntity entity) {
        Objects.requireNonNull(entity, "entity");

        Vec3 vanillaDisplacement = new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo);

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return vanillaDisplacement;
        }

        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .runtime()
                .lastCommittedLocomotionDisplacement(
                        entity.level().getGameTime())
                .orElse(Vec3.ZERO);
    }

    /**
     * Records an already-resolved/accepted world-space external velocity delta
     * into GravityEngine runtime provenance.
     *
     * <p>Both ordinary Vanilla knockback and translated combat knockback call
     * this only after Vanilla/NeoForge policy has resolved cancellation,
     * resistance and strength modification. Provenance records the actually
     * accepted velocity delta, never the requested carrier.</p>
     *
     * <p>An exactly zero accepted delta is not an external-motion fact and must
     * not advance the runtime external-evidence tick. No epsilon is used here:
     * arbitrarily small but real accepted impulses remain observable.</p>
     */
    public static void recordAcceptedExternalPush(
            Entity entity,
            Vec3 before,
            Vec3 after
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        if (!GravityInfluencePolicy
                .usesCustomLocomotion(entity)) {
            return;
        }

        Vec3 acceptedExternalDelta =
                after.subtract(before);

        /*
         * Exact-zero only.
         *
         * Do NOT introduce an epsilon here: a very small accepted impulse is still
         * real provenance. Component comparisons also treat -0.0 as zero.
         */
        if (acceptedExternalDelta.x == 0.0D
                && acceptedExternalDelta.y == 0.0D
                && acceptedExternalDelta.z == 0.0D) {
            return;
        }

        GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime()
                .recordExternalPush(
                        entity.level().getGameTime(),
                        acceptedExternalDelta
                );
    }

    private static KinematicMoveRequest.Channel channelFor(
            MoverType type
    ) {
        // Minecraft locator: MoverType -> provenance channel. PLAYER is
        // Vanilla's packet translation proposal; it is not a GravityEngine
        // acceptance or validation decision.
        return switch (type) {
            case SELF ->
                    KinematicMoveRequest.Channel.SELF;

            case PLAYER ->
                    KinematicMoveRequest.Channel
                            .PACKET_RECONCILIATION;

            case PISTON ->
                    KinematicMoveRequest.Channel.PISTON;

            case SHULKER ->
                    KinematicMoveRequest.Channel.SHULKER;

            case SHULKER_BOX ->
                    KinematicMoveRequest.Channel.SHULKER_BOX;
        };
    }
}
