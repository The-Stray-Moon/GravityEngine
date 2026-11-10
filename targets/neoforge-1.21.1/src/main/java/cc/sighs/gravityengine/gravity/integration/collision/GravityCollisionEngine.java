package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.CollisionOperationContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Thin Minecraft adapter over the pure {@link GravityCharacterRoute}.
 *
 * <p>This adapter only obtains the active frame/scene/body, invokes the pure
 * resolver, and stores the immutable {@link GravityMoveResult} for the current
 * move lifecycle. It owns no support continuity, no detach state, no ground
 * authority, no character routing, no traversal, and no gameplay velocity
 * policy.</p>
 *
 * <p>Every collision predicate that can be expressed over common collision
 * values alone lives in the common collision subsystem
 * ({@link CurrentContactQuery}); this class keeps only the capture and
 * adaptation entry points that genuinely need a Minecraft entity.</p>
 */
public final class GravityCollisionEngine {
    /** World-down retains authored step height regardless of actor attitude.
     * Non-default gravity retains the explicit static-voxel allowance. */
    public static StepUpIntent stepIntent(GravityFrame frame, double authoredHeight) {
        return !BodyRepresentation.requiresReferenceGeometry(frame)
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
        return collide(entity, request, null);
    }

    public static Vec3 collide(Entity entity, KinematicMoveRequest request,
                               SupportTransport transport) {
        GravityOperationState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();

        GravityMoveResult result =
                resolve(entity, request, transport, MovementCollisionDiagnostics.capture(entity));

        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity && result.indeterminate())
            throw new CollisionSceneCoverageException("indeterminate falling block movement");
        runtime.setCurrentMoveResult(result);


        return MinecraftMathAdapter.toMinecraft(
                result.resolvedMovement()
        );
    }

    /**
     * Resolves an environment-owned auxiliary translation against the already
     * frozen operation scene without replacing the ordinary Entity.move result.
     */
    public static Vec3 collideUnpublished(
            Entity entity,
            KinematicMoveRequest request
    ) {
        return MinecraftMathAdapter.toMinecraft(
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
     * installed as {@link GravityOperationState#currentMoveResult()}.</p>
     */
    public static GravityMoveResult resolveUnpublished(
            Entity entity,
            KinematicMoveRequest request
    ) {
        return resolve(entity, request, null, null);
    }

    private static GravityMoveResult resolve(
            Entity entity,
            KinematicMoveRequest request,
            SupportTransport supportTransport,
            MovementCollisionDiagnostics.Span diagnosticSpan
    ) {
        GravityEntityAccess access = GravityEntityAccess.cast(entity);
        GravityOperationState runtime = access.gravityengine$gravityComponent().operationState();
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
                GravityEntityGeometry.body(entity);
        if (diagnosticSpan != null) {
            queryContext.setStepDecision(null);
            queryContext.beginCollisionTrace();
            MovementCollisionDiagnostics.input(entity, diagnosticSpan,
                    body,
                    request, frame, scene, "EXACT_BODY");
        }
        var previousSupport = runtime.restingContactSnapshot();
        queryContext.setPreferredSupportFace(previousSupport != null
                && previousSupport.gameTick() >= scene.time().gameTick() - 1
                ? previousSupport.faceIdentity() : null);


        StepUpIntent stepIntent =
                entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                        || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity
                        ? StepUpIntent.disabled() : stepIntent(
                        frame,
                        entity.maxUpStep()
                );

        GravityMoveResult result =
                supportTransport != null
                        && supportTransport
                        .requiresGeneralSupportSweep()
                        ? SupportedCharacterRoute.resolve(
                                body,
                                request,
                                frame,
                                scene,
                                queryContext,
                                stepIntent,
                                supportTransport
                        )
                        : GravityCharacterRoute.resolve(
                                body,
                                request,
                                frame,
                                scene,
                                queryContext,
                                stepIntent
                        );
        if (diagnosticSpan != null) {
            MovementCollisionDiagnostics.result(entity, diagnosticSpan, result, queryContext);
        }
        if (GravityDebugLog.shouldLog(entity)) {
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
                            result.requestedMovement())),
                    GravityDebugLog.vec(result.resolvedMovement()),
                    GravityDebugLog.vec(frame.worldToLocal(
                            result.resolvedMovement())),
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
                    GravityDebugLog.vec(frame.worldToLocal(
                            MinecraftMathAdapter.toVec3d(velocity))),
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

        GravityOperationState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();

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

        return CurrentContactQuery.isClear(
                body,
                scene
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
        GravityOperationState runtime = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState();
        CollisionOperationContext operation = runtime.collisionOperation();
        if (operation != null) {
            return operation.scene();
        }
        return MinecraftCollisionSceneCapture.captureAround(
                entity, body, entity.maxUpStep()
        );
    }


}
