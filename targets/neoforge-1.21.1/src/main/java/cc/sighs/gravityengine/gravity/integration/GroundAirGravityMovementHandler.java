package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.BlockMovementMaterialSnapshot;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Spatial operands for Vanilla LivingEntity.travel's ground/air branch.
 * Vanilla owns input integration, effect selection, friction coefficients and
 * callback order. Only invocation-local return values use reference space;
 * Entity.deltaMovement always remains world-space physical velocity.
 */
public final class GroundAirGravityMovementHandler {
    private GroundAirGravityMovementHandler() {}

    /** No support is a real empty answer, never a world-Y block lookup. */
    public static float friction(LivingEntity entity, GravityRuntimeState runtime) {
        var operation = runtime.collisionOperation();
        if (operation == null) {
            throw new IllegalStateException("ground/air material requires the owning collision scene");
        }
        var support = GravityEntityAccess.cast(entity).gravityengine$getVanillaSupportingBlock();
        return support.flatMap(operation.scene()::movementMaterialAt)
                .orElse(BlockMovementMaterialSnapshot.AIR).friction();
    }

    /** Validate before Vanilla's helper can enter Entity.move. No physical mutation. */
    public static Vec3 inputContribution(GravityTravelContext context, Vec3 input, float friction) {
        var entity = context.entity();
        float speed = GravityLivingAccess.cast(entity).gravityengine$getFrictionInfluencedSpeed(friction);
        return cc.sighs.gravityengine.gravity.movement.GravityPhysics.calculateRelativeMovement(
                context.look().forward(), speed, input, context.frame(), context.look().zeroPitchForward());
    }

    /**
     * Called only at Vanilla's selected gravity subtraction. Vanilla has
     * already selected slow-falling's magnitude cap or zero no-gravity.
     * Keep the actual vector even when the installed reference is deadbanded.
     *
     * <p>Gravity is a newly generated operation-local increment. It must not be
     * allowed to enter persistent actor velocity merely because endpoint
     * feet-support identity disappeared after a movement that already proved a
     * supporting gravity-down collision.</p>
     */
    public static Vec3 accelerate(
            GravityTravelContext context,
            Vec3 localVelocity,
            double magnitude
    ) {
        Vec3 acceleration =
                context.sample().accelerationVector();

        if (magnitude == 0.0D) {
            acceleration = Vec3.ZERO;
        } else if (magnitude < acceleration.length()) {
            acceleration =
                    acceleration.normalize().scale(magnitude);
        }

        /*
         * Acceleration ownership and collision ownership are independent.
         * Native AABB travel still needs the post-move downward increment to
         * refresh verticalCollisionBelow on its NEXT Entity.move invocation.
         */
        if (!ownsExactContactResponse(context)) {
            return localVelocity.add(context.frame().worldToLocal(acceleration));
        }

        var move =
                context.runtime().currentMoveResult();

        if (move != null && !move.indeterminate()) {
            Vec3 actorVelocity =
                    context.frame().localToWorld(
                            localVelocity
                    );

            if (move.tractionEligible()) {
                /*
                 * Strong case: the solved endpoint still has terminal support.
                 * Its real face owns the exact support normal and surface
                 * velocity, so retain the existing source-specific policy.
                 */
                acceleration =
                        cc.sighs.gravityengine.gravity.collision
                                .GroundTractionPolicy
                                .supportedGravity(
                                        acceleration,
                                        actorVelocity,
                                        move.supportContact()
                                                .orElseThrow()
                                );

            } else if (move.physicalSupport()) {
                acceleration = cc.sighs.gravityengine.gravity.collision.GroundTractionPolicy.contactGravity(
                        acceleration, actorVelocity, move.supportContact().orElseThrow());
            } else if (move.supportingContactDuringMove()) {
                /*
                 * Important handoff case:
                 *
                 *   vertical/gravity-down leg hit a real supporting contact
                 *       -> supportingContactDuringMove = true
                 *
                 *   later tangent/end-point support query has no terminal witness
                 *       -> supportContact = empty
                 *
                 * gameplayGrounded is intentionally still true for this movement
                 * commit. The collision result owns this contact response;
                 * endpoint witness absence does not undo a blocked gravity leg.
                 *
                 * Do not invent terminal support here. Consume only the
                 * already-proven movement-support fact to suppress THIS newly
                 * generated inward gravity increment.
                 */
                acceleration =
                        cc.sighs.gravityengine.gravity.collision
                                .GroundTractionPolicy
                                .supportedGravityAfterMovementContact(
                                        acceleration,
                                        actorVelocity,
                                        context.frame()
                                );
            }
        } else if (move == null
                && context.runtime().movementCollisionRoute() == null) {
            /*
             * This fallback is ONLY for a genuine pre-move exact-body query.
             * A completed native move publishes its VANILLA route but no
             * GravityMoveResult. A completed exact move with a missing result
             * must not resurrect stale start support either.
             */
            var stepStartSupport =
                    context.operation()
                            .controlSupportAtStepStart();
            if (stepStartSupport.isPresent()) {
                acceleration = stepStartSupportGravity(
                        acceleration,
                        context.frame().localToWorld(localVelocity),
                        stepStartSupport.get()
                );
            }
        }

        return localVelocity.add(
                context.frame().worldToLocal(
                        acceleration
                )
        );
    }

