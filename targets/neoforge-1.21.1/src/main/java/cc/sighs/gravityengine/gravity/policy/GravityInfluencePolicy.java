package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.policy.GravityReferencePolicy;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Objects;

public final class GravityInfluencePolicy {
    private GravityInfluencePolicy() {}

    public static GravitySuppressionReason authoritativeSuppression(Entity e) { return component(e).state().authoritativeSuppression(); }
    public static GravityApplicationPlan committedPlan(Entity e) { return component(e).state().appliedPlan(); }

    private static final double PASSIVE_DEFAULT_DOWN_EPSILON_SQUARED = 1.0E-12D;

    /**
     * True when the currently committed application gives GravityEngine ownership
     * of at least one gravity behavior axis for this entity.
     *
     * <p>This is the authoritative scope predicate for client gravity
     * diagnostics. Assigned state is not debug scope: an assigned but
     * suppressed entity whose committed plan is vanilla is outside the scope.</p>
     */
    public static boolean hasAppliedGravityInfluence(Entity entity) {
        return committedPlan(entity).hasGravityOwnership();
    }

    /** True when the committed application owns gravity-relative locomotion operands. */
    public static boolean usesCustomLocomotion(Entity entity) {
        return committedPlan(entity).usesCustomMoveSolver();
    }
    public static boolean usesCustomBody(Entity e) {
        return BodyRepresentation.ofAxis(component(e).operationState().installedCollisionUp()).isExact();
    }

    /** Installed non-default character geometry requires exact collision until a safe
     * reference transition replaces it. Actor attitude never selects this route.
     * Default-equivalent character frames delegate to Vanilla. */
    public static GravityCollisionRoute collisionRoute(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (cc.sighs.gravityengine.gravity.integration.FallingBlockTickIntegration.isUnavailable(entity))
            return GravityCollisionRoute.VANILLA;
        GravityApplicationPlan plan = committedPlan(entity);

        // Reference rotation and collision ownership are independent. Discovery
        // consumes the already captured scene, never invokes providers again.
        var operation = component(entity).operationState().collisionOperation();
        if (cc.sighs.gravityengine.gravity.integration.MovementModeIntegration.allowsExternalCollision(entity) && operation != null) {
            var bounds = operation.scene() instanceof cc.sighs.gravityengine.gravity.collision.CapturedCollisionScene captured
                    ? captured.domain().staticBounds()
                    : cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter
                            .toAabb3d(entity.getBoundingBox());
            if (operation.scene().dynamicObstacles().stream().anyMatch(obstacle ->
                    !obstacle.providerNamespace().equals(cc.sighs.gravityengine.gravity.collision
                            .RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE)
                            && obstacle.operationSweptBounds().intersects(bounds))) {
                return GravityCollisionRoute.EXACT_BODY;
            }
        }

        if (usesCustomBody(entity) || plan.kind() == GravityApplicationPlan.Kind.CHARACTER
                && BodyRepresentation.requiresReferenceGeometry(cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity))) {
            return GravityCollisionRoute.EXACT_BODY;
        }

        if (plan.kind() != GravityApplicationPlan.Kind.PASSIVE) {
            return GravityCollisionRoute.VANILLA;
        }

        var runtime = component(entity).operationState();

        /*
         * A passive collision solve requires the frozen frame owned by the active
         * GravityOperation. Outside that operation there is no authoritative
         * passive collision snapshot and vanilla remains authoritative.
         */
        if (!runtime.isInMove()) {
            return GravityCollisionRoute.VANILLA;
        }

        return runtime.activeFrame()
                .down()
                .distanceSquared(GravityState.DEFAULT_DOWN)
                > PASSIVE_DEFAULT_DOWN_EPSILON_SQUARED
                ? GravityCollisionRoute.PASSIVE_AABB
                : GravityCollisionRoute.VANILLA;
    }

    public static boolean usesExactBodyCollision(Entity entity) {
        return collisionRoute(entity) == GravityCollisionRoute.EXACT_BODY;
    }

    /** Discovery eligibility only; registration alone never selects a solver. */
    public static boolean hasExternalCollisionProviders(Entity entity) {
        return cc.sighs.gravityengine.gravity.integration.MovementModeIntegration.allowsExternalCollision(entity)
                && !cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry
                        .providers(entity.level()).isEmpty();
    }

    public static boolean usesPassiveCollision(Entity entity) {
        return collisionRoute(entity) == GravityCollisionRoute.PASSIVE_AABB;
    }

    public static boolean usesCustomCollision(Entity entity) {
        return collisionRoute(entity) != GravityCollisionRoute.VANILLA;
    }
    /**
     * True when the entity currently has an environmental gravity reference
     * that presentation may consume. This is independent from the committed
     * application kind, locomotion mode, swimming/climbing pose and installed
     * collision representation.
     */
    public static boolean hasGravityReference(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!hasPresentationCapability(entity)) {
            return false;
        }
        var component = component(entity);
        return GravityReferencePolicy.hasGravityReference(
                component.state().assignedAuthority(),
                /*
                 * Deliberate UNKNOWN policy for an unresolved reconciliation:
                 * a previously confirmed reference stays provisionally in
                 * force (the committed application is not replaced while
                 * evidence is unknown), while a restored seed that never
                 * confirmed presence claims no reference. Callers that need a
                 * proven answer use GravityEntityState.fieldPresence().
                 */
                component.state().fieldReferenceInForce(),
                component.state().authoritativeSuppression(),
                true,
                cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                        .authoritativeFrame(entity)
        );
    }

    private static boolean hasPresentationCapability(Entity entity) {
        if (!(entity instanceof LivingEntity living)
                || GravityEntityCapabilitiesPolicy.capabilities(entity)
                != cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities.CHARACTER
                || entity.isRemoved()
                || entity.isSpectator()
                || entity.noPhysics
                || entity.isPassenger()
                || living.isSleeping()) {
            return false;
        }
        return true;
    }

    /** Scalar-look authority follows the accepted environmental reference. */
    public static boolean usesGravityLocalLook(Entity entity) {
        return hasGravityReference(entity);
    }

    /** Presentation has its own capability gate; collision route is not that gate. */
    public static boolean usesCustomPresentation(Entity entity) {
        return hasGravityReference(entity);
    }

    public static GravitySuppressionReason deriveAuthoritativeSuppression(Entity e) {
        if (e instanceof ServerPlayer p && p.isCreative() && p.getAbilities().flying) return GravitySuppressionReason.CREATIVE_FLIGHT;
        return GravitySuppressionReason.NONE;
    }

    public static boolean shouldSuppressCreativeFlight(boolean creative, boolean flying) { return creative && flying; }
    public static GravitySuppressionReason deriveSuppression(boolean creative, boolean flying) {
        return shouldSuppressCreativeFlight(creative, flying) ? GravitySuppressionReason.CREATIVE_FLIGHT : GravitySuppressionReason.NONE;
    }

    private static EntityGravityComponent component(Entity e) { return GravityEntityAccess.cast(e).gravityengine$gravityComponent(); }
}
