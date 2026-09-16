package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import cc.sighs.gravityengine.gravity.runtime.SubLevelMovementPolicy;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Vanilla {@code Entity.move} / {@code Entity.collide} movement integration.
 *
 * <p>Vanilla continues to own the movement callbacks and side effects. The
 * custom collide hook returns one resolved translation from the pure resolver;
 * the move wrapper commits that translation through Vanilla's positional write
 * and the owning geometry boundary re-anchors the exact gravity-oriented body
 * afterwards.</p>
 */
public final class EntityMovementIntegration {
    private EntityMovementIntegration() {}

    /** Frozen after preparation for a physical move; independent support calls use current ownership. */
    public static GravityInfluencePolicy.CollisionRoute movementRoute(Entity entity) {
        var selected = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime().movementCollisionRoute();
        return selected != null ? selected : GravityInfluencePolicy.collisionRoute(entity);
    }

    /** Ordinary native seams run only after collide. noPhysics/piston early returns never enter here. */
    public static VanillaCollisionState movementCollisionState(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        if (runtime.discontinuityDestination() != null) return null;
        if (movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) return null;
        var state = runtime.movementCollisionState();
        if (state == null) throw new IllegalStateException("ordinary custom Entity.move seam requires its collision result");
        return state;
    }

    /** Install only the flags preceding native minor-contact policy; ground/support commit later. */
    public static void installMovementCollisionFlags(Entity entity) {
        var state = movementCollisionState(entity);
        if (state == null) return;
        entity.horizontalCollision = state.horizontalCollision();
        entity.verticalCollision = state.verticalCollision();
        entity.verticalCollisionBelow = state.verticalCollisionBelow();
    }

    /**
     * Support setters also run outside movement (jump, packet ground, lifecycle).
     * Only the move projection supplies a named witness; absent evidence clears
     * custom support without requiring a fictitious collision solve.
     */
    public static VanillaCollisionState supportingCollisionState(Entity entity) {
        if (movementRoute(entity) == GravityInfluencePolicy.CollisionRoute.VANILLA) return null;
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        var state = runtime.movementCollisionState();
        return state != null ? state : new VanillaCollisionState(false, false, false, false, false, Optional.empty());
    }

    /** A fall callback has different landing semantics from gameplay ground continuity. */
    public record FallMovement(double vertical, boolean landed) {}

