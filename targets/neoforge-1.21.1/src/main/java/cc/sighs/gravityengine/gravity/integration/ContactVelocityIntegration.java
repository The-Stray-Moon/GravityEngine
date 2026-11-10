package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * Contact-velocity response integration for the {@code Entity.move} boundary.
 *
 * <p>Revalidates the desired velocity against the operation's frozen collision
 * scene and projects it onto the solved contact constraints. It never
 * re-solves, clips or rejects the accepted translation.</p>
 */
public final class ContactVelocityIntegration {
    private ContactVelocityIntegration() {}

    /**
     * Phase 3 indeterminate policy for the tangent velocity boundary: a
     * missing or indeterminate result is not gameplay-authoritative, so the
     * DDD responder must neither run vanilla stale world-X/Z clipping nor
     * project against untrusted constraints.
     */
    public static boolean tangentVelocityResponseAllowed(
            GravityMoveResult result
    ) {
        return result != null && !result.indeterminate();
    }

    /** Detached plane projection for immutable constraints and focused tests. A different
     * desired direction requires finite revalidation via the Entity overload
     * at production boundaries; this overload never queries live state. */
    public static ContactVelocityResolution resolveContactVelocityResult(
            GravityMoveResult result,
            Vec3 velocity
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(velocity, "velocity");

        if (result.indeterminate()) {
            throw new IllegalStateException(
                    "indeterminate collision result may not drive velocity response"
            );
        }

        return projectConstraints(
                MinecraftMathAdapter.toVec3d(velocity),
                result.contactVelocityConstraints()
        );
    }

    /** Production velocity boundary: callbacks, gravity and friction can each
     * change the desired velocity after translation. Revalidate finite entry
     * against the same operation scene at the installed endpoint; never reuse
     * a direction-specific plane set for a different velocity or capture a new
     * world/frame. This calculation has no position or packet authority. */
    public static ContactVelocityResolution resolveContactVelocityResult(
            Entity entity, GravityMoveResult result, Vec3 velocity) {
        if (result == null || !result.isAuthoritative()) {
            throw new IllegalStateException("velocity requires a determinate move");
        }
        var operation = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState().collisionOperation();
        if (operation == null) throw new IllegalStateException("contact velocity requires the owning collision operation");
        var body = GravityEntityGeometry.body(entity);
        var desired = MinecraftMathAdapter.toVec3d(velocity);
        try {
            var contacts = CurrentContactQuery.velocityContacts(
                    body, desired, operation.scene(), operation.time().intervalTicks(), operation.geometryContext());
            var constraints = CurrentContactConstraintBuilder.characterConstraints(
                    body, contacts, desired, result.frame(), 1.0D, operation.geometryContext());
            return projectConstraints(desired, constraints);
        } catch (CollisionComplexityLimitException exhausted) {
            // No trustworthy finite activation set exists. Stop velocity at
            // this boundary without rejecting/re-solving the accepted move.
            return ContactVelocityResolution.unresolved(desired);
        }
    }

    /**
     * Diagnostic-only report for a velocity response that used the
     * non-penetrating fallback. It never changes position, collision state,
     * support state or packet acceptance.
     */
    public static void logVelocityFallback(
            Entity entity,
            GravityMoveResult result,
            ContactVelocityResolution resolution,
            String phase
    ) {
        if (!GravityDebugLog.shouldLog(entity)) return;
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(phase, "phase");
        if (!resolution.fallbackApplied()) {
            return;
        }
        GravityDebugLog.log(entity,
                "velocity-response-fallback",
                "CONTACT_VELOCITY_FALLBACK; phase=%s desired=%s committed=%s "
                        + "constraints=%s grounded=%s blockedDown=%s "
                        + "blockedUp=%s blockedTangent=%s",
                phase,
                GravityDebugLog.vec(resolution.desiredVelocity()),
                GravityDebugLog.vec(resolution.velocity()),
                resolution.constraints(),
                result.gameplayGrounded(),
                result.blockedDown(),
                result.blockedUp(),
                result.blockedTangent()
        );
    }