    /** Prefer the route published by this move over any derived live policy. */
    private static boolean ownsExactContactResponse(GravityTravelContext context) {
        var executedRoute = context.runtime().movementCollisionRoute();
        return executedRoute != null
                ? executedRoute == GravityInfluencePolicy.CollisionRoute.EXACT_BODY
                : GravityInfluencePolicy.usesExactBodyCollision(context.entity());
    }

    static Vec3 stepStartSupportGravity(
            Vec3 acceleration,
            Vec3 actorVelocity,
            RestingContactSnapshot support
    ) {
        return cc.sighs.gravityengine.gravity.collision
                .GroundTractionPolicy
                .supportedGravity(
                        acceleration,
                        actorVelocity,
                        supportContact(support)
                );
    }

    private static GravitySupportContact supportContact(
            RestingContactSnapshot support
    ) {
        return new GravitySupportContact(
                new Vector3d(
                        support.normal().x,
                        support.normal().y,
                        support.normal().z
                ),
                new Vector3d(
                        support.surfaceVelocity().x,
                        support.surfaceVelocity().y,
                        support.surfaceVelocity().z
                ),
                new Vector3d(
                        support.contactPoint().x,
                        support.contactPoint().y,
                        support.contactPoint().z
                ),
                support.geometryKind(),
                support.faceIdentity()
        );
    }

    /**
     * Vanilla friction owns the gravity-local components. Do not project a
     * FLOOR contact into a world-space slope tangent before that anisotropic
     * damping runs: doing so manufactures a gravity-up component which the
     * next movement step can misinterpret as active separation.
     *
     * <p>The final velocity commit still performs the unilateral hard-contact
     * response after friction.</p>
     */
    public static Vec3 beforeFriction(
            GravityTravelContext context,
            Vec3 localVelocity
    ) {
        return localVelocity;
    }

    public static Vec3 worldVelocity(GravityTravelContext context, Vec3 localVelocity) {
        return constrain(context, context.frame().localToWorld(localVelocity), "travel-final");
    }

    /** Native climb/powder-snow policy supplies the lift; the operand is reference vertical. */
    public static Vec3 verticalVelocity(LivingEntity entity, double lift) {
        var frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity);
        Vec3 local = frame.worldToLocal(entity.getDeltaMovement());
        return frame.localToWorld(new Vec3(local.x, lift, local.z));
    }

    private static Vec3 constrain(GravityTravelContext context, Vec3 velocity, String stage) {
        if (!ownsExactContactResponse(context)) return velocity;
        var result = context.runtime().currentMoveResult();
        if (!ContactVelocityIntegration.tangentVelocityResponseAllowed(result)) return velocity;
        var resolution = ContactVelocityIntegration.resolveContactVelocityResult(context.entity(), result, velocity);
        ContactVelocityIntegration.logVelocityFallback(
                context.entity(), result, resolution, stage);
        return resolution.velocity();
    }
}