    public static FallMovement fallMovement(Entity entity, double vanillaVertical, boolean vanillaGrounded) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        return switch (movementRoute(entity)) {
            case VANILLA -> new FallMovement(vanillaVertical, vanillaGrounded);
            case EXACT_BODY -> {
                movementCollisionState(entity);
                var result = runtime.currentMoveResult();
                yield result != null && result.isAuthoritative()
                        ? new FallMovement(result.fallDistanceVertical(), result.landedOnStableSupport()) : null;
            }
            case PASSIVE_AABB -> {
                movementCollisionState(entity);
                var result = runtime.currentPassiveMoveResult();
                yield PassiveGravityCollisionIntegration.isAuthoritative(result)
                        ? new FallMovement(PassiveGravityCollisionIntegration.fallDistanceVertical(result),
                        PassiveGravityCollisionIntegration.landedOnPhysicalSupport(result)) : null;
            }
        };
    }

    public static boolean movementVerticalResponse(Entity entity) {
        var state = movementCollisionState(entity);
        return state != null && state.verticalCollision();
    }

    public static Vec3 collide(Entity entity, Vec3 movement) {
        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        GravityInfluencePolicy.CollisionRoute route =
                movementRoute(entity);

        ExternalSubLevelMoveEvidence external = null;

        if (route != GravityInfluencePolicy.CollisionRoute.VANILLA) {
            /*
             * The first Sable -> parent-world collide belongs to the ordinary
             * Entity.move transaction.
             *
             * After that solve has been captured, Sable may call Entity.collide
             * again from LivingEntity.travel for inheritedMotion. The two vectors
             * are allowed to be numerically identical, so call phase -- not Vec3
             * equality -- distinguishes the two operations.
             */
            boolean parentSubLevelSolveAlreadyCaptured =
                    runtime.externalSubLevelMoveEvidence() != null;

            if (parentSubLevelSolveAlreadyCaptured
                    && SableMovementCompatibility
                    .isInheritedSupportTransport(
                            entity,
                            movement
                    )) {
                return collideExternalSupportTransport(
                        entity,
                        movement,
                        runtime,
                        route
                );
            }

            /*
             * No parent solve has yet been consumed for this physical movement.
             * Capture Sable's completed SubLevel result now.
             */
            external =
                    SableMovementCompatibility.capture(
                            entity,
                            movement
                    );

            if (external != null) {
                runtime.setExternalSubLevelMoveEvidence(external);

                Vec3 currentVelocity =
                        entity.getDeltaMovement();

                Vec3 preserved =
                        SubLevelMovementPolicy
                                .preserveExternalContactVelocity(
                                        currentVelocity,
                                        external,
                                        runtime.activeFrame().up()
                                );

                if (!preserved.equals(currentVelocity)) {
                    entity.setDeltaMovement(preserved);
                }
            }
        }
        Vec3 parentWorldInput =
                SubLevelMovementPolicy.parentWorldSolveInput(
                        movement,
                        external
                );
        Optional<Vec3> preResolved =
                runtime.preResolvedTranslation(parentWorldInput);
        if (preResolved.isPresent()) {
            // Entity.move still owns vanilla callbacks. This guard only
            // prevents a resolved translation from being solved a second time
            // if collide is re-entered for the identical input.
            return preResolved.get();
        }
        var request = runtime.reconcileActualMovement(parentWorldInput);
        Vec3 resolved;
        switch (route) {
            case VANILLA -> {
                // The collide Mixin normally excludes Vanilla. Preserve the
                // physical identity operation if this facade is called directly.
                return parentWorldInput;
            }
            case PASSIVE_AABB -> {
                return PassiveGravityCollisionIntegration.collide(
                        entity,
                        parentWorldInput
                );
            }
            case EXACT_BODY -> resolved = GravityCollisionEngine.collide(entity, request);
            default -> throw new IllegalStateException("unknown collision route: " + route);
        }

        /*
         * The collision solver alone decides the committed displacement. A
         * later velocity/contact response never re-solves, clips or rejects
         * this valid translation.
         */
        return resolved;
    }

    private static Vec3 collideExternalSupportTransport(
            Entity entity,
            Vec3 movement,
            GravityRuntimeState runtime,
            GravityInfluencePolicy.CollisionRoute route
    ) {
        if (!runtime.isInMove()
                || runtime.collisionOperation() == null) {
            throw new IllegalStateException(
                    "Sable inherited support transport escaped its owning "
                            + "MOVE/TRAVEL collision scope"
            );
        }

        KinematicMoveRequest request =
                MovementEvidence.capture(
                        KinematicMoveRequest.Channel.SUPPORT_TRANSPORT,
                        movement,
                        OwnedMotion.ZERO
                ).reconcile(movement);

        Vec3 resolved;
        GravityMoveResult endpointResult = null;

        try {
            switch (route) {
                case EXACT_BODY -> {
                    /*
                     * Keep the full auxiliary result only as endpoint evidence.
                     * Do not install it as runtime.currentMoveResult().
                     */
                    endpointResult =
                            GravityCollisionEngine
                                    .resolveUnpublished(
                                            entity,
                                            request
                                    );

                    resolved =
                            MinecraftGeometryAdapter
                                    .toMinecraft(
                                            endpointResult
                                                    .resolvedMovement()
                                    );
                }

                case PASSIVE_AABB -> {
                    resolved =
                            PassiveGravityCollisionIntegration
                                    .collideUnpublished(
                                            entity,
                                            movement
                                    );
                }

                case VANILLA -> resolved = movement;

                default ->
                        throw new IllegalStateException(
                                "unknown collision route: "
                                        + route
                        );
            }
        } catch (CollisionSceneCoverageException uncovered) {
            if (GravityDebugLog.ENABLED) {
                GravityDebugLog.log(
                        entity,
                        "sable-support-transport-coverage-fail",
                        "movement=%s reason=%s",
                        GravityDebugLog.vec(movement),
                        uncovered.getMessage()
                );
            }

            /*
             * No displacement is applied, so the ordinary Entity.move endpoint
             * remains the physical endpoint and remains valid.
             */
            resolved = Vec3.ZERO;
            endpointResult = null;
        }

        if (endpointResult != null) {
            runtime.stageExternalSupportTransport(
                    entity.position(),
                    resolved,
                    endpointResult
            );
        } else {
            runtime.stageExternalSupportTransport(
                    entity.position(),
                    resolved
            );
        }

        return resolved;
    }

    public static void move(
            Entity entity,
            MoverType moverType,
            Vec3 movement,
            Runnable vanillaMove
    ) {
        var trace = GravityDebugLog.MOVEMENT_ENABLED
                ? cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.begin(entity, moverType, movement)
                : null;
        try {
            moveObserved(entity, moverType, movement, vanillaMove);
        } finally {
            if (trace != null) trace.finish(entity);
        }
    }

    private static void moveObserved(Entity entity, MoverType moverType, Vec3 movement, Runnable vanillaMove) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(moverType);
        Objects.requireNonNull(movement);
        Objects.requireNonNull(vanillaMove);
        GravityRuntimeState rt = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().runtime();
        if (rt.discontinuityDestination() != null) return;
        var plan = GravityInfluencePolicy.committedPlan(entity);
        if (!plan.needsGravityOperation() && !GravityInfluencePolicy.usesCustomBody(entity)) {
            vanillaMove.run();
            return;
        }

        // Nested move: reuse the outer operation frame. Do not reinstall body.
        if (rt.isInMove()) {
            try (var scope = rt.openMove(
                    rt.activeFrame(),
                    entity.level().getGameTime(),
                    GravityOperationType.MOVE
            )) {
                /*
                 * A nested physical move in the same tick borrows the outer
                 * operation's immutable GravityFrame and CollisionScene; it
                 * never captures or replaces collision world state.
                 */
                runOwnedMove(entity, moverType, movement, vanillaMove, rt);
            }
            return;
        }

        // Outermost move: one operation owns one frozen frame/scene. The
        // capture domain is derived from the actual requested movement before
        // Vanilla runs, so no solver query can lazily re-read the world.
        var captureDomain = CollisionCaptureDomain.forTranslation(
                MinecraftGeometryAdapter.toAabb3d(
                        entity.getBoundingBox()),
                MinecraftGeometryAdapter.toJoml(
                        movement, new Vector3d()),
                entity.maxUpStep());
        try (var op = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(
                entity,
                GravityOperationType.MOVE,
                rt.geometryReferenceFrame() == null
                        ? GravityEntityGeometry.proxyCenter(entity)
                        : GravityEntityGeometry.bodyCenter(
                                entity, rt.geometryReferenceFrame()
                        ),
                1.0D,
                captureDomain,
                // Vanilla's packet loop owns the position anchor it captured:
                // its pre-move baseline, lastGood bookkeeping and correction
                // destination all describe that anchor, so the current
                // Entity.move must not re-anchor the pose. Ordinary self-driven
                // movement keeps the normal support-preserving preparation.
                moverType == MoverType.PLAYER
                        ? GravityGeometryTransitionService
                        .PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : GravityGeometryTransitionService
                        .PositionAuthorityPolicy.OPERATION_MAY_REANCHOR
        )) {
            runOwnedMove(entity, moverType, movement, vanillaMove, rt);
        }
    }

    private static void runOwnedMove(Entity entity, MoverType type, Vec3 movement,
                                     Runnable vanillaMove, GravityRuntimeState runtime) {
        runtime.beginMovement(GravityInfluencePolicy.collisionRoute(entity));
        var evidence = MovementProvenanceIntegration.capture(entity, type, movement);
        try (var ignored = runtime.openMovementEvidence(evidence)) {
            vanillaMove.run();
        }
    }

    /**
     * Discontinuous position/body lifecycle entry (teleport,
     * respawn, dimension transfer, {@code refreshDimensions} and application
     * transitions that destroy movement continuity).
     *
     * <p>Owned {@code Entity.move} commits never call this method: the
     * owning operation consumes/closes its provenance. Invoking this entry
     * inside an active physics or geometry scope is a caller bug and is
     * rejected rather than silently corrupting the live transaction.</p>
     */
    public static void invalidateMovementContinuity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        GravityRuntimeState rt = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime();
        if (rt.isInMove() || rt.isApplyingGeometry()) {
            throw new IllegalStateException(
                    "cannot invalidate movement continuity inside a physics "
                            + "or geometry scope");
        }
        if (GravityDebugLog.MOVEMENT_ENABLED) {
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.discontinuity(entity);
        }
        rt.invalidateMovementContinuity();
        if (entity instanceof cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess control)
            control.gravityengine$characterControl().clear();
    }
}