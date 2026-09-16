package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState.CollisionOperationContext;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin Minecraft adapter over the pure {@link GravityCharacterRoute}.
 *
 * <p>This adapter only obtains the active frame/scene/body, invokes the pure
 * resolver, and stores the immutable {@link GravityMoveResult} for the current
 * move lifecycle. It owns no support continuity, no detach state, no ground
 * authority, no character routing, no traversal, and no gameplay velocity
 * policy.</p>
 */
public final class GravityCollisionEngine {
    /** World-down retains authored step height regardless of actor attitude.
     * Non-default gravity retains the explicit static-voxel allowance. */
    public static StepUpIntent stepIntent(GravityFrame frame, double authoredHeight) {
        return !GravityInfluencePolicy.requiresReferenceGeometry(frame)
                ? new StepUpIntent(Math.max(0.0D, authoredHeight))
                : StepUpIntent.customGravity(authoredHeight);
    }

    private GravityCollisionEngine() {}

    /**
     * Entry point used by {@code Entity.collide}.
     *
     * <p>Does not modify position, bounding box, velocity, or ground state;
     * the caller commits the returned translation through vanilla
     * {@code Entity.move} side effects.</p>
     */
    public static Vec3 collide(Entity entity, KinematicMoveRequest request) {
        GravityRuntimeState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        GravityMoveResult result =
                resolve(entity, request);

        Vec3 resolved =
                MinecraftGeometryAdapter.toMinecraft(
                        result.resolvedMovement()
                );

        runtime.setCurrentMoveResult(result);
        runtime.setPreResolved(
                request.actualMovement(),
                resolved
        );

        return resolved;
    }

    /**
     * Resolves an environment-owned auxiliary translation against the already
     * frozen operation scene without replacing the ordinary Entity.move result.
     */
    public static Vec3 collideUnpublished(
            Entity entity,
            KinematicMoveRequest request
    ) {
        return MinecraftGeometryAdapter.toMinecraft(
                resolveUnpublished(
                        entity,
                        request
                ).resolvedMovement()
        );
    }

    /**
     * Resolves an auxiliary environment-owned translation against the already
     * frozen operation scene without replacing the ordinary Entity.move result.
     *
     * <p>The returned result may be consumed only for facts belonging to this
     * auxiliary translation, notably its final endpoint support. It is never
     * installed as {@link GravityRuntimeState#currentMoveResult()}.</p>
     */
    public static GravityMoveResult resolveUnpublished(
            Entity entity,
            KinematicMoveRequest request
    ) {
        return resolve(entity, request);
    }

    private static GravityMoveResult resolve(
            Entity entity,
            KinematicMoveRequest request
    ) {
        GravityEntityAccess access = GravityEntityAccess.cast(entity);
        GravityRuntimeState runtime = access.gravityengine$gravityComponent().runtime();
        GravityFrame frame = runtime.activeFrame();

        CollisionOperationContext operation = runtime.collisionOperation();
        ObbQueryContext queryContext;
        CollisionScene scene;
        if (operation != null) {
            queryContext = operation.geometryContext();
            scene = operation.scene();
        } else {
            throw new IllegalStateException(
                    "custom collision solve has no active outer collision "
                            + "operation; refusing to lazily capture a scene"
            );
        }

        CollisionBody body =
                GravityEntityGeometry.body(entity, frame);
        if (GravityDebugLog.MOVEMENT_ENABLED) {
            queryContext.setStepDecision(null);
            queryContext.beginCollisionTrace();
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.input(entity,
                    (cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox) body,
                    request, frame, scene, "EXACT_BODY");
        }
        var previousSupport = runtime.restingContactSnapshot();
        queryContext.setPreferredSupportFace(previousSupport != null
                && previousSupport.gameTick() >= scene.time().gameTick() - 1
                ? previousSupport.faceIdentity() : null);
        GravityMoveResult result = GravityCharacterRoute.resolve(
                body,
                request,
                frame,
                scene,
                queryContext,
                stepIntent(frame, entity.maxUpStep())
        );
        if (GravityDebugLog.MOVEMENT_ENABLED) {
            cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.result(entity, result, queryContext);
        }
        if (GravityDebugLog.ENABLED) {
            Vec3 velocity = entity.getDeltaMovement();
            GravityDebugLog.log(
                    entity,
                    "move-resolved",
                    "requestWorld=%s requestLocal=%s resolvedWorld=%s "
                            + "resolvedLocal=%s recoveryWorld=%s locomotionWorld=%s "
                            + "blockedDown=%s blockedUp=%s "
                            + "blockedTangent=%s grounded=%s supportBlock=%s "
                            + "stepHeight=%.9f indeterminate=%s indeterminateReason=%s "
                            + "supportFollowRise=%.9f tangentBlockingNormals=%s "
                            + "velocityAtResolveWorld=%s velocityAtResolveLocal=%s "
                            + "positionAnchor=%s frameDown=%s",
                    GravityDebugLog.vec(result.requestedMovement()),
                    GravityDebugLog.vec(frame.worldToLocal(
                            MinecraftGeometryAdapter.toMinecraft(
                                    result.requestedMovement())
                    )),
                    GravityDebugLog.vec(result.resolvedMovement()),
                    GravityDebugLog.vec(frame.worldToLocal(
                            MinecraftGeometryAdapter.toMinecraft(
                                    result.resolvedMovement())
                    )),
                    GravityDebugLog.vec(result.recoveryMovement()),
                    GravityDebugLog.vec(result.locomotionMovement()),
                    result.blockedDown(),
                    result.blockedUp(),
                    result.blockedTangent(),
                    result.gameplayGrounded(),
                    result.supportBlock().map(Object::toString).orElse("none"),
                    result.stepHeight(),
                    result.indeterminate(),
                    result.indeterminateReason(),
                    result.supportFollowRise(),
                    result.tangentBlockingNormals(),
                    GravityDebugLog.vec(velocity),
                    GravityDebugLog.vec(frame.worldToLocal(velocity)),
                    GravityDebugLog.vec(entity.position()),
                    GravityDebugLog.vec(frame.down())
            );
        }

        return result;
    }

