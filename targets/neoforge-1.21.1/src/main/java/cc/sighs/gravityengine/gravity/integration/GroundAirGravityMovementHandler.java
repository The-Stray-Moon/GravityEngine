package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.BlockMovementMaterialSnapshot;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityLivingAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Spatial operands for Vanilla LivingEntity.travel's ground/air branch.
 * Vanilla owns input integration, effect selection, friction coefficients and
 * callback order. Only invocation-local return values use reference space;
 * Entity.deltaMovement always remains world-space physical velocity.
 */
public final class GroundAirGravityMovementHandler {
    private GroundAirGravityMovementHandler() {}

    /** No support is a real empty answer, never a world-Y block lookup. */
    public static float friction(LivingEntity entity, GravityOperationState runtime) {
        var operation = runtime.collisionOperation();
        if (operation == null) {
            throw new IllegalStateException("ground/air material requires the owning collision scene");
        }
        return BlockContactResolver.supportMaterial(entity).friction();
    }

    /** Validate before Vanilla's helper can enter Entity.move. No physical mutation. */
    public static Vec3 inputContribution(GravityTravelContext context, Vec3 input, float friction) {
        var entity = context.entity();
        float speed;
        if (ownsExactContactResponse(context)) {
            speed = groundedAtStart(context)
                    ? entity.getSpeed() * (0.21600002F / (friction * friction * friction))
                    : GravityLivingAccess.cast(entity).gravityengine$getFlyingSpeed();
        } else {
            speed = GravityLivingAccess.cast(entity).gravityengine$getFrictionInfluencedSpeed(friction);
        }
        return MinecraftMathAdapter.toMinecraft(
                cc.sighs.gravityengine.gravity.movement.GravityPhysics
                        .calculateRelativeMovement(
                                context.look().forward(),
                                speed,
                                MinecraftMathAdapter.toVec3d(input),
                                context.controlFrame(),
                                context.look().zeroPitchForward()));
    }

    /** The travel owner revalidated this body before input or native drag selection. */
    public static boolean groundedAtStart(GravityTravelContext context) {
        return context.operation().controlSupportAtStepStart().isPresent();
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
        Vec3d acceleration =
                GravityInfluencePolicy.committedPlan(context.entity()).usesCustomMoveSolver()
                        ? context.sample().accelerationVector()
                        : context.frame().down().multiply(magnitude);

        if (magnitude == 0.0D) {
            acceleration = Vec3d.ZERO;
        } else if (magnitude < acceleration.length()) {
            acceleration =
                    acceleration.normalized().multiply(magnitude);
        }

        /*
         * Acceleration ownership and collision ownership are independent.
         * Native AABB travel still needs the post-move downward increment to
         * refresh verticalCollisionBelow on its NEXT Entity.move invocation.
         */
        if (!ownsExactContactResponse(context)) {
            return MinecraftMathAdapter.toMinecraft(
                    MinecraftMathAdapter.toVec3d(localVelocity)
                            .add(context.frame().worldToLocal(acceleration)));
        }

        var move =
                context.operationState().currentMoveResult();

        if (move != null && !move.indeterminate()) {
            Vec3d actorVelocity =
                    context.frame().localToWorld(
                            MinecraftMathAdapter.toVec3d(localVelocity)
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
                && context.operationState().movementCollisionRoute() == null) {
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
                        context.frame().localToWorld(
                                MinecraftMathAdapter.toVec3d(
                                        localVelocity)),
                        stepStartSupport.get()
                );
            }
        }

        return MinecraftMathAdapter.toMinecraft(
                MinecraftMathAdapter.toVec3d(localVelocity).add(
                        context.frame().worldToLocal(acceleration)
                )
        );
    }

    /** Prefer the route published by this move over any derived live policy. */
    private static boolean ownsExactContactResponse(GravityTravelContext context) {
        var executedRoute = context.operationState().movementCollisionRoute();
        return executedRoute != null
                ? executedRoute == GravityCollisionRoute.EXACT_BODY
                : GravityInfluencePolicy.usesExactBodyCollision(context.entity());
    }

    static Vec3d stepStartSupportGravity(
            Vec3d acceleration,
            Vec3d actorVelocity,
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
                support.normal(),
                support.surfaceVelocity(),
                support.contactPoint(),
                support.geometryKind(),
                support.faceIdentity()
        );
    }

    /** Native scalar drag runs in the contacted face's basis. Damping a slope
     * tangent in the gravity basis manufactures outward normal velocity. The
     * carrier is relative to credited support momentum; no transport enters it. */
    public static Vec3 beforeFriction(GravityTravelContext context, Vec3 localVelocity) {
        Vec3d relative = context.frame().localToWorld(MinecraftMathAdapter.toVec3d(localVelocity))
                .subtract(context.operationState().supportVelocityContribution());
        var rotation = frictionRotation(context);
        return MinecraftMathAdapter.toMinecraft(rotation == null
                ? context.frame().worldToLocal(relative) : rotation.conjugate().transform(relative));
    }

    public static Vec3 worldVelocity(GravityTravelContext context, Vec3 localVelocity) {
        var rotation = frictionRotation(context);
        Vec3d local = MinecraftMathAdapter.toVec3d(localVelocity);
        Vec3d relative = rotation == null ? context.frame().localToWorld(local) : rotation.transform(local);
        return constrain(context, MinecraftMathAdapter.toMinecraft(relative.add(
                context.operationState().supportVelocityContribution())), "travel-final");
    }

    private static cc.sighs.gravityengine.math.Quatd frictionRotation(GravityTravelContext context) {
        if (!ownsExactContactResponse(context)) return null;
        var result = context.operationState().currentMoveResult();
        if (result == null || !result.isAuthoritative()) return null;
        return result.supportContact().or(result::movementSupportContact)
                .map(contact -> cc.sighs.gravityengine.math.Quatd.rotationTo(Vec3d.Y, contact.normal()))
                .orElse(null);
    }

    /** Native climb/powder-snow policy supplies the lift; the operand is reference vertical. */
    public static Vec3 verticalVelocity(LivingEntity entity, double lift) {
        var frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity);
        Vec3d local = frame.worldToLocal(
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement()));
        return MinecraftMathAdapter.toMinecraft(
                frame.localToWorld(
                        new Vec3d(local.x(), lift, local.z())));
    }

    private static Vec3 constrain(GravityTravelContext context, Vec3 velocity, String stage) {
        if (!ownsExactContactResponse(context)) return velocity;
        var result = context.operationState().currentMoveResult();
        if (!ContactVelocityIntegration.tangentVelocityResponseAllowed(result)) return velocity;
        var resolution = ContactVelocityIntegration.resolveContactVelocityResult(context.entity(), result, velocity);
        ContactVelocityIntegration.retainSupportContribution(context.operationState(), resolution);
        ContactVelocityIntegration.logVelocityFallback(
                context.entity(), result, resolution, stage);
        return MinecraftMathAdapter.toMinecraft(resolution.velocity());
    }
}
