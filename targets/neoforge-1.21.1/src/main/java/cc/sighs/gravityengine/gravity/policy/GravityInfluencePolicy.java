package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import cc.sighs.gravityengine.gravity.integration.GravityOperation;

import java.util.Objects;

public final class GravityInfluencePolicy {
    public enum CollisionRoute {
        VANILLA,
        PASSIVE_AABB,
        EXACT_BODY
    }

    private GravityInfluencePolicy() {}

    public static GravitySuppressionReason authoritativeSuppression(Entity e) { return component(e).authoritativeSuppression(); }
    public static GravityApplicationPlan committedPlan(Entity e) { return component(e).appliedPlan(); }

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
        var frame = component(e).runtime().geometryReferenceFrame();
        return frame != null && requiresReferenceGeometry(frame);
    }

    public static boolean requiresReferenceGeometry(cc.sighs.gravityengine.gravity.GravityFrame frame) {
        return frame.down().distanceToSqr(GravityState.DEFAULT_DOWN) > PASSIVE_DEFAULT_DOWN_EPSILON_SQUARED;
    }
    /** Installed non-default character geometry requires exact collision until a safe
     * reference transition replaces it. Actor attitude never selects this route.
     * Default-equivalent character frames delegate to Vanilla. */
    public static CollisionRoute collisionRoute(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        GravityApplicationPlan plan = committedPlan(entity);

        if (usesCustomBody(entity) || plan.kind() == GravityApplicationPlan.Kind.CHARACTER
                && requiresReferenceGeometry(cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity))) {
            return CollisionRoute.EXACT_BODY;
        }

        if (plan.kind() != GravityApplicationPlan.Kind.PASSIVE) {
            return CollisionRoute.VANILLA;
        }

        var runtime = component(entity).runtime();

        /*
         * A passive collision solve requires the frozen frame owned by the active
         * GravityOperation. Outside that operation there is no authoritative
         * passive collision snapshot and vanilla remains authoritative.
         */
        if (!runtime.isInMove()) {
            return CollisionRoute.VANILLA;
        }

        return runtime.activeFrame()
                .down()
                .distanceToSqr(GravityState.DEFAULT_DOWN)
                > PASSIVE_DEFAULT_DOWN_EPSILON_SQUARED
                ? CollisionRoute.PASSIVE_AABB
                : CollisionRoute.VANILLA;
    }

    public static boolean usesExactBodyCollision(Entity entity) {
        return collisionRoute(entity) == CollisionRoute.EXACT_BODY;
    }

    public static boolean usesPassiveCollision(Entity entity) {
        return collisionRoute(entity) == CollisionRoute.PASSIVE_AABB;
    }

    public static boolean usesCustomCollision(Entity entity) {
        return collisionRoute(entity) != CollisionRoute.VANILLA;
    }
    /** Scalar-look authority follows the accepted reference, not pending assignment. */
    public static boolean usesGravityLocalLook(Entity entity) {
        var c = component(entity);
        return c.effectiveSuppression() == GravitySuppressionReason.NONE
                && c.appliedPlan().usesGravityLocalLook()
                && requiresReferenceGeometry(
                        cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                                .authoritativeFrame(entity));
    }

    /** Presentation has its own capability gate; collision route is not that gate. */
    public static boolean usesCustomPresentation(Entity entity) {
        var c = component(entity);
        return c.effectiveSuppression() == GravitySuppressionReason.NONE
                && c.appliedPlan().usesCustomPresentation()
                && requiresReferenceGeometry(
                        cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess
                                .authoritativeFrame(entity));
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