    // ---------------------------------------------------------------
    // Pure geometry helpers (no controller semantics)
    // ---------------------------------------------------------------

    public static boolean noCollision(
            Entity entity,
            CollisionBody body
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(body, "body");

        GravityRuntimeState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();

        CollisionOperationContext operation =
                runtime.collisionOperation();

        CollisionScene scene =
                operation != null
                        ? operation.scene()
                        : MinecraftCollisionSceneCapture
                        .captureAround(
                                entity,
                                body,
                                entity.maxUpStep()
                        );

        return noCollision(
                body,
                scene
        );
    }

    /**
     * Explicit scene-consuming legality predicate.
     *
     * <p>Tests and operation-local callers should prefer this overload. Moving
     * obstacles are tested at the operation-start instant rather than by
     * recapturing live world state.</p>
     */
    public static boolean noCollision(
            CollisionBody body,
            CollisionScene scene
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(scene, "scene");

        return !hasMeaningfulPenetrationAt(
                body,
                scene,
                0.0D,
                new ObbQueryContext()
        );
    }

    /**
     * Pose-fit scene resolution at the physics boundary.
     *
     * <p>Inside an outer gravity operation the operation's frozen
     * {@link CollisionScene} is borrowed; no second capture happens. Outside
     * an operation this is the explicit pose-change transition capture
     * boundary. Consumers (for example
     * {@link cc.sighs.gravityengine.gravity.integration.collision.GravityPlayerPoseFitQuery})
     * may then read only the returned immutable scene.</p>
     */
    public static CollisionScene poseFitScene(
            Entity entity,
            CollisionBody body
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(body, "body");
        GravityRuntimeState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().runtime();
        CollisionOperationContext operation = runtime.collisionOperation();
        if (operation != null) {
            return operation.scene();
        }
        return MinecraftCollisionSceneCapture.captureAround(
                entity, body, entity.maxUpStep()
        );
    }

    /**
     * Operation-scene final-body penetration predicate at an explicit
     * operation-local obstacle time.  Moving obstacles are probed at that
     * same instant; a platform that has not yet arrived (or has already left)
     * cannot own the final body.
     */
    public static boolean hasMeaningfulPenetrationAt(
            CollisionBody body,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");
        CurrentContactQuery.Result current =
                CurrentContactQuery.contacts(
                        body,
                        scene,
                        obstacleTimeTicks,
                        context
                );
        return CurrentContactQuery.hasMeaningfulPenetration(current);
    }

    /** Operation-start overlap check over a caller-provided obstacle list. */
    public static boolean requiresPenetrationRecovery(
            CollisionBody body,
            List<CollisionObstacle> obstacles,
            ObbQueryContext queryContext
    ) {
        for (CollisionObstacle obstacle : obstacles) {
            Optional<CollisionContact> contact = CollisionNarrowPhase.staticContact(
                    body, obstacle, queryContext
            );
            if (contact.isPresent()
                    && contact.get().penetration()
                    > CollisionTolerances.PENETRATION_EPSILON) {
                return true;
            }
        }
        return false;
    }

    /** Exact initial-overlap predicate shared by recovery and tests. */
    public static boolean requiresPenetrationRecovery(
            CollisionBody body,
            List<CollisionObstacle> obstacles
    ) {
        return requiresPenetrationRecovery(
                body, obstacles, new ObbQueryContext()
        );
    }

    public static boolean noCollisionAtPosition(
            Entity entity,
            double x,
            double y,
            double z,
            Vec3 down
    ) {
        return noCollisionAtPosition(
                entity,
                new Vec3(x, y, z),
                GravityFrame.downOnly(down)
        );
    }

    public static boolean noCollisionAtPosition(
            Entity entity,
            Vec3 positionAnchor,
            GravityFrame frame
    ) {
        CollisionBody body = GravityEntityGeometry.candidateBody(
                entity,
                GravityEntityGeometry.dimensions(entity),
                positionAnchor,
                frame
        );
        return noCollision(entity, body);
    }

}