    /**
     * Solves one immutable contact-velocity constraint set once through
     * {@link ContactConstraintProjector} and reports the exact outcome
     * together with a usable committed velocity.
     */
    private static ContactVelocityResolution projectConstraints(
            Vec3d velocity,
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        if (constraints.isEmpty()) {
            return ContactVelocityResolution.feasible(velocity, constraints);
        }

        ContactConstraintProjector.Result projected =
                ContactConstraintProjector.project(
                        velocity,
                        constraints
                );

        if (projected.infeasible()) {
            return ContactVelocityResolution.infeasible(
                    velocity,
                    nonPenetratingFallback(velocity, constraints),
                    constraints
            );
        }

        Vec3d resolved = projected.requireProjectedVector();

        for (ContactConstraintProjector.Constraint constraint
                : constraints) {
            if (constraint.normal().dot(
                    resolved
            ) < constraint.minimumDot()
                    - ContactConstraintProjector.FEASIBILITY_EPSILON * 4.0D) {
                throw new IllegalStateException(
                        "contact velocity commit violates solved constraint: "
                                + constraint
                                + ", resolved="
                                + resolved
                );
            }
        }

        return ContactVelocityResolution.feasible(
                velocity,
                resolved,
                constraints
        );
    }

    /**
     * Pure non-penetrating fallback for an infeasible affine contact set.
     * Projects onto {@code n_i dot v >= 0}, which always contains zero and
     * therefore always yields a finite velocity without adding momentum.
     */
    private static Vec3d nonPenetratingFallback(
            Vec3d desiredVelocity,
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        return ContactConstraintProjector.projectNonPenetrating(
                desiredVelocity,
                constraints
        );
    }

    /** Retain only support credit admitted by the same finite contact response. */
    public static void retainSupportContribution(GravityOperationState runtime, ContactVelocityResolution response) {
        var credit = runtime.supportVelocityContribution();
        if (credit.lengthSquared() == 0) return;
        if (response.infeasible()) {
            runtime.setSupportVelocityContribution(Vec3d.ZERO);
            return;
        }
        // Remove support momentum blocked by the same finite contacts. Positive
        // bounds can inject momentum from another moving obstacle; that impulse
        // stays in world velocity and must not be relabelled as support carry.
        var constraints = response.constraints().stream().map(c ->
                new ContactConstraintProjector.Constraint(c.normal(), Math.min(0, c.minimumDot()))).toList();
        runtime.setSupportVelocityContribution(ContactConstraintProjector.project(credit, constraints).requireProjectedVector());
    }

    /** Reconcile completed landing writes before native step damping. The native
     * join calls this again for branches without stepOn; claiming is idempotent. */
    public static void prepareStepVelocity(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity) return;
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        var missing = runtime.claimSupportVelocity(runtime.currentMoveResult());
        if (missing.lengthSquared() != 0) entity.setDeltaMovement(entity.getDeltaMovement()
                .add(MinecraftMathAdapter.toMinecraft(missing)));
    }

    public static void commitMoveContactVelocity(Entity entity) {
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        if (EntityMovementIntegration.activeMovementCollisionRoute(entity)
                == GravityCollisionRoute.PASSIVE_AABB) {
            entity.setDeltaMovement(PassiveGravityCollisionIntegration.projectVelocity(
                    runtime.currentPassiveMoveResult(), entity.getDeltaMovement()));
            return;
        }
        GravityMoveResult result = runtime.currentMoveResult();
        if (result == null || !result.isAuthoritative()) return;
        prepareStepVelocity(entity); // Also covers native branches that skip stepOn.
        Vec3 desired = entity.getDeltaMovement();
        ContactVelocityResolution response = resolveContactVelocityResult(entity, result, desired);
        retainSupportContribution(runtime, response);
        logVelocityFallback(entity, result, response, "entity-move-contact");
        entity.setDeltaMovement(
                MinecraftMathAdapter.toMinecraft(response.velocity()));
        if (GravityDebugLog.shouldLogMovement(entity)) {
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics
                    .recordVelocityResolution(entity, result, response);
        }
    }
}
