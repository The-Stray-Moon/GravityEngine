package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.movement.MovementExecutionPlan;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.geometry.PositionAuthorityPolicy;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.SubLevelMovementPolicy;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

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

    /**
     * Physical-state inspection at the outer movement boundary. The route is
     * deliberately not selected here: the passive-AABB route is only defined
     * while the owning operation has frozen its frame.
     *
     * <p>This is a pre-operation inspection. It must not be used as the final
     * execution decision: geometry preparation inside the owning operation can
     * legally commit an {@code EXACT_BODY -> NATIVE_AABB} handoff, so the
     * representation read here may be stale by the time the Vanilla
     * {@code Entity.move} body executes. Use
     * {@link MovementExecutionPlan#resolveExecution(BodyRepresentation, GravityCollisionRoute)} to
     * produce the plan that actually describes the execution instant.</p>
     */
    public static MovementExecutionPlan inspectMovement(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return MovementExecutionPlan.decide(
                movementMode(entity),
                installedRepresentation(entity),
                GravityInfluencePolicy.committedPlan(entity),
                GravityInfluencePolicy.hasExternalCollisionProviders(entity)
        );
    }

    /** Movement-only negative proof. Support continuity keeps the conservative
     * path because it can widen the trajectory beyond the requested translation. */
    private static MovementExecutionPlan inspectMovement(Entity entity, Vec3 movement) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        if (!GravityInfluencePolicy.hasExternalCollisionProviders(entity)
                || runtime.persistentSupportState() != null
                || GravityEntityAccess.cast(entity).gravityengine$gravityComponent().moveInterop().subLevelMoveEvidence() != null)
            return inspectMovement(entity);
        var domain = CollisionCaptureDomain.forTranslation(
                MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox()),
                MinecraftMathAdapter.toVec3d(movement), entity.maxUpStep());
        boolean external = cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.mayAffect(
                entity.level(), new cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery(
                        domain.staticBounds(), domain.dynamicEntityBounds(),
                        cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(entity.level().getGameTime(), 0)));
        return MovementExecutionPlan.decide(movementMode(entity), installedRepresentation(entity),
                GravityInfluencePolicy.committedPlan(entity), external);
    }

    /**
     * The locomotion policy currently published by the runtime.
     */
    public static GravityOperationState.MovementMode movementMode(
            Entity entity
    ) {
        Objects.requireNonNull(entity, "entity");
        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .operationState()
                .movementMode();
    }

    /**
     * The installed collider representation as it exists now.
     *
     * <p>Derived only from committed installed geometry; the same authoritative
     * state every other representation consumer reads. Reading it again after
     * geometry preparation is how the outer movement boundary observes a
     * committed {@code EXACT_BODY -> NATIVE_AABB} handoff.</p>
     */
    public static BodyRepresentation installedRepresentation(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return BodyRepresentation.ofAxis(
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent()
                        .operationState()
                        .installedCollisionUp()
        );
    }



    /**
     * The frozen collision route of the GravityEngine movement transaction
     * that currently owns this invocation, or {@code null} when no such
     * transaction exists.
     *
     * <p>This is the only route query an inner Vanilla/{@code Entity.move}
     * seam may use. It never consults live policy, so
     *
     * <ul>
     *   <li>a null result means the seam is not owned by GravityEngine and must
     *       preserve Vanilla behavior (a null frozen route never grants GE
     *       ownership);</li>
     *   <li>a non-null result is the ownership decision already frozen by
     *       {@link GravityOperationState#beginMovement(GravityCollisionRoute)}
     *       at the outer movement boundary.</li>
     * </ul>
     *
     * <p>If a seam that requires GE physical data observes {@code null}, the
     * defect is at the outer {@code Entity.move} boundary, not here. Do not
     * repair it by sampling a frame or recomputing
     * {@link GravityInfluencePolicy#collisionRoute(Entity)}.</p>
     */
    public static GravityCollisionRoute activeMovementCollisionRoute(
            Entity entity
    ) {
        Objects.requireNonNull(entity, "entity");
        if (FallingBlockTickIntegration.isUnavailable(entity)) {
            return null;
        }
        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .operationState()
                .movementCollisionRoute();
    }

    /**
     * True when the currently executing {@code Entity.move} is owned by a
     * GravityEngine collision transaction.
     */
    public static boolean engineOwnsActiveMovement(Entity entity) {
        GravityCollisionRoute route =
                activeMovementCollisionRoute(entity);
        return route != null && route != GravityCollisionRoute.VANILLA;
    }

    /**
     * Outer-movement-boundary collision-route selection.
     *
     * <p>This method must be called exactly once per physical
     * {@code Entity.move}, before the Vanilla move body executes, and its
     * result must be frozen through
     * {@link GravityOperationState#beginMovement(GravityCollisionRoute)}.
     * Inner Vanilla/Mixin seams must never call it.</p>
     */
    public static GravityCollisionRoute selectCollisionRouteForOperation(
            Entity entity
    ) {
        Objects.requireNonNull(entity, "entity");
        if (FallingBlockTickIntegration.isUnavailable(entity)) {
            return GravityCollisionRoute.VANILLA;
        }
        return GravityInfluencePolicy.collisionRoute(entity);
    }

    /**
     * Committed collision ownership for questions that are not part of one
     * physical {@code Entity.move}: impulse provenance, velocity diagnostics
     * and support setters that also run outside movement (jump, packet ground,
     * lifecycle).
     *
     * <p>It deliberately does not consult the frozen movement route: those
     * consumers describe the entity's currently committed policy, not the
     * ownership of a particular move.</p>
     */
    public static GravityCollisionRoute committedCollisionRoute(
            Entity entity
    ) {
        Objects.requireNonNull(entity, "entity");
        if (FallingBlockTickIntegration.isUnavailable(entity)) {
            return GravityCollisionRoute.VANILLA;
        }
        return GravityInfluencePolicy.collisionRoute(entity);
    }

    /** Ordinary native seams run only after collide. noPhysics/piston early returns never enter here. */
    public static VanillaCollisionState movementCollisionState(Entity entity) {
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        var operationState = component.operationState();
        if (operationState.discontinuityDestination() != null) return null;
        if (!engineOwnsActiveMovement(entity)) return null;
        var state = component.moveInterop().currentCollisionState();
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
        /*
         * Inside one physical Entity.move the frozen route is the ownership
         * authority. Support setters also run outside movement (jump, packet
         * ground, lifecycle); there the committed policy is the only owner.
         */
        GravityCollisionRoute route = activeMovementCollisionRoute(entity);
        if (route == null) {
            route = committedCollisionRoute(entity);
        }
        if (route == GravityCollisionRoute.VANILLA) return null;
        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .moveInterop()
                .currentCollisionStateOrEmpty();
    }

    /** A fall callback has different landing semantics from gameplay ground continuity. */
    public record FallMovement(double vertical, boolean landed) {}

    public static FallMovement fallMovement(Entity entity, double vanillaVertical, boolean vanillaGrounded) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        GravityCollisionRoute route = activeMovementCollisionRoute(entity);
        if (route == null) {
            route = GravityCollisionRoute.VANILLA;
        }
        return switch (route) {
            case VANILLA -> new FallMovement(vanillaVertical, vanillaGrounded);
            case EXACT_BODY -> {
                movementCollisionState(entity);
                var result = runtime.currentMoveResult();
                yield result != null && result.isAuthoritative()
                        ? new FallMovement(result.fallDistanceVertical(runtime.activeReferenceFrame().up()), result.landedOnStableSupport()
                                || result.blockedDown() && result.movementSupportContact().isPresent()) : null;
            }
            case PASSIVE_AABB -> {
                movementCollisionState(entity);
                var result = runtime.currentPassiveMoveResult();
                yield PassiveGravityCollisionIntegration.isAuthoritative(result)
                        ? new FallMovement(PassiveGravityCollisionIntegration.fallDistanceVertical(result, runtime.activeReferenceFrame().up()),
                        PassiveGravityCollisionIntegration.landedOnPhysicalSupport(result)) : null;
            }
        };
    }

    public static boolean movementVerticalResponse(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity
                && activeMovementCollisionRoute(entity) == GravityCollisionRoute.PASSIVE_AABB) {
            var result = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState().currentPassiveMoveResult();
            return result != null && !result.indeterminate() && !result.impacts().isEmpty();
        }
        var state = movementCollisionState(entity);
        return state != null && state.verticalCollision();
    }

    public static Vec3 collide(Entity entity, Vec3 movement) {
        var component =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent();
        var runtime = component.operationState();

        /*
         * Entity.collide is an inner seam of the currently executing
         * Entity.move. It is only reachable when that move froze a non-Vanilla
         * route; a null route here is an outer-boundary ownership defect and
         * must fail close to the adapter instead of being repaired with an
         * ad-hoc frame or live policy.
         */
        GravityCollisionRoute route =
                activeMovementCollisionRoute(entity);
        if (route == null) {
            throw new IllegalStateException(
                    "GravityEngine collide requires a frozen movement route "
                            + "from the owning Entity.move"
            );
        }

        ExternalSubLevelMoveEvidence external = null;

        if (route != GravityCollisionRoute.VANILLA) {
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
                    component.moveInterop().subLevelMoveEvidence() != null;

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
                component.moveInterop().setSubLevelMoveEvidence(external);

                Vec3 currentVelocity =
                        entity.getDeltaMovement();

                Vec3 preserved =
                        SubLevelMovementPolicy
                                .preserveExternalContactVelocity(
                                        currentVelocity,
                                        external,
                                        MinecraftMathAdapter.toMinecraft(
                                                runtime.activeFrame().up())
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

        /*
         * Engine-owned persistent support transport is part of the requested
         * trajectory, not post-solve carry. It is applied before the one
         * authoritative collision solve and consumed at most once.
         */
        var input = MinecraftMathAdapter.toVec3d(parentWorldInput);
        var body = GravityEntityGeometry.body(entity);
        var preResolved = runtime.preResolvedTranslation(input, body);
        if (preResolved.isPresent()) {
            return MinecraftMathAdapter.toMinecraft(preResolved.get());
        }

        // Only this solve owns the consumed transport. Independent requests,
        // including auxiliary support and packet requests, cannot replay it.
        SupportTransport transport = runtime.currentMovementChannel()
                == KinematicMoveRequest.Channel.SELF
                ? runtime.consumeEngineSupportTransport().orElse(null) : null;
        Vec3 requestedInput = transport == null ? parentWorldInput
                : parentWorldInput.add(MinecraftMathAdapter.toMinecraft(
                        transport.displacement()));
        var request = transport == null
                ? runtime.reconcileActualMovement(input)
                : runtime.reconcileActualMovement(
                        MinecraftMathAdapter.toVec3d(requestedInput), transport.displacement());
        Vec3 resolved;
        switch (route) {
            case VANILLA -> {
                // The collide Mixin normally excludes Vanilla. Preserve the
                // physical identity operation if this facade is called directly.
                return requestedInput;
            }
            case PASSIVE_AABB -> {
                resolved = PassiveGravityCollisionIntegration.collide(entity, requestedInput);
            }
            case EXACT_BODY -> resolved = GravityCollisionEngine.collide(entity, request, transport);
            default -> throw new IllegalStateException("unknown collision route: " + route);
        }

        /*
         * The collision solver alone decides the committed displacement. A
         * later velocity/contact response never re-solves, clips or rejects
         * this valid translation.
         */
        runtime.setPreResolved(input, body, MinecraftMathAdapter.toVec3d(resolved));
        return resolved;
    }

    private static Vec3 collideExternalSupportTransport(
            Entity entity,
            Vec3 movement,
            GravityOperationState runtime,
            GravityCollisionRoute route
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
                        MinecraftMathAdapter.toVec3d(movement),
                        OwnedMotion.ZERO
                ).reconcile(MinecraftMathAdapter.toVec3d(movement));

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

                    resolved = MinecraftMathAdapter.toMinecraft(
                            endpointResult.resolvedMovement());
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
            if (GravityDebugLog.shouldLog(entity)) {
                GravityDebugLog.log(entity,
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
                    MinecraftMathAdapter.toVec3d(entity.position()),
                    MinecraftMathAdapter.toVec3d(resolved),
                    endpointResult
            );
        } else {
            runtime.stageExternalSupportTransport(
                    MinecraftMathAdapter.toVec3d(entity.position()),
                    MinecraftMathAdapter.toVec3d(resolved)
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
        var trace = GravityDebugLog.shouldLogMovement(entity)
                ? cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.begin(entity, moverType, movement)
                : null;
        var packetMove = cc.sighs.gravityengine.network.ServerPlayerMovementReceiver.claimRootMove(entity);
        Vec3 beforeMove = entity.position();
        boolean completed = false;
        try {
            moveObserved(entity, moverType, movement, vanillaMove, packetMove);
            completed = true;
        } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                | CollisionSceneCoverageException unavailable) {
            if (packetMove != null) packetMove.unavailable(unavailable.getMessage());
            // Freeze translation at the last proven body; preserve physical velocity for retry.
            cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity)
                    .gravityengine$gravityComponent().operationState().invalidateMovementContinuity();
            cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity).gravityengine$setVanillaSupportingBlock(null);
            entity.setOnGround(false);
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(entity, unavailable);
        } finally {
            if (packetMove == null && (!completed || !entity.position().equals(beforeMove))) {
                cc.sighs.gravityengine.network.ServerPlayerMovementReceiver.invalidateFacts(
                        entity, "nested movement changed the root endpoint or failed");
            }
            if (trace != null) {
                if (completed) trace.finish(entity);
                else trace.abort(entity);
            }
        }
    }

    private static void moveObserved(Entity entity, MoverType moverType, Vec3 movement, Runnable vanillaMove,
            cc.sighs.gravityengine.network.ServerPlayerMovementReceiver.Invocation packetMove) {
        if (FallingBlockTickIntegration.isUnavailable(entity)) { vanillaMove.run(); return; }
        Objects.requireNonNull(entity);
        Objects.requireNonNull(moverType);
        Objects.requireNonNull(movement);
        Objects.requireNonNull(vanillaMove);
        cc.sighs.gravityengine.gravity.integration.geometry.BodyCommitTransaction.requireOutside(entity);
        GravityOperationState rt = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        if (rt.discontinuityDestination() != null) return;
        if (!rt.isInMove()) {
            MovementModeIntegration.update(entity);
        }

        // Nested move: this call is already inside an owning operation. The
        // outer boundary of this physical move freezes its own route below.
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

        /*
         * OUTER MOVEMENT BOUNDARY.
         *
         * Locomotion mode, installed representation, committed application and
         * external collision ownership are inspected once. The pure-Vanilla
         * fast path is physical, never modal:
         *
         *   no committed application requiring a GE operation
         *       AND no installed exact GE body
         *       AND no external collision owner requiring GE routing
         *
         * MovementMode.NATIVE_FALLBACK is intentionally absent from this
         * predicate. Vanilla may own locomotion semantics while GravityEngine
         * still clips the request against an installed exact body.
         */
        MovementExecutionPlan decision = inspectMovement(entity, movement);
        if (!decision.operationRequired()) {
            /*
             * The pure-Vanilla fast path is only legal when the installed
             * representation is physically native-compatible too; the
             * inspection already proves it, and the invariant is asserted
             * explicitly rather than assumed.
             */
            MovementExecutionPlan nativePlan = decision.resolveExecution(
                    decision.installedRepresentation(),
                    GravityCollisionRoute.VANILLA
            );
            logMovementOwnership(entity, nativePlan);
            if (packetMove != null) packetMove.selectRoute(GravityCollisionRoute.VANILLA);
            vanillaMove.run();
            return;
        }

        // Outermost move: one operation owns one frozen frame/scene. The
        // capture domain is derived from the actual requested movement before
        // Vanilla runs, so no solver query can lazily re-read the world.
        Optional<SupportTransport> supportTransportPreflight =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent()
                        .moveInterop()
                        .subLevelMoveEvidence() == null
                        ? EngineSupportTransportIntegration.preflight(
                                entity,
                                rt,
                                entity.level().getGameTime(),
                                1.0D
                        )
                        : Optional.empty();
        Vec3 requestedTrajectory = supportTransportPreflight
                .map(SupportTransport::displacement)
                .map(MinecraftMathAdapter::toMinecraft)
                .map(movement::add)
                .orElse(movement);
        Optional<cc.sighs.gravityengine.math.geometry.Aabb3d>
                supportRelativeBounds =
                supportTransportPreflight
                        .flatMap(SupportTransport::trajectory)
                        .filter(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::requiresGeneralSweep
                        )
                        .map(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::relativeBounds
                        );
        var captureDomain = supportRelativeBounds
                .map(relative -> CollisionCaptureDomain
                        .forTranslationRange(
                                MinecraftCollisionGeometryAdapter
                                        .toAabb3d(
                                                entity.getBoundingBox()
                                        ),
                                MinecraftMathAdapter.toVec3d(
                                        movement
                                ),
                                MinecraftMathAdapter.toVec3d(
                                        movement
                                ),
                                relative,
                                entity.maxUpStep()
                        ))
                .orElseGet(() ->
                        CollisionCaptureDomain.forTranslation(
                                MinecraftCollisionGeometryAdapter
                                        .toAabb3d(
                                                entity.getBoundingBox()
                                        ),
                                MinecraftMathAdapter.toVec3d(
                                        requestedTrajectory
                                ),
                                entity.maxUpStep()
                        ));
        var op = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(
                entity,
                GravityOperationType.MOVE,
                rt.installedCollisionUp() == null
                        ? GravityEntityGeometry.proxyCenter(entity)
                        : GravityEntityGeometry.bodyCenter(entity),
                1.0D,
                captureDomain,
                // Vanilla's packet loop owns the position anchor it captured:
                // its pre-move baseline, lastGood bookkeeping and correction
                // destination all describe that anchor, so the current
                // Entity.move must not re-anchor the pose. Ordinary self-driven
                // movement keeps the normal support-preserving preparation.
                moverType == MoverType.PLAYER
                        ? PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : PositionAuthorityPolicy.OPERATION_MAY_REANCHOR
        );
        Vec3 solvedEndpoint;
        long solvedBodyRevision;
        try (op) {
            /*
             * Select the collision route exactly once for this physical move,
             * now that the operation frame/scene are frozen.
             *
             * The final execution plan must describe one coherent physical
             * instant. Geometry preparation inside the operation above may
             * legally have committed EXACT_BODY -> NATIVE_AABB, so the
             * pre-operation inspection's representation is re-read here
             * instead of being carried into the execution decision. A
             * subsequent route is paired with that post-preparation
             * representation, and the combination is validated before
             * anything enters the Vanilla move body.
             */
            MovementExecutionPlan plan = decision.resolveExecution(
                    EntityMovementIntegration.installedRepresentation(entity),
                    selectCollisionRouteForOperation(entity)
            );
            logMovementOwnership(entity, plan);
            if (packetMove != null) {
                packetMove.selectRoute(plan.collisionRoute());
                op.captureCompletedMovementFacts();
            }
            EngineSupportTransportIntegration.stageResolvedTransport(
                    rt,
                    supportTransportPreflight
            );
            solvedBodyRevision = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().state().applicationEpoch();
            runOwnedMove(
                    entity,
                    moverType,
                    movement,
                    vanillaMove,
                    rt,
                    plan.collisionRoute()
            );
            solvedEndpoint = entity.position();
        }
        if (packetMove != null) packetMove.complete(op.completedMovementFacts(), solvedEndpoint, solvedBodyRevision);
    }

    private static void runOwnedMove(Entity entity, MoverType type, Vec3 movement,
                                     Runnable vanillaMove, GravityOperationState runtime) {
        /*
         * A nested physical move is its own outer boundary: it selects and
         * freezes exactly one route for this invocation, exactly as the
         * pre-existing nested-move contract did. A nested operation never
         * re-prepares geometry, so the currently installed representation is
         * already the execution-time representation; the combination is
         * validated here before the route is published.
         */
        GravityCollisionRoute route = selectCollisionRouteForOperation(entity);
        MovementExecutionPlan.requireCoherentExecution(
                EntityMovementIntegration.installedRepresentation(entity),
                route
        );
        runOwnedMove(
                entity,
                type,
                movement,
                vanillaMove,
                runtime,
                route
        );
    }

    private static void runOwnedMove(
            Entity entity,
            MoverType type,
            Vec3 movement,
            Runnable vanillaMove,
            GravityOperationState runtime,
            GravityCollisionRoute route
    ) {
        runtime.beginMovement(route);
        var evidence = MovementProvenanceIntegration.capture(entity, type, movement);
        try (var ignored = runtime.openMovementEvidence(evidence)) {
            vanillaMove.run();
            if (route == GravityCollisionRoute.VANILLA && runtime.discontinuityDestination() == null) {
                // A native-compatible collider can still have GE character controls.
                // Observe its actual endpoint in the captured scene; vanilla onGround
                // is a gameplay carrier and cannot authorize free-flight activation.
                var operation = runtime.collisionOperation();
                try {
                    runtime.setEndpointSupportOverride(cc.sighs.gravityengine.gravity.collision.GravityGroundProbe.probe(
                            GravityEntityGeometry.body(entity), operation.frame(), operation.scene(),
                            operation.time().intervalTicks(), operation.geometryContext()));
                } catch (CollisionSceneCoverageException
                        | cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException unavailable) {
                    runtime.markEndpointSupportIndeterminate();
                    cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(entity, unavailable);
                }
            }
        }
    }

    /**
     * One outer movement-ownership diagnostic per physical move: locomotion
     * mode, installed representation, committed application, selected
     * collision route, whether an operation was required and why.
     */
    private static void logMovementOwnership(
            Entity entity,
            MovementExecutionPlan plan
    ) {
        if (!GravityDebugLog.shouldLogMovement(entity)) {
            return;
        }
        GravityDebugLog.log(
                entity,
                "movement-ownership",
                "movementMode=%s executionRepresentation=%s "
                        + "committedApplication=%s externalCollisionProviders=%s "
                        + "collisionRoute=%s operationRequired=%s reason=%s",
                plan.movementMode(),
                plan.installedRepresentation(),
                plan.committedApplication().kind(),
                plan.externalCollisionProviders(),
                plan.collisionRoute(),
                plan.operationRequired(),
                plan.reason()
        );
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
        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        GravityOperationState rt = component.operationState();
        if (rt.isInMove() || rt.isApplyingGeometry()) {
            throw new IllegalStateException(
                    "cannot invalidate movement continuity inside a physics "
                            + "or geometry scope");
        }
        cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.discontinuity(entity);
        rt.invalidateMovementContinuity();
        component.moveInterop().clear();
        if (entity instanceof cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess control)
            control.gravityengine$characterControl().clear();
    }
}
