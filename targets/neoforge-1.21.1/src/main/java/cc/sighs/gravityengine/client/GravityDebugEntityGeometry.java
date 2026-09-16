package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * One read-only geometry snapshot for an entity in the gravity diagnostic
 * scope. Scope membership is independent from optional custom-body layers.
 */
public record GravityDebugEntityGeometry(
        AABB entityBounds,
        @Nullable CollisionBody physicalBody,
        @Nullable CollisionBody presentationBody,
        GravityApplicationPlan applicationPlan
) {
    public GravityDebugEntityGeometry {
        Objects.requireNonNull(entityBounds, "entityBounds");
        Objects.requireNonNull(applicationPlan, "applicationPlan");
    }

    public static Optional<GravityDebugEntityGeometry> capture(
            Entity entity,
            float partialTick
    ) {
        Objects.requireNonNull(entity, "entity");

        // assignedState != debug scope. The committed application alone says
        // which behavior GravityEngine currently owns.
        if (!GravityInfluencePolicy.hasAppliedGravityInfluence(entity)) {
            return Optional.empty();
        }

        return capture(
                entity.getBoundingBox(),
                GravityPresentationIntegration.physicalCollisionBody(entity),
                GravityPresentationIntegration.debugCollisionBody(
                        entity,
                        partialTick
                ),
                GravityInfluencePolicy.committedPlan(entity)
        );
    }

    static Optional<GravityDebugEntityGeometry> capture(
            AABB entityBounds,
            @Nullable CollisionBody physicalBody,
            @Nullable CollisionBody presentationBody,
            GravityApplicationPlan applicationPlan
    ) {
        Objects.requireNonNull(entityBounds, "entityBounds");
        Objects.requireNonNull(applicationPlan, "applicationPlan");
        if (!applicationPlan.hasGravityOwnership()) {
            return Optional.empty();
        }
        return Optional.of(new GravityDebugEntityGeometry(
                entityBounds,
                physicalBody,
                presentationBody,
                applicationPlan
        ));
    }
}
