package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.geometry.*;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensions;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.KinematicPose;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Minecraft capture/commit adapter for the loader-neutral geometry-transition
 * planner.
 *
 * <p>This owner keeps exactly the platform half of a geometry transition:</p>
 *
 * <ul>
 *   <li>entity dimensions, position, live body and world scene capture;</li>
 *   <li>live entity mutation through the exact-body commit;</li>
 *   <li>Minecraft debug identity and the decision log line;</li>
 *   <li>Vanilla body installation/recovery and standalone size-change
 *       legality, which query the live level.</li>
 * </ul>
 *
 * <p>Collision-axis deadband, frame delta, pose legality/fit, support
 * compatibility, bounded support correction, the PRESERVE_SUPPORT candidate and
 * the position-authority decision live in
 * {@link GravityGeometryTransitionPlanner} and are consumed here as an
 * immutable {@link OperationPoseTransition}.</p>
 */
public final class GravityGeometryTransitionService {
    private GravityGeometryTransitionService() {}

    /** Standalone size changes preserve network P, as Vanilla does. Changing
     * h changes C along world Y and is a geometry transaction, never a second
     * movement. Pose-fit keeps ordinary entities strict too; rotation/resize
     * rejects both new and deepened overlap without packet tolerance. */
    public static boolean dimensionsChangeLegal(Entity entity, CollisionBody oldBody,
                                                Vec3d positionAnchor, Vec3d installedUp) {
        var dimensions = GravityEntityGeometry.dimensions(entity);
        if (CharacterDimensionPolicy.decide(dimensions.width(), dimensions.height())
                != CharacterDimensionPolicy.Decision.CAPSULE) return false;
        CollisionBody candidate = GravityEntityGeometry.candidateBody(dimensions, MinecraftMathAdapter.toMinecraft(positionAnchor), installedUp);
        if (candidate.equals(oldBody) || entity.noPhysics) return true;
        try {
            Aabb3d bounds = oldBody.enclosingAabb().union(candidate.enclosingAabb());
            var domain = CollisionCaptureDomain.forTranslation(bounds, Vec3d.ZERO, entity.maxUpStep());
            long tick = entity.level().getGameTime();
            var scene = MinecraftCollisionSceneCapture.capture(entity, domain, tick, 0,
                    KinematicStepContext.fullTick(tick, 0),
                    new CollisionWorkTracker(CollisionWorkBudget.defaults()));
            return BodyCollisionDelta.compare(oldBody, candidate,
                    (body, movement) -> scene.queryPoseFit(body), false).legal();
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Operation-frame preparation
    // -----------------------------------------------------------------

    /**
     * Operation-scene variant with an explicit position-authority contract.
     * The authority is mandatory: an implicit ordinary-movement default here
     * is exactly how a Player packet call site could silently acquire
     * permission to re-anchor the anchor its owner already captured.
     */
    public static OperationFrameResult prepareOperationFrame(
            Entity entity,
            GravityFrame proposedFrame,
            CollisionScene scene,
            PositionAuthorityPolicy positionAuthority
    ) {
        return prepareOperationFrame(
                entity,
                proposedFrame,
                scene,
                positionAuthority,
                new ObbQueryContext()
        );
    }

    /**
     * Captures this operation's Minecraft facts, plans the transition in common
     * and commits at most one planned pose.
     *
     * <p>An externally owned translation proposal must not be re-anchored: the
     * owner already captured the anchor it will translate from and to
     * (Vanilla's {@code handleMovePlayer} pre-move position and last-good
     * baseline are the canonical example), so operation-time re-anchoring
     * would make the proposal start from a different position than the owner
     * assumed. {@link PositionAuthorityPolicy#EXTERNAL_POSITION_ANCHOR} keeps
     * the installed representation when only a support correction could make
     * the proposed axis legal.</p>
     */
    public static OperationFrameResult prepareOperationFrame(
            Entity entity,
            GravityFrame proposedFrame,
            CollisionScene scene,
            PositionAuthorityPolicy positionAuthority,
            ObbQueryContext queryContext
    ) {
        return prepareOperationFrame(entity, proposedFrame, scene, positionAuthority,
                queryContext, GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState().restingContactSnapshot());
    }

    /** Operation-owned support, including a soft-position witness verified before
     * preparation. This does not publish endpoint ground or grant position authority. */
    public static OperationFrameResult prepareOperationFrame(
            Entity entity,
            GravityFrame proposedFrame,
            CollisionScene scene,
            PositionAuthorityPolicy positionAuthority,
            ObbQueryContext queryContext,
            RestingContactSnapshot operationStartSupport
    ) {
        Objects.requireNonNull(positionAuthority, "positionAuthority");
        if (PlayerBodyHandoff.preservesPosition(entity)) {
            positionAuthority = PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR;
        }
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(proposedFrame, "proposedFrame");

        GravityOperationState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();
        GravityFrame installedFrame = installedFallback(runtime);
        GravityEntityGeometry.requireInstalledGeometry(entity, "operation-frame preparation");
        EntityDimensions dimensions = GravityEntityGeometry.dimensions(entity);
        CollisionBody installedBody =
                GravityEntityGeometry.body(entity);
        Vec3d installedAnchor =
                MinecraftMathAdapter.toVec3d(entity.position());

        GeometryTransitionRequest request =
                new GeometryTransitionRequest(
                        installedFrame,
                        installedBody,
                        installedAnchor,
                        proposedFrame,
                        new CharacterDimensions(
                                dimensions.width(),
                                dimensions.height()
                        ),
                        scene,
                        queryContext,
                        positionAuthority,
                        operationStartSupport,
                        entity.level().getGameTime()
                );

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(request);

        logGeometryTransitionDecision(
                entity,
                installedFrame,
                proposedFrame,
                GravityGeometryTransitionPlanner
                        .exactFrameAngularDistanceRadians(
                                installedFrame,
                                proposedFrame
                        ),
                transition.kind() == GeometryTransitionKind.UNCHANGED,
                positionAuthority,
                transition
        );

        return commitOperationPoseTransition(
                entity,
                installedAnchor,
                transition
        );
    }

    /**
     * Commit phase: install at most one planned pose.
     *
     * <p>A failed or unplanned candidate never reaches this method, so no
     * half-updated geometry or runtime state can exist.</p>
     */
    static OperationFrameResult commitOperationPoseTransition(
            Entity entity,
            Vec3d installedAnchor,
            OperationPoseTransition transition
    ) {
        Objects.requireNonNull(transition, "transition");
        if (!transition.installsPose()) {
            return transition.toFrameResult();
        }
        KinematicPose proposedPose = transition.candidatePose();
        GravityFrame proposedFrame = proposedPose.frame();
        Vec3d positionCorrection =
                proposedPose.positionAnchor().subtract(installedAnchor);
        if (transition.kind() == GeometryTransitionKind.DIRECT_AT_ANCHOR
                && positionCorrection.lengthSquared()
                > GravityEntityGeometry.GEOMETRY_FRAME_EPSILON_SQUARED) {
            throw new IllegalStateException(
                    "direct frame handoff must not translate the "
                            + "authoritative position anchor: installed="
                            + installedAnchor + ", proposed="
                            + proposedPose.positionAnchor()
                            + ", frame=" + proposedFrame
            );
        }
        GravityEntityGeometry.commitCustomBody(
                entity, proposedFrame.up(),
                MinecraftMathAdapter.toMinecraft(proposedPose.positionAnchor()), proposedPose.body());
        if (transition.kind() == GeometryTransitionKind.PRESERVE_SUPPORT) {
            if (GravityDebugLog.shouldLogMovement(entity)) GravityDebugLog.movement(entity, "SUPPORT", "anchor",
                    "up=%s positionCorrection=%s physicalSupport=true "
                            + "stableGround=true footEligible=true "
                            + "tractionEligible=true transitionKind=%s",
                    GravityDebugLog.exactVec(proposedFrame.up()),
                    GravityDebugLog.vec(positionCorrection),
                    transition.kind());
        }
        return new OperationFrameResult(
                proposedFrame,
                GeometryTransitionStatus.APPLIED,
                transition.geometryChanged(),
                positionCorrection,
                transition.kind()
        );
    }

    /**
     * One debug line for every operation-pose decision, with enough evidence
     * to reconstruct which candidate won and which layer rejected the others.
     *
     * <p>The line adds no collision or support queries: every field is a value
     * the planner already produced. Deadband ticks keep their historical
     * quiet path.</p>
     */
    private static void logGeometryTransitionDecision(
            Entity entity,
            GravityFrame installedFrame,
            GravityFrame proposedFrame,
            double exactFrameDeltaRadians,
            boolean deadbandHit,
            PositionAuthorityPolicy positionAuthority,
            OperationPoseTransition transition
    ) {
        if (!GravityDebugLog.shouldLog(entity) || deadbandHit) {
            return;
        }
        OperationFrameDiagnostics diagnostics = transition.diagnostics();
        GravityDebugLog.log(entity,
                "geometry-frame",
                "decision=%s installedDown=%s proposedDown=%s "
                        + "exactFrameDeltaDeg=%.6f deadbandHit=%s "
                        + "positionAuthority=%s "
                        + "positionAuthorityAllowed=%s "
                        + "directFit=%s directRejectReason=%s "
                        + "supportCandidateConstructed=%s "
                        + "supportFit=%s "
                        + "supportRejectReason=%s "
                        + "requiredSupportCorrection=%s "
                        + "maxSupportCorrection=%.9f "
                        + "selectedTransitionKind=%s selectedDown=%s "
                        + "positionCorrection=%s geometryChanged=%s "
                        + "finalStatus=%s",
                transition.kind(),
                GravityDebugLog.vec(installedFrame.down()),
                GravityDebugLog.vec(proposedFrame.down()),
                Math.toDegrees(exactFrameDeltaRadians),
                deadbandHit,
                positionAuthority,
                diagnostics.positionAuthorityAllowed(),
                diagnostics.directFit(),
                diagnostics.directRejectReason() == null
                        ? "none"
                        : diagnostics.directRejectReason(),
                diagnostics.supportCandidateConstructed(),
                diagnostics.supportFit(),
                diagnostics.supportRejectReason() == null
                        ? "none"
                        : diagnostics.supportRejectReason(),
                diagnostics.requiredCorrection(),
                diagnostics.maxCorrection(),
                transition.kind(),
                GravityDebugLog.vec(transition.selectedFrame().down()),
                GravityDebugLog.vec(transition.positionCorrection()),
                transition.geometryChanged(),
                transition.kind() == GeometryTransitionKind.DEFERRED
                        ? GeometryTransitionStatus.DEFERRED
                        : GeometryTransitionStatus.APPLIED
        );
    }

    public static GravityFrame installedFallback(
            GravityOperationState runtime
    ) {
        GravityFrame installed = runtime.geometryReferenceFrame();
        if (installed == null) {
            throw new IllegalStateException(
                    "custom operation has no installed collision axis"
            );
        }
        return installed;
    }

    // -----------------------------------------------------------------
    // Vanilla transition (no inflated box)
    // -----------------------------------------------------------------

    public static GeometryTransitionResult installVanilla(Entity entity) {
        if (!PlayerBodyHandoff.mayChangeBody(entity)) return GeometryTransitionResult.DEFERRED;
        Vec3 positionAnchorMc = entity.position();
        Vec3d positionAnchor =
                MinecraftMathAdapter.toVec3d(positionAnchorMc);
        Vec3 velocityMc = entity.getDeltaMovement();
        Vec3d velocity = MinecraftMathAdapter.toVec3d(velocityMc);
        EntityDimensions dims = GravityEntityGeometry.dimensions(entity);
        AABB candidate = dims.makeBoundingBox(positionAnchorMc);

        if (entity.level().noCollision(entity, candidate)) {
            GravityEntityGeometry.commitVanillaBody(
                    entity, positionAnchorMc, candidate);
            entity.setDeltaMovement(velocityMc);
            entity.fallDistance = 0f;
            entity.setOnGround(false);
            return GeometryTransitionResult.applied();
        }

        if (PlayerBodyHandoff.preservesPosition(entity)) return GeometryTransitionResult.DEFERRED;

        // Lifecycle/non-player placement may attempt bounded recovery.
        GeometryTransitionResult recovered = attemptVanillaRecovery(entity, dims, positionAnchor, velocity);
        if (recovered != null) {
            entity.setDeltaMovement(velocityMc);
            return recovered;
        }

        // A proxy enclosure cannot become an exact vanilla collider. Keep the
        // accepted body, support and velocity until a legal handoff succeeds.
        return GeometryTransitionResult.DEFERRED;
    }

    private static GeometryTransitionResult attemptVanillaRecovery(
            Entity entity,
            EntityDimensions dims,
            Vec3d positionAnchor,
            Vec3d velocity
    ) {
        double maxStep = entity.maxUpStep();
        if (maxStep <= 1.0E-6D) return null;
        double stepSize = Math.max(maxStep * 0.5, 0.0625);
        int maxSteps = Math.min((int) Math.ceil(maxStep / stepSize) + 2, 20);
        double halfWidth = dims.width() * 0.5;
        double height = dims.height();

        for (int i = 1; i <= maxSteps; i++) {
            double offset = stepSize * i;
            if (offset > maxStep + 0.5) break;
            Vec3d tryPositionAnchor = positionAnchor.add(0, offset, 0);
            AABB tryBox = new AABB(
                    tryPositionAnchor.x() - halfWidth, tryPositionAnchor.y(), tryPositionAnchor.z() - halfWidth,
                    tryPositionAnchor.x() + halfWidth, tryPositionAnchor.y() + height, tryPositionAnchor.z() + halfWidth);
            if (entity.level().noCollision(entity, tryBox)) {
                GravityEntityGeometry.commitVanillaBody(
                        entity,
                        MinecraftMathAdapter.toMinecraft(
                                tryPositionAnchor),
                        tryBox);
                entity.fallDistance = 0f;
                entity.setOnGround(false);
                return GeometryTransitionResult.recovered(new Vec3d(0, offset, 0));
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Custom body installation
    // -----------------------------------------------------------------

    public static GeometryTransitionResult installCustomFromPositionAnchor(Entity entity, GravityState state) {
        if (!PlayerBodyHandoff.mayChangeBody(entity)) return GeometryTransitionResult.DEFERRED;
        // A body-only plan (e.g. Elytra) is valid under ordinary world gravity too.
        EntityDimensions dims = GravityEntityGeometry.dimensions(entity);
        if (CharacterDimensionPolicy.decide(dims.width(), dims.height())
                != CharacterDimensionPolicy.Decision.CAPSULE) return GeometryTransitionResult.failed();
        Vec3 positionAnchorMc = entity.position();
        Vec3d positionAnchor =
                MinecraftMathAdapter.toVec3d(positionAnchorMc);
        Vec3 velocityMc = entity.getDeltaMovement();
        GravityFrame frame = GravityEntityGeometry.frameAtPositionAnchor(
                entity, state, positionAnchorMc);
        CollisionBody body = GravityEntityGeometry.candidateBody(dims, positionAnchorMc, frame.up());

        CollisionScene scene = MinecraftCollisionSceneCapture.captureAround(
                entity, body, entity.maxUpStep());
        boolean initiallyLegal =
                !CurrentContactQuery.requiresPenetrationRecovery(
                                body,
                                scene.query(body, Vec3d.ZERO));
        if (!initiallyLegal) {
            if (PlayerBodyHandoff.preservesPosition(entity)) return GeometryTransitionResult.DEFERRED;
            CollisionObstacleQuery obstacleQuery =
                    (queryBody, movement) -> scene.query(
                            queryBody, movement);
            CollisionRecovery.RecoveryResult recovery;
            try {
                recovery = CollisionRecovery.recover(
                        body,
                        obstacleQuery,
                        new ObbQueryContext());
            } catch (CollisionComplexityLimitException limit) {
                // Work-budget exhaustion in transition recovery is a failed
                // transition, never a fallback that pretends recovery happened.
                return GeometryTransitionResult.failed();
            }

            if (recovery.recovered()) {
                Vec3d recoveryVec = recovery.recoveryMovement();
                double maxDisplacement = entity.getBbWidth() + entity.maxUpStep() + 0.5;
                if (recoveryVec.length() > maxDisplacement
                        || !Double.isFinite(recoveryVec.lengthSquared())) {
                    return GeometryTransitionResult.failed();
                }
                Vec3d newPositionAnchor = positionAnchor.add(recoveryVec);
                GravityEntityGeometry.commitCustomBody(entity, frame.up(), MinecraftMathAdapter.toMinecraft(
                                newPositionAnchor), recovery.recoveredBody());
                entity.setDeltaMovement(velocityMc);
                return GeometryTransitionResult.recovered(recoveryVec);
            } else {
                return GeometryTransitionResult.failed();
            }
        }

        GravityEntityGeometry.commitCustomBody(entity, frame.up(), MinecraftMathAdapter.toMinecraft(positionAnchor), body);
        entity.setDeltaMovement(velocityMc);
        return GeometryTransitionResult.applied();
    }
}
