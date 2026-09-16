package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import java.util.Optional;

import static cc.sighs.gravityengine.gravity.collision.MovementIndeterminateReason.*;

/**
 * Gravity-relative continuous movement resolver.
 *
 * <p>The ordinary route remains vertical-first and tangent-second. It is
 * solved completely before the optional step candidate and retains the strict
 * direction-sensitive anti-lift invariant. A step is the sole explicit
 * exception: an operation-local rise, traverse, and settle route solved from
 * the authoritative operation-start body through the same exact CCD geometry.</p>
 *
 * <p>The already-authoritative operation-start body is the immutable contact
 * baseline. Existing meaningful penetration allows tangent/outward escape
 * without automatic recovery, but inward motion cannot deepen it. A merely touching
 * contact still clips inward movement normally, and collision with an obstacle
 * absent from the starting body remains illegal. Explicit discontinuity, pose,
 * suffocation and world-change owners retain their separate recovery policy.</p>
 *
 * <p>The resolver has no {@code Entity}/{@code Level}, performs no position,
 * velocity, or ground mutation, and retains no state after this invocation.</p>
 */
public final class GravityCharacterRoute {
    private static final int MAX_BUMP = 6;
    private static final double BLOCK_EPSILON = 1.0E-7D;
    private static final double BLOCK_EPSILON_SQUARED =
            BLOCK_EPSILON * BLOCK_EPSILON;
    private static final double TOI_EPSILON = CollisionTolerances.TOI_EPSILON;
    private static final double FRESH_LANDING_RETENTION_DISTANCE =
            GravityGroundProbe.PROBE_DISTANCE * 8.0D;

    /** Minimum authored height that enables a step candidate. */
    static final double STEP_INTENT_EPSILON = 1.0E-7D;
    /** Minimum signed progress gain along the original tangent request. */
    static final double STEP_PROGRESS_EPSILON = 1.0E-7D;
    /** Numerical allowance for the accepted route's final gravity-up height. */
    static final double STEP_HEIGHT_EPSILON = 1.0E-7D;
    /** Projection change below this magnitude is treated as solver stagnation. */
    private static final double NO_PROGRESS_EPSILON = 1.0E-12D;
    private static final double NO_PROGRESS_EPSILON_SQUARED =
            NO_PROGRESS_EPSILON * NO_PROGRESS_EPSILON;
    /** Numerical allowance added to the walkability-derived support-follow bound. */
    private static final double SUPPORT_RISE_ALLOWANCE = 1.0E-6D;
    /**
     * Up-dot band above which a plane is treated as pure gravity-aligned.
     * Accounts for the float-constructed {@code GravityFrame} basis error
     * (~1e-7 per component): a "parallel" contact normal from such a frame
     * must not produce a garbage near-zero tangent plane.
     */

    private GravityCharacterRoute() {}

    /** Production entry: ownership is supplied by the actual-collide adapter. */
    public static GravityMoveResult resolve(
            CollisionBody body, cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest request,
            GravityFrame frame, CollisionScene scene, ObbQueryContext context, StepUpIntent step) {
        return resolveOwned(body, MinecraftGeometryAdapter.toJoml(request.actualMovement(), new Vector3d()),
                frame, scene, context, step, request.ownership(), request.channel());
    }

    /** Ordinary-route overload without an authored step intent. */
    public static GravityMoveResult resolve(
            CollisionBody startBody,
            Vector3d requestedMovement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context
    ) {
        return resolve(
                startBody,
                requestedMovement,
                frame,
                scene,
                context,
                StepUpIntent.disabled()
        );
    }

    /** Resolves one requested movement, including at most one step candidate. */
    public static GravityMoveResult resolve(
            CollisionBody startBody,
            Vector3d requestedMovement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            StepUpIntent stepIntent
    ) {
        // Neutral geometry/route entry. Gameplay production must use the
        // immutable request overload above.
        return resolveOwned(startBody, requestedMovement, frame, scene, context, stepIntent, null,
                KinematicMoveRequest.Channel.SELF);
    }

    private static GravityMoveResult resolveOwned(
            CollisionBody startBody, Vector3d requestedMovement, GravityFrame frame,
            CollisionScene scene, ObbQueryContext context, StepUpIntent stepIntent,
            OwnedMotion ownership, KinematicMoveRequest.Channel channel
    ) {
        BodyCollisionDelta.Baseline contactBaseline;
        try {
            contactBaseline = new BodyCollisionDelta.Baseline(
                    startBody, scene, 0.0D, context);
        } catch (CollisionComplexityLimitException exception) {
            return degraded(requestedMovement, frame, INITIAL_BODY_QUERY_BUDGET);
        }
        if (workLimitExceeded(scene, context)) {
            return degraded(requestedMovement, frame, INITIAL_BODY_QUERY_BUDGET);
        }

        var initial = ownership != null || stepIntent.terrainRiseBudget() > STEP_INTENT_EPSILON
                ? GravityGroundProbe.probe(startBody, frame, scene, 0, context)
                : GravityGroundProbe.Result.AIRBORNE;
        Vector3d effective = new Vector3d(requestedMovement);
        // Only local SELF integration creates new traction/transport. A packet
        // already contains the client's displacement; external movers likewise
        // supply a complete proposal. Never add another interval of carry there.
        if (channel == KinematicMoveRequest.Channel.SELF
                && !initial.indeterminate() && initial.tractionEligible() && ownership != null
                && requestedMovement.dot(up(frame)) <= BLOCK_EPSILON) {
            var filtered = GroundTractionPolicy.supported(ownership, initial.supportContact().orElseThrow(),
                    scene.time().intervalTicks());
            effective.add(MinecraftGeometryAdapter.toJoml(filtered.total().subtract(ownership.total()), new Vector3d()));
            ownership = filtered;
        }
        UnsnappedResolution unsnapped = resolveUnsnapped(
                startBody,
                effective,
                frame,
                scene,
                context,
                stepIntent,
                ownership,
                contactBaseline,
                initial
        );

        GravityMoveResult resolved = unsnapped.move();

        /*
         * Diagnostics/native requested-versus-resolved semantics retain the
         * actual request even when traction absorbed an identified increment.
         */
        if (!effective.equals(requestedMovement)) {
            resolved = new GravityMoveResult(
                    requestedMovement,
                    resolved.resolvedMovement(),
                    resolved.recoveryMovement(),
                    resolved.locomotionMovement(),
                    resolved.blockedDown(),
                    resolved.blockedUp(),
                    resolved.blockedTangent(),
                    resolved.supportingContactDuringMove(),
                    resolved.movementSupportContact(),
                    resolved.terminalGrounded(),
                    resolved.walkableGround(),
                    resolved.supportBlock(),
                    resolved.stepHeight(),
                    resolved.indeterminate(),
                    resolved.tangentBlockingNormals(),
                    resolved.supportFollowRise(),
                    frame,
                    resolved.supportContact(),
                    resolved.contactVelocityConstraints(),
                    resolved.indeterminateReason()
            );
        }

        return snapToSelectedFace(
                startBody,
                resolved,
                requestedMovement,
                frame,
                scene,
                context,
                initial,
                unsnapped.freshLandingFace(),
                stepIntent.maxStepHeight()
        );
    }

    private static UnsnappedResolution resolveUnsnapped(
            CollisionBody startBody, Vector3d requestedMovement, GravityFrame frame,
            CollisionScene scene, ObbQueryContext context, StepUpIntent stepIntent,
            OwnedMotion ownership, BodyCollisionDelta.Baseline contactBaseline, GravityGroundProbe.Result initialSupport
    ) {
        Objects.requireNonNull(startBody, "startBody");
        Objects.requireNonNull(requestedMovement, "requestedMovement");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(stepIntent, "stepIntent");

        /*
         * One movement operation owns one obstacle-time interval. The
         * operation window is created before the start-state baseline capture
         * so every later route query can name its explicit
         * operation-local instant instead of silently re-using the
         * full-interval two-argument scene overload.
         */
        SweepTimeWindow operationWindow =
                SweepTimeWindow.full(scene.time());

        CollisionBody movementStartBody = startBody;

        Vector3d effectiveMovement =
                new Vector3d(requestedMovement);

        boolean initialStableSupport = false;
        MovementIndeterminateReason initialSupportFailure = NONE;

        if (ownership != null
                || stepIntent.terrainRiseBudget()
                > STEP_INTENT_EPSILON) {

            if (initialSupport.indeterminate()) {
                /*
                 * Initial support is a locomotion-policy input, not a
                 * translation authority. Its uncertainty may disable the
                 * optional step candidate, but it must not erase movement that
                 * the ordinary collision route can still prove.
                 */
                initialSupportFailure = queryFailure(
                        scene,
                        context,
                        INITIAL_SUPPORT_QUERY_INDETERMINATE,
                        INITIAL_SUPPORT_QUERY_BUDGET
                );
            } else {
                initialStableSupport =
                        initialSupport.stableGround();
            }
        }

        Vector3d up =
                frame.orientation()
                        .axisY(new Vector3d());

        Vec3 worldUp =
                MinecraftGeometryAdapter.toMinecraft(up);

        /*
         * Split known delta evidence at the same route boundary as geometry.
         *
         * The vertical and tangent phases now own corresponding decomposition
         * evidence rather than repeatedly consulting the original combined
         * request after one phase has already selected its response.
         */
        OwnedMotion verticalOwnership =
                ownership == null
                        ? null
                        : ownership.projectOnto(worldUp);

        OwnedMotion tangentOwnership =
                ownership == null
                        ? null
                        : ownership.rejectFrom(worldUp);

        double requestedUp =
                effectiveMovement.dot(up);

        Vector3d verticalRequested =
                new Vector3d(up)
                        .mul(requestedUp);

        Vector3d tangentRequested =
                new Vector3d(effectiveMovement)
                        .sub(verticalRequested);

        RouteOutcome baseline =
                solveOrdinaryRoute(
                        movementStartBody,
                        verticalRequested,
                        tangentRequested,
                        requestedUp,
                        frame,
                        scene,
                        context,
                        operationWindow,
                        verticalOwnership,
                        tangentOwnership,
                        contactBaseline
                );

        // A placement overlap is authoritative, but neither a later leg nor
        // a moving surface may turn it into permission for greater depth.
        if (!baseline.indeterminate() && contactBaseline.hasMeaningfulOverlap()) {
            try {
                if (!contactBaseline.compareAt(movementStartBody.move(baseline.applied()), operationWindow.endTicks()).legal()) {
                    /*
                     * A placement overlap may not be deepened. Stop at the
                     * already-authoritative start body instead of rewinding
                     * past motion that was never validated against the
                     * overlap constraint.
                     */
                    return new UnsnappedResolution(
                            degraded(
                                    requestedMovement,
                                    baseline.applied(),
                                    frame,
                                    FINAL_CONTACT_PENETRATION
                            ),
                            Optional.empty()
                    );
                }
            } catch (CollisionComplexityLimitException exception) {
                return new UnsnappedResolution(
                        degraded(
                                requestedMovement,
                                baseline.applied(),
                                frame,
                                FINAL_CONTACT_QUERY_BUDGET
                        ),
                        Optional.empty()
                );
            }
        }

        enforceOrdinaryAntiLift(
                requestedMovement,
                baseline.applied(),
                requestedUp,
                baseline.supportFollowRise(),
                frame
        );

        /*
         * The step alternative consumes the same actual tangent request as
         * the ordinary route. Evidence describes only known new deltas.
         *
         * From this point onward the step alternative must compare itself against
         * exactly the tangent request that the ordinary route actually attempted,
         * with no reconstruction from the evidence sum.
         */
        Vector3d stepTangentRequested =
                baseline.tangentRequest();

        OwnedMotion stepTangentOwnership =
                baseline.tangentRequestOwnership();

        Vector3d tangentDirection =
                stepTangentRequested.lengthSquared()
                        > STEP_INTENT_EPSILON
                        * STEP_INTENT_EPSILON
                        ? new Vector3d(
                        stepTangentRequested
                ).normalize()
                        : new Vector3d();

        double baselineProgress =
                baseline.applied().dot(
                        tangentDirection
                );

        /*
         * Terrain traversal layers:
         *
         * 1. The ordinary route resolved hard contact against the frozen frame.
         * 2. A blocked baseline below only means the continuous fast path
         *    declined (steep but finite feature) or a true wall stopped the
         *    route. Both are ordinary locomotion outcomes, never solver
         *    indeterminate.
         * 3. The bounded rise -> traverse -> settle candidate below may then
         *    traverse finite static voxel terrain up to the operation-local
         *    terrain budget, bounded by the authored step height in every frame.
         */
        double authoredStepHeight =
                Math.max(
                        0.0D,
                        stepIntent.maxStepHeight()
                );

        double terrainRiseBudget = Math.min(stepIntent.terrainRiseBudget(), authoredStepHeight);

        boolean voxelTerrainEvidence =
                TerrainTraversalPolicy.exclusivelyStaticVoxelBlockers(
                        baseline.tangentBlockingContacts()
                );
        logContinuousDecision(
                baseline,
                stepIntent,
                frame,
                terrainRiseBudget,
                voxelTerrainEvidence
        );

        String ineligible =
                stepIneligibility(
                        stepIntent,
                        stepTangentRequested,
                        requestedUp,
                        baseline,
                        initialStableSupport,
                        scene,
                        context,
                        stepTangentOwnership,
                        initialSupportFailure
                );
        if (ineligible != null) {
            logStepDecision(context,
                    false, false, 0.0D, baselineProgress, Double.NaN,
                    ineligible
            );
            return resolvedResult(
                    requestedMovement,
                    baseline,
                    0.0D,
                    frame,
                    initialSupportFailure
            );
        }

        StepAttempt attempt =
                solveTraversalCandidate(
                        movementStartBody,
                        stepTangentRequested,
                        stepTangentOwnership,
                        requestedUp,
                        tangentDirection,
                        authoredStepHeight,
                        terrainRiseBudget,
                        voxelTerrainEvidence,
                        frame,
                        scene,
                        context,
                        operationWindow,
                        contactBaseline
                );
        if (attempt.rejectionReason() != null) {
            logStepDecision(context,
                    true,
                    false,
                    0.0D,
                    baselineProgress,
                    attempt.progress(),
                    attempt.rejectionReason()
            );
            logTraversalDecision(
                    "TERRAIN_TRAVERSAL_REJECTED",
                    attempt,
                    baselineProgress,
                    terrainRiseBudget,
                    baseline,
                    frame
            );
            return resolvedResult(
                    requestedMovement,
                    baseline,
                    0.0D,
                    frame,
                    initialSupportFailure
            );
        }

        RouteOutcome candidate = attempt.route();
        double discreteStepHeight = attempt.discreteStepHeight();

        String comparisonRejection = null;
        if (attempt.progress() < 0.0D) {
            comparisonRejection = "NEGATIVE_DIRECTIONAL_PROGRESS";
        } else if (!improvesDirectionalProgress(
                baselineProgress, attempt.progress()
        )) {
            comparisonRejection = "NO_DIRECTIONAL_IMPROVEMENT";
        } else if (discreteStepHeight <= STEP_HEIGHT_EPSILON) {
            comparisonRejection = "NO_NET_STEP_HEIGHT";
        }
        if (comparisonRejection != null) {
            logStepDecision(context,
                    true,
                    false,
                    0.0D,
                    baselineProgress,
                    attempt.progress(),
                    comparisonRejection
            );
            logTraversalDecision(
                    "TERRAIN_TRAVERSAL_REJECTED",
                    attempt,
                    baselineProgress,
                    terrainRiseBudget,
                    baseline,
                    frame
            );
            return resolvedResult(
                    requestedMovement,
                    baseline,
                    0.0D,
                    frame,
                    initialSupportFailure
            );
        }

        logStepDecision(context,
                true,
                true,
                discreteStepHeight,
                baselineProgress,
                attempt.progress(),
                "NONE"
        );
        logTraversalDecision(
                "TERRAIN_TRAVERSAL_ACCEPTED",
                attempt,
                baselineProgress,
                terrainRiseBudget,
                baseline,
                frame
        );
        return resolvedResult(
                requestedMovement,
                candidate,
                discreteStepHeight,
                frame,
                initialSupportFailure
        );
    }

    /** Stick-to-floor is an explicit translation, enabled only after real tangent
     * travel from a selected support. It never repairs a static rest or a jump.
     * The locator chooses a real plane; OBB CCD still vetoes the entire path. */
    /**
     * Stick-to-floor / fresh-landing retention is an explicit translation.
     *
     * <p>Two distinct authorities exist:
     *
     * <ul>
     *     <li>START_SUPPORTED: movement began on a real selected support and may
     *     perform the normal bounded floor-follow operation.</li>
     *
     *     <li>FRESH_LANDING: the vertical leg of this same movement operation
     *     established a real walkable face.  It may retain only that exact face
     *     and only inside a very small numerical separation band.</li>
     * </ul>
     *
     * <p>FRESH_LANDING is strictly operation-local.  It cannot become persistent
     * grounding authority and cannot acquire a different surface after walking
     * over a finite edge.</p>
     */
    private static GravityMoveResult snapToSelectedFace(
            CollisionBody start,
            GravityMoveResult move,
            Vector3d requested,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            GravityGroundProbe.Result initial,
            Optional<SupportFaceIdentity> freshLandingFace,
            double maxDistance
    ) {
        boolean startSupported =
                initial.walkableGround();

        boolean freshLanding =
                !startSupported
                        && move.supportingContactDuringMove()
                        && freshLandingFace.isPresent();

        if (move.indeterminate()
                || move.terminalGrounded()
                || (!startSupported && !freshLanding)
                || requested.dot(up(frame)) > BLOCK_EPSILON
                || frameTangent(
                move.locomotionMovement(),
                frame
        ).lengthSquared() <= BLOCK_EPSILON_SQUARED) {
            return move;
        }

        final SupportFaceIdentity preferredFace;
        final double snapDistance;

        if (freshLanding) {
            /*
             * A landing that happened inside this movement is allowed only a tiny
             * retention correction.  This is not ordinary step-down.
             */
            preferredFace =
                    freshLandingFace.orElseThrow();

            snapDistance =
                    FRESH_LANDING_RETENTION_DISTANCE;
        } else {
            if (maxDistance <= 0.0D) {
                return move;
            }

            preferredFace =
                    initial.supportContact()
                            .orElseThrow()
                            .faceIdentity();

            snapDistance =
                    maxDistance;
        }

        CollisionBody endpoint =
                start.move(move.resolvedMovement());

        var candidates =
                FeetSupportQuery.query(
                        endpoint,
                        frame,
                        scene,
                        scene.time().intervalTicks(),
                        snapDistance,
                        preferredFace,
                        context
                );

        if (candidates.indeterminate()
                || candidates.candidates().isEmpty()) {
            return move;
        }

        var baseline =
                new BodyCollisionDelta.Baseline(
                        endpoint,
                        scene,
                        scene.time().intervalTicks(),
                        context
                );

        /*
         * Every alternative begins at the same terminal body.  Rejected attempts
         * never leave a partially committed snap behind.
         */
        for (var face : candidates.candidates()) {
            if (face.upDot()
                    < TerrainTraversalPolicy
                    .MIN_CONTINUOUS_SUPPORT_UP_DOT) {
                continue;
            }

            /*
             * Ordinary floor-follow may transition to another legal face.
             *
             * Fresh landing may not.  It is allowed to repair only the exact face
             * proven by the vertical landing leg.
             */
            if (freshLanding
                    && !preferredFace.equals(face.identity())) {
                continue;
            }

            /*
             * Preserve real micrometre-scale positive separation.
             *
             * FeetSupportQuery previously rounded these values to zero.  Zero or
             * negative distance requires no translation.
             */
            if (!(face.distance() > 0.0D)) {
                continue;
            }

            /*
             * Capsule support probing contains one probe-sized geometric allowance.
             * Do not let that turn into an unbounded retention distance.
             */
            if (face.distance()
                    > snapDistance
                    + GravityGroundProbe.PROBE_DISTANCE) {
                continue;
            }

            Vector3d snap =
                    up(frame).mul(-face.distance());

            var swept =
                    sweepAxis(
                            endpoint,
                            snap,
                            frame,
                            scene,
                            context,
                            new SweepTimeWindow(
                                    scene.time().intervalTicks(),
                                    0.0D
                            ),
                            baseline
                    );

            if (swept.indeterminate()) {
                return move;
            }

            if (swept.applied().distanceSquared(snap)
                    > BLOCK_EPSILON_SQUARED) {
                continue;
            }

            /*
             * Rebuild terminal support from the actual corrected body.  Historical
             * vertical contact is never published directly as terminal support.
             */
            var support =
                    GravityGroundProbe.probe(
                            swept.body(),
                            frame,
                            scene,
                            scene.time().intervalTicks(),
                            context
                    );

            if (support.indeterminate()) {
                return move;
            }

            if (!support.walkableGround()) {
                continue;
            }

            /*
             * FRESH_LANDING must still resolve onto the same physical finite face
             * after the correction.  Otherwise we really did leave the surface.
             */
            if (freshLanding) {
                SupportFaceIdentity finalFace =
                        support.supportContact()
                                .map(GravitySupportContact::faceIdentity)
                                .orElse(null);

                if (!preferredFace.equals(finalFace)) {
                    continue;
                }
            }

            var authority =
                    buildFinalCharacterVelocityAuthority(
                            swept.body(),
                            support,
                            frame,
                            scene,
                            context,
                            scene.time().intervalTicks(),
                            requested
                    );

            if (authority.indeterminate()) {
                return move;
            }

            return new GravityMoveResult(
                    requested,

                    /*
                     * The actual entity translation includes the tiny retention
                     * correction.
                     */
                    new Vector3d(
                            move.resolvedMovement()
                    ).add(snap),

                    /*
                     * Floor retention remains recovery/support-follow translation,
                     * not locomotion intent.
                     */
                    new Vector3d(
                            move.recoveryMovement()
                    ).add(snap),

                    move.locomotionMovement(),

                    move.blockedDown(),
                    move.blockedUp(),
                    move.blockedTangent(),

                    move.supportingContactDuringMove(),
                    move.movementSupportContact(),

                    true,
                    true,

                    Optional.ofNullable(
                            support.supportBlock()
                    ),

                    move.stepHeight(),
                    false,

                    move.tangentBlockingNormals(),
                    move.supportFollowRise(),

                    frame,

                    support.supportContact(),
                    authority.constraints(),

                    NONE
            );
        }

        return move;
    }

    private static Vector3d frameTangent(Vector3d vector, GravityFrame frame) {
        Vector3d up = up(frame);
        return new Vector3d(vector).fma(-vector.dot(up), up);
    }

    /** Ordinary vertical-first then tangent route. */
    private static RouteOutcome solveOrdinaryRoute(
            CollisionBody startBody,
            Vector3d verticalRequested,
            Vector3d tangentRequested,
            double requestedUp,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            SweepTimeWindow operationWindow,
            OwnedMotion verticalOwnership,
            OwnedMotion tangentOwnership,
            BodyCollisionDelta.Baseline contactBaseline
    ) {
        /*
         * One operation owns one monotonic obstacle-time path. The ordinary
         * vertical and tangent legs receive contiguous sub-windows proportional
         * to their requested path lengths.
         */
        SweepTimeWindow verticalWindow = operationWindow;
        SweepTimeWindow tangentWindow = operationWindow;

        double verticalLength = verticalRequested.length();
        double tangentLength = tangentRequested.length();
        double totalLength = verticalLength + tangentLength;

        if (totalLength > 0.0D) {
            double verticalFraction = verticalLength / totalLength;

            verticalWindow = operationWindow.segment(
                    0.0D,
                    verticalFraction
            );
            tangentWindow = operationWindow.segment(
                    verticalFraction,
                    1.0D
            );
        }

        context.collisionTracePhase("ORDINARY_VERTICAL");
        SweepOutcome vertical =
                sweepVertical(
                        startBody,
                        verticalRequested,
                        frame,
                        scene,
                        context,
                        verticalWindow,
                        contactBaseline
                );

        boolean verticalBlockedDown = requestedUp < -BLOCK_EPSILON
                && vertical.applied().dot(up(frame)) > requestedUp + BLOCK_EPSILON;
        GravityGroundProbe.Result rawMovementSupport =
                !vertical.indeterminate()
                        && verticalBlockedDown
                        ? GravityGroundProbe.probe(
                        vertical.body(),
                        frame,
                        scene,
                        verticalWindow.endTicks(),
                        context)
                        : GravityGroundProbe.Result.AIRBORNE;

        /*
         * Support is secondary derived evidence. If its independent bounded query
         * cannot classify this endpoint, fail closed only for support facts. The hard
         * vertical translation remains authoritative.
         */
        GravityGroundProbe.Result movementSupport =
                rawMovementSupport.indeterminate()
                        ? GravityGroundProbe.Result.AIRBORNE
                        : rawMovementSupport;

        final Vector3d tangentRequestForSweep =
                new Vector3d(tangentRequested);

        OwnedMotion tangentRequestOwnership = tangentOwnership;
        if (movementSupport.tractionEligible() && tangentOwnership != null) {
            // The down leg has now proved hard blocking plus stable foot support.
            // Absorb only this operation's new passive tangent increment. Persistent
            // inertia/self input/external pushes are not traction-owned deltas.
            tangentRequestForSweep.sub(MinecraftGeometryAdapter.toJoml(tangentOwnership.passive(), new Vector3d()));
            tangentRequestOwnership = new OwnedMotion(tangentOwnership.selfWalk(), tangentOwnership.externalPush(),
                    Vec3.ZERO, tangentOwnership.supportMotion());
        }

        context.collisionTracePhase("ORDINARY_TANGENT");
        SweepOutcome tangent =
                sweepTangent(
                        vertical.body(),
                        tangentRequestForSweep,
                        frame,
                        scene,
                        context,
                        tangentWindow,
                        tangentRequestOwnership,
                        contactBaseline
                );

        MovementIndeterminateReason reason = vertical.indeterminateReason() != NONE
                ? vertical.indeterminateReason() : tangent.indeterminateReason();
        boolean indeterminate =
                vertical.indeterminate()
                        || tangent.indeterminate()
                        || workLimitExceeded(
                        scene,
                        context);

        if (indeterminate && reason == NONE) reason = COLLISION_QUERY_BUDGET;

        final double terminalTimeTicks =
                operationWindow.endTicks();

        /*
         * Ground/support is an endpoint fact.
         *
         * A vertical-leg blocker may have been left behind during the following
         * tangent leg (walking/sliding off an edge, or a moving platform moving
         * away). It therefore cannot remain authoritative support by itself.
         */
        GravityGroundProbe.Result rawProbe =
                indeterminate
                        ? GravityGroundProbe.Result.AIRBORNE
                        : GravityGroundProbe.probe(
                        tangent.body(),
                        frame,
                        scene,
                        terminalTimeTicks,
                        context
                );

        /*
         * Losing support classification is not losing translation authority.
         *
         * Fail closed for grounding/traction this commit, but retain the accepted hard
         * movement and continue rebuilding finite hard-contact velocity authority.
         */
        GravityGroundProbe.Result probe =
                rawProbe.indeterminate()
                        ? GravityGroundProbe.Result.AIRBORNE
                        : rawProbe;

        indeterminate |=
                workLimitExceeded(
                        scene,
                        context);

        if (indeterminate
                && reason == NONE) {
            reason =
                    COLLISION_QUERY_BUDGET;
        }

        /*
         * Rebuild velocity authority from the accepted final body at the exact
         * terminal obstacle time. Historical sweep contacts remain movement
         * provenance only; they must not constrain post-move velocity.
         */
        FinalVelocityAuthority finalAuthority = indeterminate
                ? FinalVelocityAuthority.failed(reason)
                : buildFinalCharacterVelocityAuthority(
                tangent.body(),
                probe,
                frame,
                scene,
                context,
                terminalTimeTicks,
                new Vector3d(verticalRequested).add(tangentRequested)
        );

        indeterminate |=
                finalAuthority.indeterminate()
                        || workLimitExceeded(scene, context);
        if (indeterminate && reason == NONE) {
            reason = finalAuthority.indeterminateReason() != NONE
                    ? finalAuthority.indeterminateReason() : FINAL_CONTACT_QUERY_BUDGET;
        }

        double resolvedUp =
                vertical.applied().dot(up(frame));

        boolean blockedDown =
                requestedUp < -BLOCK_EPSILON
                        && resolvedUp > requestedUp + BLOCK_EPSILON;

        boolean blockedUp =
                requestedUp > BLOCK_EPSILON
                        && vertical.blockingContacts().stream().anyMatch(contact ->
                        ContinuousSupportPolicy.classify(contact.normal(), frame)
                                == ContinuousSupportPolicy.Classification.CEILING);

        // Movement contact is proven at the vertical collision body. The later
        // tangent leg may leave that support without erasing this commit's ground.
        boolean supportingContactDuringMove =
                !indeterminate
                        && blockedDown
                        && movementSupport.stableGround();
        /*
         * The vertical leg owns the surface that actually absorbed gravity-down
         * movement. Keep this same-operation contact even if the later tangent leg
         * walks off the face and terminal support becomes empty.
         *
         * This is material-response evidence only; it is not cross-tick support.
         */
        Optional<GravitySupportContact> movementSupportContact =
                !indeterminate && blockedDown
                        ? movementSupport.supportContact()
                        : Optional.empty();

        /*
         * Same-operation landing authority only.
         *
         * This face was established by the vertical leg itself.  It is deliberately
         * not published into GravityMoveResult or GravityRuntimeState: it exists only
         * long enough for the terminal retention pass below.
         */
        Optional<SupportFaceIdentity> freshLandingFace =
                supportingContactDuringMove
                        ? movementSupportContact
                        .map(GravitySupportContact::faceIdentity)
                        .filter(Objects::nonNull)
                        : Optional.empty();

        boolean terminalGrounded =
                !indeterminate && probe.stableGround();
        boolean walkableGround =
                !indeterminate && probe.walkableGround();

        SupportSelection support =
                !indeterminate
                        ? supportSelection(probe)
                        : SupportSelection.NONE;

        /*
         * Do not mutate SweepOutcome-owned Vector3d instances.
         */
        Vector3d applied =
                new Vector3d(vertical.applied())
                        .add(tangent.applied());

        return new RouteOutcome(
                tangent.body(),
                applied,
                blockedDown,
                blockedUp,
                tangent.tangentBlocked(),
                supportingContactDuringMove,
                movementSupportContact,
                freshLandingFace,
                terminalGrounded,
                walkableGround,
                support.block(),
                indeterminate,
                tangent.supportFollowRise(),

                /*
                 * Historical tangent blockers remain route/step diagnostics.
                 * They are NOT velocity authority.
                 */
                tangent.tangentBlockingNormals(),
                tangent.blockingContacts(),

                /*
                 * supportContact describes final-body support only.
                 */
                support.contact(),

                /*
                 * Terminal-time planes for this requested direction. A later
                 * velocity boundary revalidates its own finite entry.
                 */
                indeterminate
                        ? List.of()
                        : finalAuthority.constraints(),

                /*
                 * Step policy authority:
                 * post-landing / pre-tangent-CCD request and matching ownership.
                 */
                tangentRequestForSweep,
                tangentRequestOwnership,
                reason
        );
    }

    /**
     * Bounded rise -> traverse -> settle traversal candidate from the
     * authoritative operation-start body.
     *
     * <p>This single exact route serves both the vanilla-like authored step
     * (budget equals the authored {@code maxUpStep}) and custom-gravity
     * bounded voxel terrain traversal (using the same authored height).
     * No second locomotion solver exists: the relaxed path still proves rise
     * clearance, traverse legality, settle legality, a final body with no new
     * obstacle penetration, a legal final landing, directional progress, and a terrain rise
     * cost inside the operation-local budget through the same exact CCD
     * geometry.</p>
     *
     * <p><b>Candidate path-time allocation.</b> The three legs are the only
     * path through this candidate, so they consume one deterministic
     * monotonic obstacle-time path inside the operation window. Each leg is
     * a contiguous sub-window of {@code operationWindow}: rise first, then
     * traverse, then settle. The split fractions are proportional to each
     * leg's <em>requested</em> displacement magnitude (the same policy the
     * ordinary route uses for its vertical/tangent legs), so legs neither
     * restart obstacle time at the operation start nor grant themselves the
     * whole remaining operation:
     *
     * <pre>
     *   riseRequested      = terrainRiseBudget
     *   traverseRequested  = |tangentRequested|
     *   settleRequested    = |requestedUp - terrainRiseBudget|
     *                        (the settle path needed when the full rise clears)
     *   legFraction_i      = requestedLength_i / sum(requestedLengths)
     * </pre>
     *
     * Internal bumps inside a leg continue through
     * {@link SweepTimeWindow#remainingAfter(double)} and never restart the
     * leg window. Baseline-route computation is an independent alternative
     * candidate and never consumes this candidate's time. The candidate's
     * final body legality and final support probe inspect obstacle geometry
     * at the candidate terminal operation time ({@code
     * operationWindow.endTicks()}), never a fresh full-tick instant.</p>
     *
     * <p>Moving obstacles are supported by the same explicit time model: the
     * scene broad phase and the CCD narrow phase both consume the exact
     * window passed to the leg, so a moving platform/wall is resolved
     * relative to the body motion during that leg instead of being rejected
     * wholesale. Candidates whose geometry/time result genuinely cannot be
     * proven (indeterminate query, work-budget exhaustion, terminal
     * penetration, missing terminal landing) are still rejected.</p>
     */
    private static StepAttempt solveTraversalCandidate(
            CollisionBody startBody,
            Vector3d tangentRequested,
            OwnedMotion tangentRequestOwnership,
            double requestedUp,
            Vector3d tangentDirection,
            double authoredStepHeight,
            double terrainRiseBudget,
            boolean voxelTerrainEvidence,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            SweepTimeWindow operationWindow,
            BodyCollisionDelta.Baseline contactBaseline
    ) {
        double riseRequested =
                Math.max(0.0D, terrainRiseBudget);
        double traverseRequested =
                tangentRequested.length();
        double settleRequested =
                Math.max(0.0D, terrainRiseBudget - requestedUp);
        double totalRequested =
                riseRequested + traverseRequested + settleRequested;

        SweepTimeWindow riseWindow = operationWindow;
        SweepTimeWindow traverseWindow = operationWindow;
        SweepTimeWindow settleWindow = operationWindow;
        if (totalRequested > 0.0D) {
            double riseFraction =
                    riseRequested / totalRequested;
            double traverseFraction =
                    traverseRequested / totalRequested;
            riseWindow = operationWindow.segment(
                    0.0D,
                    riseFraction
            );
            traverseWindow = operationWindow.segment(
                    riseFraction,
                    riseFraction + traverseFraction
            );
            settleWindow = operationWindow.segment(
                    riseFraction + traverseFraction,
                    1.0D
            );
        }

        context.collisionTracePhase("STEP_RISE");
        SweepOutcome rise = sweepAxis(
                startBody,
                new Vector3d(up(frame)).mul(terrainRiseBudget),
                frame,
                scene,
                context,
                riseWindow,
                contactBaseline
        );
        String riseFailure = legFailure("RISE", rise, scene, context);
        if (riseFailure != null) return StepAttempt.rejected(riseFailure);

        double actualRise = rise.applied().dot(up(frame));
        if (actualRise <= STEP_HEIGHT_EPSILON) {
            return StepAttempt.rejected("NO_RISE_CLEARANCE");
        }

        context.collisionTracePhase("STEP_TRAVERSE");
        SweepOutcome traverse = sweepTangent(
                rise.body(),
                tangentRequested,
                frame,
                scene,
                context,
                traverseWindow,
                contactBaseline
        );
        String traverseFailure = legFailure(
                "TRAVERSE", traverse, scene, context
        );
        if (traverseFailure != null) {
            return StepAttempt.rejected(traverseFailure);
        }

        Vector3d settleRequest =
                new Vector3d(up(frame)).mul(requestedUp - actualRise);
        context.collisionTracePhase("STEP_SETTLE");
        SweepOutcome settle = sweepAxis(
                traverse.body(),
                settleRequest,
                frame,
                scene,
                context,
                settleWindow,
                contactBaseline
        );
        String settleFailure = legFailure(
                "SETTLE", settle, scene, context
        );
        if (settleFailure != null) return StepAttempt.rejected(settleFailure);

        /*
         * JOML add/sub mutate their receiver. Leg-applied vectors are owned
         * by the SweepOutcome records and must survive for exact provenance
         * checks below (riseUp, settleUp, traverseUp), so the candidate sum
         * is built from defensive copies only.
         */
        Vector3d candidateApplied = new Vector3d(rise.applied())
                .add(traverse.applied())
                .add(settle.applied());
        CollisionBody finalBody = settle.body();

        /*
         * Exact step provenance: the discrete step contribution is the sum of
         * the two explicit vertical legs. The traverse begins as a pure
         * gravity-tangent request; any vertical component it applied must be
         * its own walkable support-follow correction (wall tangent constraints
         * cannot own vertical provenance).
         */
        double riseUp = rise.applied().dot(up(frame));
        double settleUp = settle.applied().dot(up(frame));
        double traverseUp = traverse.applied().dot(up(frame));
        double traverseRise =
                traverse.supportFollowRise();

        /*
         * Traverse starts from a strictly gravity-tangent request.
         * Its wall constraints are also gravity-tangent.
         *
         * Therefore every meaningful gravity-up displacement committed during
         * traverse must be exactly the committed support-follow provenance.
         */
        double traverseProvenanceError =
                Math.abs(traverseUp - traverseRise);

        if (traverseProvenanceError > STEP_HEIGHT_EPSILON) {
            throw new IllegalStateException(
                    "step traverse support-follow provenance mismatch: "
                            + "traverseUp=" + traverseUp
                            + ", supportFollowRise=" + traverseRise
                            + ", error=" + traverseProvenanceError
                            + ", applied=" + traverse.applied()
                            + ", frame=" + frame
            );
        }

        double discreteStepHeight =
                riseUp + settleUp;

        /*
         * A rise -> traverse -> settle candidate is allowed to settle slightly
         * below its starting gravity-height.
         *
         * That does not violate the collision solver: it only means that this
         * candidate is not a valid upward step. Treat it as ordinary candidate
         * rejection and fall back to the already-computed baseline route.
         *
         * Do not promote this semantic rejection to a solver invariant failure.
         */
        if (discreteStepHeight <= STEP_HEIGHT_EPSILON) {
            return StepAttempt.rejected("NO_NET_STEP_HEIGHT");
        }

        /*
         * Terrain rise cost is the single authoritative traversal metric:
         *
         *     discrete terrain rise (explicit rise/settle legs)
         *     + support-follow rise actually committed inside the traverse
         *
         * It never includes recoveryMovement (ordinary candidates start from
         * the authoritative body), never includes requested gravity-down motion
         * (settle nets it out of the discrete legs), and never replaces the
         * public GravityMoveResult.stepHeight semantics, which remain exactly
         * the discrete vertical legs of an accepted step.
         */
        double supportFollowRise = traverse.supportFollowRise();
        double terrainRiseCost = terrainTraversalRiseCost(
                discreteStepHeight,
                supportFollowRise
        );

        /*
         * Exceeding the terrain budget is ordinary locomotion policy
         * rejection: the finite feature is taller than the allowed rise and
         * the candidate falls back to the completed baseline route. It is
         * never a solver indeterminate.
         */
        double budgetAllowance =
                Math.max(0.0D, requestedUp) + STEP_HEIGHT_EPSILON;
        if (terrainRiseCost > terrainRiseBudget + budgetAllowance) {
            return StepAttempt.rejected("TERRAIN_RISE_EXCEEDED");
        }

        /*
         * The one-block relaxed budget applies only to static voxel terrain
         * evidence; an arbitrary oblique surface must keep its authored step
         * budget so a long steep plane cannot be climbed by per-tick micro
         * rises below one block.
         */
        if (terrainRiseCost
                > authoredStepHeight + STEP_HEIGHT_EPSILON
                && !voxelTerrainEvidence) {
            return StepAttempt.rejected("NON_VOXEL_TERRAIN_RELAXATION");
        }

        /*
         * Candidate terminal time is the end of the settle window, which by
         * construction is the end of the operation window. Final legality
         * must inspect obstacle geometry at that instant; a moving platform
         * beneath the body at the terminal instant is current geometry, not
         * a reason to reject the candidate.
         */
        double terminalTimeTicks =
                settleWindow.endTicks();
        BodyCollisionDelta finalValidation;
        try {
            finalValidation = contactBaseline.compareAt(
                    finalBody, terminalTimeTicks);
        } catch (CollisionComplexityLimitException exception) {
            return StepAttempt.rejected("FINAL_INDETERMINATE");
        }
        if (workLimitExceeded(scene, context)) {
            return StepAttempt.rejected("FINAL_INDETERMINATE");
        }
        if (!finalValidation.legal()) {
            return StepAttempt.rejected("FINAL_OVERLAP");
        }

        GravityGroundProbe.Result probe = GravityGroundProbe.probe(
                finalBody,
                frame,
                scene,
                terminalTimeTicks,
                context
        );
        if (probe.indeterminate() || workLimitExceeded(scene, context)) {
            return StepAttempt.rejected("GROUND_PROBE_INDETERMINATE");
        }
        if (!probe.stableGround()) {
            return StepAttempt.rejected("NO_STABLE_LANDING");
        }

        boolean settleBlockedDown = settleRequest.dot(up(frame))
                < -BLOCK_EPSILON
                && settle.applied().dot(up(frame))
                > settleRequest.dot(up(frame)) + BLOCK_EPSILON
                && probe.stableGround();
        if (!settleBlockedDown) {
            return StepAttempt.rejected("NO_SETTLE_CONTACT");
        }

        /*
         * Rebuild the complete endpoint authority at the accepted candidate's
         * terminal obstacle time. Historical rise/traverse/settle contacts are path
         * provenance only.
         *
         * Only finite contacts active for this direction supply planes.
         * Feet selection never adds or replaces a hard contact plane.
         */
        FinalVelocityAuthority finalAuthority =
                buildFinalCharacterVelocityAuthority(
                        finalBody,
                        probe,
                        frame,
                        scene,
                        context,
                        terminalTimeTicks,
                        new Vector3d(tangentRequested).fma(requestedUp, up(frame))
                );

        if (finalAuthority.indeterminate()
                || workLimitExceeded(scene, context)) {
            return StepAttempt.rejected(
                    "FINAL_MANIFOLD_INDETERMINATE"
            );
        }

        // The settle collision proves movement support; the probe owns only
        // the candidate's new terminal support and walkability.
        boolean terminalGrounded = probe.stableGround();
        boolean walkableGround = probe.walkableGround();
        SupportSelection support = supportSelection(probe);

        RouteOutcome route =
                new RouteOutcome(
                        finalBody,
                        candidateApplied,
                        settleBlockedDown,
                        false,
                        traverse.tangentBlocked(),
                        settleBlockedDown,

                        /*
                         * The settle leg is the step candidate's gravity-down landing leg.
                         */
                        settleBlockedDown
                                ? support.contact()
                                : Optional.empty(),

                        Optional.empty(),
                        terminalGrounded,
                        walkableGround,
                        support.block(),
                        false,
                        traverse.supportFollowRise(),

                        /*
                         * Historical movement blockers remain
                         * step/route provenance.
                         */
                        traverse.tangentBlockingNormals(),

                        /*
                         * Endpoint blocking diagnostics are rebuilt
                         * from the accepted terminal manifold.
                         */
                        finalAuthority.blockingContacts(),

                        /*
                         * Final-body support only.
                         */
                        support.contact(),

                        finalAuthority.constraints(),

                        /*
                         * Preserve the exact request/ownership pair that authorized
                         * this terrain-traversal candidate.
                         */
                        new Vector3d(tangentRequested),
                        tangentRequestOwnership,
                        NONE
                );
        return new StepAttempt(
                route,
                candidateApplied.dot(tangentDirection),
                discreteStepHeight,
                null
        );
    }

    private static String stepIneligibility(
            StepUpIntent intent,
            Vector3d tangentRequested,
            double requestedUp,
            RouteOutcome baseline,
            boolean initialStableSupport,
            CollisionScene scene,
            ObbQueryContext context,
            OwnedMotion tangentOwnership,
            MovementIndeterminateReason initialSupportFailure
    ) {
        if (requestedUp > STEP_INTENT_EPSILON) {
            return "UPWARD_INTENT";
        }
        if (initialSupportFailure != NONE) {
            return "INITIAL_SUPPORT_INDETERMINATE";
        }

        if (tangentOwnership != null) {
            double ownershipEpsilonSquared =
                    STEP_INTENT_EPSILON
                            * STEP_INTENT_EPSILON;

            /*
             * Discrete terrain traversal is locomotion policy. There must be a
             * genuine current-tick SELF_WALK request before a blocked tangent route
             * may be reinterpreted as rise -> traverse -> settle.
             *
             * Pure platform carry therefore cannot make an idle player climb a stair.
             */
            if (tangentOwnership.selfWalk().lengthSqr()
                    <= ownershipEpsilonSquared) {
                return "NO_SELF_WALK_OWNERSHIP";
            }

            /*
             * Explicit push/knockback/piston motion must not be converted into
             * terrain traversal. Known acceleration/correction evidence also
             * does not establish self input.
             *
             * supportMotion is deliberately NOT rejected here:
             *
             *     SELF_WALK + current platform tangent
             *
             * is a legal operation-local locomotion request.
             *
             * Check the forbidden owners independently. Do not sum them: opposite
             * external/passive vectors could otherwise cancel numerically and
             * incorrectly authorize a step.
             */
            if (tangentOwnership.externalPush().lengthSqr()
                    > ownershipEpsilonSquared
                    || tangentOwnership.passive().lengthSqr()
                    > ownershipEpsilonSquared) {
                return "NON_SELF_TANGENT_OWNERSHIP";
            }
        }

        if (intent.terrainRiseBudget()
                <= STEP_INTENT_EPSILON) {
            return "DISABLED";
        }
        if (tangentRequested.lengthSquared()
                <= STEP_INTENT_EPSILON * STEP_INTENT_EPSILON) {
            return "NO_TANGENT_INTENT";
        }
        if (workLimitExceeded(scene, context)) {
            return "BUDGET_EXCEEDED";
        }
        if (baseline.indeterminate()) {
            return "BASELINE_INDETERMINATE";
        }
        if (!baseline.blockedTangent()) {
            return "TANGENT_NOT_BLOCKED";
        }
        if (!initialStableSupport
                && !baseline.terminalGrounded()) {
            return "NOT_STABLY_SUPPORTED_FOR_STEP";
        }
        return null;
    }

    private static String legFailure(
            String leg,
            SweepOutcome outcome,
            CollisionScene scene,
            ObbQueryContext context
    ) {
        if (outcome.indeterminate() || workLimitExceeded(scene, context)) {
            return leg + "_INDETERMINATE";
        }
        return null;
    }

    /**
     * Ordinary vertical provenance. The upward request is parallel to up until
     * first contact; a WALL or CEILING then constrains only real inward motion.
     * Projected displacement remains this leg's locomotion, never step rise,
     * support-follow, recovery or a new tangent-input owner.
     */
    private static SweepOutcome sweepVertical(CollisionBody start, Vector3d movement,
            GravityFrame frame, CollisionScene scene, ObbQueryContext context,
            SweepTimeWindow window, BodyCollisionDelta.Baseline contactBaseline) {
        if (movement.dot(up(frame)) <= BLOCK_EPSILON) {
            return sweepAxis(start, movement, frame, scene, context, window, contactBaseline);
        }
        var result = KinematicSweepKernel.advanceProjected(start, movement, scene, window,
                context, contactBaseline, MAX_BUMP);
        return new SweepOutcome(result.body(), result.applied(), result.impacts(), result.reason() != NONE,
                result.impacts().stream().anyMatch(contact ->
                        contact.surfaceVelocity().lengthSquared() > 0),
                0, false, List.of(), List.of(), result.reason());
    }

    /**
     * One-dimensional support/step locator sweep. Downward landing, floor snap
     * and authored stair rise keep their existing axis-constrained semantics.
     *
     * <p>The sweep consumes exactly {@code window}: the scene broad phase,
     * the CCD narrow phase and moving-obstacle displacement all use that
     * operation-local interval. A moving obstacle is resolved through the
     * same CCD relative-motion model as a static one; it is reported on the
     * outcome only as diagnostics ({@code dependsOnMovingObstacle}), never as
     * an automatic rejection.</p>
     */
    private static SweepOutcome sweepAxis(
            CollisionBody start,
            Vector3d movement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            SweepTimeWindow window,
            BodyCollisionDelta.Baseline contactBaseline
    ) {
        Vector3d up = up(frame);
        double requestedScalar = movement.dot(up);
        if (Math.abs(requestedScalar) <= BLOCK_EPSILON) {
            return SweepOutcome.clear(start);
        }

        List<CollisionObstacle> obstacles = scene.query(
                start,
                movement,
                window
        );
        boolean movingObstacle = hasMovingObstacle(obstacles, start.center());
        if (workLimitExceeded(scene, context)) {
            return new SweepOutcome(
                    start, new Vector3d(), List.of(), true, movingObstacle,
                    0.0D, false, List.of(), List.of(), COLLISION_QUERY_BUDGET
            );
        }

        KinematicSweepKernel.EarliestContactBatch toi =
                KinematicSweepKernel.queryEarliestOver(
                        start,
                        movement,
                        obstacles,
                        window,
                        scene.time().intervalTicks(),
                        context,
                        KinematicSweepKernel.EarliestQueryPolicy.CHARACTER,
                        contactBaseline);
        if (toi.indeterminate() || toi.overlapping()) {
            return new SweepOutcome(
                    start, new Vector3d(), List.of(), true, movingObstacle,
                    0.0D, false, List.of(), List.of(),
                    toi.indeterminate() ? (toi.indeterminateReason() == SWEEP_QUERY_INDETERMINATE ? VERTICAL_SWEEP_INDETERMINATE : toi.indeterminateReason())
                            : VERTICAL_SWEEP_INITIAL_OVERLAP
            );
        }
        if (toi.contacts().isEmpty()) {
            return new SweepOutcome(
                    start.move(movement), movement, List.of(), false,
                    movingObstacle, 0.0D, false, List.of(), List.of(), NONE
            );
        }

        double moveToi = clampToUnit(toi.timeOfImpact());
        Vector3d applied =
                new Vector3d(up).mul(requestedScalar * moveToi);
        List<ContactConstraintProjector.Constraint> velocityConstraints =
                new ArrayList<>();
        if (requestedScalar > BLOCK_EPSILON) {
            /*
             * An upward movement that was clipped by a ceiling keeps the
             * exact affine ceiling constraint at the final velocity boundary:
             * n dot v >= n dot surfaceVelocity. Full plane normals are the
             * movement-canonical normals for a pure vertical stop.
             */
            for (CollisionContact contact : toi.contacts()) {
                if (ContinuousSupportPolicy.classify(contact.normal(), frame)
                        == ContinuousSupportPolicy.Classification.CEILING) {
                    CurrentContactConstraintBuilder.addConstraint(
                            velocityConstraints,
                            contact.normal(),
                            contact.surfaceVelocity()
                    );
                }
            }
        }
        return new SweepOutcome(
                start.move(applied),
                applied,
                toi.contacts(),
                false,
                movingObstacle,
                0.0D,
                false,
                List.of(),
                velocityConstraints, NONE
        );
    }

    /**
     * Gravity-tangent sweep with operation-local active constraints. Every
     * cotemporal plane is installed together, and earlier blocking planes stay
     * active for the remainder of this one sweep so later projections cannot
     * re-enter them.
     *
     * <p>Contacts are classified by {@link ContinuousSupportPolicy}, the
     * ordinary continuous-slope fast path only. Continuous {@code FLOOR}
     * contacts never become tangent wall constraints: an entering floor is
     * converted into the minimum gravity-up support-follow correction
     * (bounded by the continuous rise ratio), then the corrected movement is
     * re-swept through the exact CCD geometry for non-voxel geometry only.
     * Voxel faces always use the explicit bounded up/traverse/down step;
     * their normals are never synthesized or merged into a slope. When a continuous
     * support-follow correction would exceed the continuous rise ratio, the
     * fast path declines as an ordinary locomotion block (never a solver
     * indeterminate); the bounded terrain traversal candidate is the only
     * route that may still cross such a finite feature. Only
     * {@code WALL}/{@code CEILING} contacts constrain the tangent route and
     * may report {@code blockedTangent}. A small projection correction never
     * erases large legal tangent motion, and a genuinely stalled iteration is
     * a convergence failure, not an ordinary wall stop.</p>
     */

    private static SweepOutcome sweepTangent(
            CollisionBody start,
            Vector3d movement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            SweepTimeWindow window,
            BodyCollisionDelta.Baseline contactBaseline
    ) {
        return sweepTangent(
                start,
                movement,
                frame,
                scene,
                context,
                window,
                null,
                contactBaseline
        );
    }

    private static SweepOutcome sweepTangent(
            CollisionBody start,
            Vector3d movement,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            SweepTimeWindow window,
            OwnedMotion ownership,
            BodyCollisionDelta.Baseline contactBaseline
    ) {
        CollisionBody current = start;
        Vector3d remaining = new Vector3d(movement);

        OwnedMotion remainingOwnership =
                ownership;


        Vector3d applied = new Vector3d();
        List<CollisionContact> blocking = new ArrayList<>();
        List<ContactConstraintProjector.Constraint> activeConstraints =
                new ArrayList<>();
        List<Vector3d> tangentBlockingNormals = new ArrayList<>();
        List<ContactConstraintProjector.Constraint>
                tangentVelocityConstraints = new ArrayList<>();
        boolean movingObstacle = false;
        boolean indeterminate = false;
        MovementIndeterminateReason reason = NONE;
        boolean tangentBlocked = false;
        SweepTimeWindow remainingWindow = window;

        /*
         * sweepTangent owns a gravity-tangent request. Any meaningful gravity-up
         * displacement that is actually committed by this sweep therefore belongs
         * exclusively to walkable support-following.
         */
        double requestedVertical = movement.dot(up(frame));
        if (Math.abs(requestedVertical) > BLOCK_EPSILON) {
            throw new IllegalArgumentException(
                    "tangent sweep received non-tangent movement: movement="
                            + movement
                            + ", vertical=" + requestedVertical
                            + ", frameUp=" + frame.up()
            );
        }

        double maxSupportRise = movement.length()
                * TerrainTraversalPolicy.MAX_CONTINUOUS_SUPPORT_RISE_RATIO
                + SUPPORT_RISE_ALLOWANCE;
        double lastToi = Double.NaN;
        boolean lastAdvanced = false;
        boolean lastConstraintAdded = false;
        TangentTermination termination = TangentTermination.MAX_BUMP;

        for (int bump = 0; bump < MAX_BUMP; bump++) {
            if (remaining.lengthSquared() <= BLOCK_EPSILON_SQUARED) {
                remaining = new Vector3d();
                termination = TangentTermination.ZERO_REMAINING;
                break;
            }

            List<CollisionObstacle> obstacles = scene.query(
                    current,
                    remaining,
                    remainingWindow
            );
            boolean queryHasMoving = hasMovingObstacle(
                    obstacles, current.center()
            );
            movingObstacle |= queryHasMoving;
            if (workLimitExceeded(scene, context)) {
                indeterminate = true;
                reason = COLLISION_QUERY_BUDGET;
                termination = TangentTermination.QUERY_BUDGET;
                break;
            }

            KinematicSweepKernel.EarliestContactBatch toi =
                    KinematicSweepKernel.queryEarliestOver(
                            current,
                            remaining,
                            obstacles,
                            remainingWindow,
                            scene.time().intervalTicks(),
                            context,
                            KinematicSweepKernel
                                    .EarliestQueryPolicy.CHARACTER,
                            contactBaseline);
            if (toi.indeterminate()) {
                indeterminate = true;
                reason = toi.indeterminateReason() == SWEEP_QUERY_INDETERMINATE ? TANGENT_SWEEP_INDETERMINATE : toi.indeterminateReason();
                termination = TangentTermination.NARROW_PHASE_INDETERMINATE;
                break;
            }
            if (toi.overlapping()) {
                indeterminate = true;
                reason = TANGENT_SWEEP_INITIAL_OVERLAP;
                termination = TangentTermination.INITIAL_OVERLAP;
                break;
            }
            if (toi.contacts().isEmpty()) {
                current = current.move(remaining);
                applied.add(remaining);
                remaining = new Vector3d();
                termination = TangentTermination.COMPLETED;
                break;
            }

            double moveToi = clampToUnit(toi.timeOfImpact());
            Vector3d advance = new Vector3d(remaining).mul(moveToi);
            boolean advanced = moveToi > TOI_EPSILON
                    || advance.lengthSquared() > BLOCK_EPSILON_SQUARED;
            current = current.move(advance);
            applied.add(advance);
            remaining.sub(advance);

            if (remainingOwnership != null) {
                /*
                 * Geometry advanced exactly moveToi of the one selected remaining
                 * segment. Every owner consumes the same fraction.
                 */
                remainingOwnership =
                        remainingOwnership.scale(
                                1.0D - moveToi
                        );

            }

            remainingWindow =
                    remainingWindow.remainingAfter(moveToi);

            // Classify contacts BEFORE deciding tangent-plane ownership.
            // Continuous floors may demand a support-follow correction; walls
            // (and tangent-projectable ceilings) may become tangent
            // constraints; a pure gravity ceiling blocks the corrected rise.
            // Sub-threshold positive-up voxel faces are WALL here: the
            // ordinary route declines them and the bounded terrain traversal
            // candidate decides whether they are finite traversable features.
            List<CollisionContact> floorContacts = new ArrayList<>();
            List<CollisionContact> wallContacts = new ArrayList<>();
            List<Vector3d> wallNormals = new ArrayList<>();

            boolean ceilingBlocked = false;
            for (CollisionContact contact : toi.contacts()) {
                ContinuousSupportPolicy.Classification classification =
                        ContinuousSupportPolicy.classify(
                                contact.normal(), frame
                        );
                switch (classification) {
                    case FLOOR -> {
                        // Entering status for a floor belongs to the FULL
                        // floor plane, not only its gravity-tangent projection.
                        if (remaining.dot(contact.normal())
                                < -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            floorContacts.add(contact);
                        }
                    }
                    case WALL -> {
                        Vector3d planeNormal =
                                CurrentContactConstraintBuilder.tangentPlaneNormal(
                                        contact.normal(),
                                        frame
                                );

                        if (planeNormal == null) {
                            throw new IllegalStateException("WALL has no tangent normal: "
                                    + contact.normal() + ", up=" + frame.up());
                        }

                        double planeDot =
                                remaining.dot(planeNormal);

                        if (GravityDebugLog.ENABLED
                                && (planeDot < -CollisionTolerances.ENTERING_PLANE_EPSILON
                                || remaining.dot(contact.normal()) < -CollisionTolerances.ENTERING_PLANE_EPSILON)) {
                            logTangentContactDecision("WALL_RESPONSE", current, remaining, frame,
                                    contact, moveToi, bump, context);
                        }

                        if (planeDot
                                >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            continue;
                        }

                        wallContacts.add(contact);
                        wallNormals.add(planeNormal);

                        /*
                         * Only meaningful tangent entry becomes public wall-block provenance.
                         */
                        if (planeDot < -BLOCK_EPSILON) {
                            tangentBlocked = true;
                            addTangentBlockingNormal(
                                    tangentBlockingNormals,
                                    planeNormal
                            );
                            /*
                             * The exported velocity constraint uses exactly
                             * the projected tangent-plane normal whose plane
                             * the tangent sweep treats as blocking, paired
                             * with that contact's sampled surface velocity:
                             * minimumDot = planeNormal dot surfaceVelocity.
                             */
                            CurrentContactConstraintBuilder.addConstraint(
                                    tangentVelocityConstraints,
                                    planeNormal,
                                    contact.surfaceVelocity()
                            );
                        }
                    }
                    case CEILING -> {
                        Vector3d normal = contact.normal();

                        /*
                         * A ceiling is first classified against its FULL contact plane.
                         * This matters after FLOOR support-follow has introduced a real
                         * gravity-up component into remaining.
                         */
                        double fullDot = remaining.dot(normal);
                        if (fullDot
                                >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            continue;
                        }

                        double remainingUp =
                                remaining.dot(up(frame));
                        double normalUp =
                                normal.dot(up(frame));

                        /*
                         * The tangent projector cannot modify gravity-vertical motion.
                         *
                         * If that immutable vertical component itself enters the ceiling,
                         * no homogeneous tangent constraint can make the complete contact
                         * plane legal. This is therefore a deterministic locomotion stop,
                         * not a numerical solver failure.
                         */
                        double verticalContribution =
                                remainingUp * normalUp;

                        if (verticalContribution
                                < -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            ceilingBlocked = true;
                            continue;
                        }

                        /*
                         * Otherwise the ceiling is being entered by the gravity-tangent
                         * component. That component can be treated exactly like a wall
                         * constraint.
                         */
                        Vector3d planeNormal =
                                CurrentContactConstraintBuilder.tangentPlaneNormal(normal, frame);

                        if (planeNormal == null) {
                            /*
                             * fullDot proved entering, yet there is no usable tangent plane
                             * and the vertical component was not classified as the cause.
                             * Do not turn this into NON_BLOCKING_CONTACT_ONLY.
                             */
                            ceilingBlocked = true;
                            continue;
                        }

                        double planeDot =
                                remaining.dot(planeNormal);

                        if (planeDot
                                >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            /*
                             * This should only be reachable through numerical disagreement
                             * between the full-plane and tangent decomposition. Fail as a
                             * deterministic ceiling obstruction rather than allowing
                             * penetration or entering an artificial no-progress loop.
                             */
                            ceilingBlocked = true;
                            continue;
                        }

                        wallContacts.add(contact);
                        wallNormals.add(planeNormal);

                        if (planeDot < -BLOCK_EPSILON) {
                            tangentBlocked = true;
                            addTangentBlockingNormal(
                                    tangentBlockingNormals,
                                    planeNormal
                            );
                            CurrentContactConstraintBuilder.addConstraint(
                                    tangentVelocityConstraints,
                                    planeNormal,
                                    contact.surfaceVelocity()
                            );
                        }
                    }
                }
            }
            if (ceilingBlocked) {
                // Remain at the last proven legal body; the ceiling prevents
                // the walkable support-follow correction.
                remaining = new Vector3d();
                tangentBlocked = true;
                termination = TangentTermination.CEILING_BLOCKED;
                break;
            }
            if (floorContacts.isEmpty() && wallNormals.isEmpty()) {
                if (!advanced) {
                    for (CollisionContact contact : toi.contacts()) {
                        logTangentContactDecision("NON_BLOCKING_ZERO_TOI", current, remaining, frame,
                                contact, moveToi, bump, context);
                    }
                    logTangentNoProgress(
                            bump,
                            remaining,
                            activeConstraints.size(),
                            moveToi,
                            false,
                            false,
                            "NON_BLOCKING_CONTACT_ONLY"
                    );
                    indeterminate = true;
                    reason = TANGENT_SWEEP_NON_BLOCKING_ZERO_TOI;
                    termination = TangentTermination.NON_BLOCKING_ZERO_TOI;
                    break;
                }
                continue;
            }

            // Support-follow correction: the minimum single gravity-up
            // correction that satisfies every entering continuous floor plane
            // The classification owns this response for every obstacle shape.
            double requiredRise = 0.0D;
            for (CollisionContact floor : floorContacts) {
                double upDot = floor.normal().dot(up(frame));
                if (upDot <= 0.0D) {
                    throw new IllegalStateException(
                            "FLOOR contact has non-positive gravity-up dot: normal="
                                    + floor.normal()
                                    + ", frameUp=" + frame.up()
                                    + ", upDot=" + upDot
                    );
                }

                requiredRise = Math.max(
                        requiredRise,
                        -remaining.dot(floor.normal()) / upDot
                );
            }

            if (requiredRise > 0.0D) {
                /*
                 * appliedUp is already committed support-follow.
                 *
                 * remainingUp is an earlier support-follow correction that has been
                 * requested but has not yet reached the body's committed position.
                 *
                 * Do NOT put the newly requested correction into the public provenance
                 * until a later CCD advance actually moves the body through it.
                 */
                double committedRise =
                        committedSupportFollowRise(applied, frame);
                double pendingRise = Math.max(
                        0.0D,
                        remaining.dot(up(frame))
                );
                double totalRequestedRise =
                        committedRise + pendingRise + requiredRise;

                if (totalRequestedRise > maxSupportRise) {
                    GravityDebugLog.log(
                            "collision-tangent",
                            "TANGENT_SWEEP_SUPPORT_RISE_DECLINED; "
                                    + "requiredRise=%.9f committedRise=%.9f "
                                    + "pendingRise=%.9f totalRequestedRise=%.9f "
                                    + "maxContinuousRise=%.9f "
                                    + "tangentRequestLength=%.9f remaining=%s bump=%d",
                            requiredRise,
                            committedRise,
                            pendingRise,
                            totalRequestedRise,
                            maxSupportRise,
                            movement.length(),
                            GravityDebugLog.vec(remaining),
                            bump
                    );

                    /*
                     * Continuous fast-path decline is locomotion policy, not
                     * solver failure: the required support-follow rise
                     * exceeds the continuous rise ratio for this tick. The
                     * body stays at its last proven legal position, the route
                     * reports a deterministic tangent block, and the bounded
                     * terrain traversal candidate (if eligible) is the only
                     * route that may still cross the finite feature.
                     *
                     * The declining floor/manifold members are retained as
                     * blocking contacts so voxel-terrain evidence is visible
                     * to the traversal policy. They never become projected
                     * tangent wall constraints by themselves.
                     */
                    blocking.addAll(floorContacts);
                    blocking.addAll(wallContacts);
                    for (CollisionContact declined : floorContacts) {
                        addDeclinedSurfaceBlockingNormal(
                                declined,
                                remaining,
                                frame,
                                tangentBlockingNormals,
                                tangentVelocityConstraints
                        );
                    }
                    for (CollisionContact declined : wallContacts) {
                        addDeclinedSurfaceBlockingNormal(
                                declined,
                                remaining,
                                frame,
                                tangentBlockingNormals,
                                tangentVelocityConstraints
                        );
                    }
                    remaining = new Vector3d();
                    tangentBlocked = true;
                    termination = TangentTermination.SUPPORT_RISE_EXCEEDED;
                    break;
                }

                remaining.add(
                        new Vector3d(up(frame))
                                .mul(requiredRise)
                );

                if (wallNormals.isEmpty()) {
                    continue;
                }
            }

            // Only walls/ceilings that actually block may constrain the route.
            blocking.addAll(wallContacts);
            int constraintsBefore = activeConstraints.size();
            for (Vector3d planeNormal : wallNormals) {
                addActiveConstraint(activeConstraints, planeNormal);
            }
            boolean addedConstraint =
                    activeConstraints.size() > constraintsBefore;

            ContactConstraintProjector.Result projection =
                    ContactConstraintProjector.project(
                            remaining, activeConstraints
                    );
            if (projection.infeasible()) {
                /*
                 * Every active constraint is homogeneous:
                 *
                 *     normal dot movement >= 0
                 *
                 * Therefore Vector3d.ZERO is always feasible. An infeasible result is not
                 * ordinary collision geometry; it means the projector/constraint contract
                 * has been violated.
                 */
                throw new IllegalStateException(
                        "homogeneous tangent collision constraints unexpectedly "
                                + "infeasible: remaining=" + remaining
                                + ", activeConstraints=" + activeConstraints
                                + ", tangentBlockingNormals="
                                + tangentBlockingNormals
                                + ", frame=" + frame
                );
            }
            Vector3d projected = projection.requireProjectedVector();

            if (remainingOwnership != null) {
                /*
                 * One projector selected geometry. Ownership follows its exact linear /
                 * affine response instead of projecting each owner independently.
                 */
                remainingOwnership =
                        GroundTractionPolicy.followResponse(
                                remainingOwnership,
                                projection
                        );

            }

            // A small projection correction must never erase a large legal
            // tangent vector. Only a projected vector that is itself zero
            // justifies stopping the sweep.
            if (projected.lengthSquared() <= BLOCK_EPSILON_SQUARED) {
                remaining = new Vector3d();
                termination = TangentTermination.ZERO_REMAINING;
                break;
            }

            // Explicit stagnation guard: no advance, no new constraint, and no
            // meaningful projection change means the sweep is not converging.
            // This is a solver failure at the last proven legal body, never a
            // normal wall stop.
            boolean projectionChanged =
                    new Vector3d(projected).sub(remaining).lengthSquared()
                    > NO_PROGRESS_EPSILON_SQUARED;
            if (!advanced && !addedConstraint && !projectionChanged) {
                logTangentNoProgress(
                        bump,
                        remaining,
                        activeConstraints.size(),
                        moveToi,
                        false,
                        addedConstraint,
                        "NO_PROGRESS"
                );
                indeterminate = true;
                reason = TANGENT_SWEEP_NO_PROGRESS;
                termination = TangentTermination.NO_PROGRESS;
                break;
            }

            lastToi = moveToi;
            lastAdvanced = advanced;
            lastConstraintAdded = addedConstraint;
            remaining.set(projected);
        }

        if (termination == TangentTermination.MAX_BUMP
                && remaining.lengthSquared() > BLOCK_EPSILON_SQUARED) {
            indeterminate = true;
            reason = TANGENT_SWEEP_BUMP_LIMIT;
            GravityDebugLog.log(
                    "collision-tangent",
                    "TANGENT_SWEEP_MAX_BUMP_EXHAUSTED; bumps=%d remaining=%s "
                            + "activeConstraints=%d lastToi=%.9f lastAdvanced=%s "
                            + "lastConstraintAdded=%s",
                    MAX_BUMP,
                    GravityDebugLog.vec(remaining),
                    activeConstraints.size(),
                    lastToi,
                    lastAdvanced,
                    lastConstraintAdded
            );
        } else if (termination == TangentTermination.MAX_BUMP) {
            // All iterations ran but the last projected vector consumed the
            // remaining movement: a converged stop, not an exhaustion.
            termination = TangentTermination.COMPLETED;
        }
        return new SweepOutcome(
                current,
                applied,
                blocking,
                indeterminate,
                movingObstacle,
                committedSupportFollowRise(applied, frame),
                tangentBlocked,
                tangentBlockingNormals,
                tangentVelocityConstraints, reason
        );
    }

    private static double committedSupportFollowRise(
            Vector3d applied,
            GravityFrame frame
    ) {
        double appliedUp = applied.dot(up(frame));

        /*
         * sweepTangent starts with a strictly gravity-tangent request and all
         * active wall constraints are gravity-tangent. Therefore meaningful
         * gravity-up displacement can only originate from accepted FLOOR
         * support-follow correction.
         */
        if (appliedUp < -SUPPORT_RISE_ALLOWANCE) {
            throw new IllegalStateException(
                    "tangent sweep committed unexplained gravity-down movement: "
                            + "applied=" + applied
                            + ", appliedUp=" + appliedUp
                            + ", frameUp=" + frame.up()
            );
        }

        if (Math.abs(appliedUp) <= BLOCK_EPSILON) {
            return 0.0D;
        }

        return Math.max(0.0D, appliedUp);
    }

    private static void addTangentBlockingNormal(
            List<Vector3d> normals,
            Vector3d normal
    ) {
        for (Vector3d existing : normals) {
            if (CollisionTolerances.sameConstraintIdentity(
                    existing, normal
            )) {
                return;
            }
        }
        normals.add(normal);
    }

    /** Observe selected response contacts only, never resample support or rerun SAT for diagnostics. */
    private static void logTangentContactDecision(String decision, CollisionBody body, Vector3d remaining,
            GravityFrame frame, CollisionContact contact, double moveToi, int bump, ObbQueryContext context) {
        if (!GravityDebugLog.ENABLED) return;
        Vector3d normal = contact.normal();
        Vector3d plane = CurrentContactConstraintBuilder.tangentPlaneNormal(normal, frame);
        Vector3d frameUp = up(frame);
        var supportWork = context.supportWorkTracker();
        String obstacle = contact.obstacle() instanceof BlockObstacle block
                ? block.blockPos() + ":" + block.bounds()
                : contact.obstacle() instanceof EntityObstacle entity
                ? entity.sourceId() + ":" + entity.primitiveId()
                : contact.obstacle().getClass().getSimpleName();
        GravityDebugLog.log("collision-tangent-contact",
                "decision=%s bump=%d bodyCenter=%s obstacle=%s classification=%s normal=%s normalUp=%s "
                        + "remaining=%s fullDot=%s remainingUp=%s planeNormal=%s planeDot=%s "
                        + "pendingSupportFollowRise=%s moveToi=%s supportLimitExceeded=%s supportLimitReason=%s",
                decision, bump, MinecraftGeometryAdapter.toMinecraft(body.center()), obstacle,
                ContinuousSupportPolicy.classify(normal, frame), MinecraftGeometryAdapter.toMinecraft(normal),
                normal.dot(frameUp), MinecraftGeometryAdapter.toMinecraft(remaining), remaining.dot(normal),
                remaining.dot(frameUp), plane == null ? "none" : MinecraftGeometryAdapter.toMinecraft(plane),
                plane == null ? "unavailable" : remaining.dot(plane), Math.max(0, remaining.dot(frameUp)),
                moveToi, supportWork.limitExceeded(), supportWork.limitReason());
    }

    private static void logTangentNoProgress(
            int bump,
            Vector3d remaining,
            int activeConstraintCount,
            double moveToi,
            boolean advanced,
            boolean constraintAdded,
            String reason
    ) {
        GravityDebugLog.log(
                "collision-tangent",
                "TANGENT_SWEEP_%s; bump=%d moveToi=%.9f advanced=%s "
                        + "constraintAdded=%s remaining=%s activeConstraints=%d",
                reason,
                bump,
                moveToi,
                advanced,
                constraintAdded,
                GravityDebugLog.vec(remaining),
                activeConstraintCount
        );
    }

    private static void addActiveConstraint(
            List<ContactConstraintProjector.Constraint> constraints,
            Vector3d normal
    ) {
        for (ContactConstraintProjector.Constraint constraint : constraints) {
            if (CollisionTolerances.sameConstraintIdentity(
                    constraint.normal(), normal
            )) {
                return;
            }
        }
        constraints.add(new ContactConstraintProjector.Constraint(
                normal, 0.0D
        ));
    }

    /** Package-visible for the focused directional-comparison contract test. */
    static boolean improvesDirectionalProgress(
            double baselineProgress,
            double candidateProgress
    ) {
        return Double.isFinite(baselineProgress)
                && Double.isFinite(candidateProgress)
                && candidateProgress >= 0.0D
                && candidateProgress
                > baselineProgress + STEP_PROGRESS_EPSILON;
    }

    /**
     * Builds the requested-direction velocity planes at an accepted endpoint.
     * Production post-move velocities revalidate against the owning scene.
     *
     * Character-route canonical planes are:
     *
     * FLOOR   -> full contact normal
     * CEILING -> full contact normal
     * WALL    -> full contact normal (velocity is not tangent-only motion)
     *
     * Every constraint preserves the sampled obstacle surface velocity:
     *
     *     n dot v >= n dot surfaceVelocity
     *
     * The support query remains independent; semantic support never supplies
     * a collision plane. Finite activation belongs to the current direction.
     */
    private static FinalVelocityAuthority
    buildFinalCharacterVelocityAuthority(
            CollisionBody finalBody,
            GravityGroundProbe.Result support,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext context,
            double terminalTimeTicks,
            Vector3d desiredVelocity
    ) {
        Objects.requireNonNull(finalBody, "finalBody");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");

        /*
         * Support classification is not hard-contact authority.
         *
         * The caller may deliberately pass AIRBORNE after a bounded support query
         * failed. Hard endpoint contact constraints must still be rebuilt from the
         * accepted body and requested velocity.
         */
        if (workLimitExceeded(
                scene,
                context)) {
            return FinalVelocityAuthority.failed(
                    FINAL_CONTACT_QUERY_BUDGET
            );
        }

        CurrentContactQuery.Result finalContacts =
                CurrentContactQuery.velocityContacts(
                        finalBody,
                        desiredVelocity,
                        scene,
                        terminalTimeTicks,
                        context
                );

        if (finalContacts.indeterminate()
                || workLimitExceeded(scene, context)) {
            return FinalVelocityAuthority.failed(queryFailure(scene, context,
                    FINAL_CONTACT_QUERY_INDETERMINATE, FINAL_CONTACT_QUERY_BUDGET));
        }

        List<ContactConstraintProjector.Constraint> constraints;
        try {
            constraints = CurrentContactConstraintBuilder.characterConstraints(finalBody, finalContacts,
                    desiredVelocity, frame, terminalTimeTicks / scene.time().intervalTicks(), context);
        } catch (CollisionComplexityLimitException exhausted) {
            return FinalVelocityAuthority.failed(FINAL_CONTACT_QUERY_BUDGET);
        }
        List<CollisionContact> blockingContacts = finalContacts.contacts().stream()
                .filter(contact -> ContinuousSupportPolicy.classify(contact.normal(), frame)
                        != ContinuousSupportPolicy.Classification.FLOOR).toList();

        return new FinalVelocityAuthority(
                constraints,
                blockingContacts,
                false, NONE
        );
    }

    /**
     * Final-position support authority.
     *
     * Historical sweep contacts must never manufacture final support. If the
     * terminal ground probe cannot establish support, the accepted endpoint is
     * airborne.
     */
    private static SupportSelection supportSelection(
            GravityGroundProbe.Result probe
    ) {
        Objects.requireNonNull(probe, "probe");

        if (probe.indeterminate() || !probe.physicalSupport()) {
            return SupportSelection.NONE;
        }

        /*
         * supportContact is physical endpoint identity and may remain present for
         * steep/non-walkable contact.
         *
         * supportBlock is the Vanilla-compatible standing-block projection.
         * Vanilla mainSupportingBlockPos belongs only to stable terminal ground;
         * a steep physical face must never become the entity's "block below".
         */
        Optional<BlockPos> stableGroundBlock =
                probe.stableGround()
                        ? Optional.ofNullable(probe.supportBlock())
                        .map(BlockPos::immutable)
                        : Optional.empty();

        return new SupportSelection(
                stableGroundBlock,
                probe.supportContact()
        );
    }

    private static void enforceOrdinaryAntiLift(
            Vector3d requested,
            Vector3d resolved,
            double requestedUp,
            double supportFollowRise,
            GravityFrame frame
    ) {
        double resolvedUp = resolved.dot(up(frame));
        double allowed = Math.max(0.0D, requestedUp)
                + supportFollowRise
                + BLOCK_EPSILON;
        boolean lifted = resolvedUp > allowed;
        if (lifted) {
            throw new IllegalStateException(
                    "ordinary collision route created gravity-up movement "
                            + "beyond accepted walkable support-following: "
                            + "requestedUp=" + requestedUp
                            + ", resolvedUp=" + resolvedUp
                            + ", supportFollowRise=" + supportFollowRise
                            + ", requested=" + requested
                            + ", resolved=" + resolved
                            + ", frame=" + frame
            );
        }
    }

    /**
     * Declining continuous support-follow exports the declining surfaces as
     * tangent blocking normals and their exact affine velocity constraints so
     * the velocity response stops the route instead of leaving unowned
     * momentum pushing into the surface. The exported velocity constraint
     * uses the same projected tangent-plane normal whose plane the declined
     * route treats as blocking, paired with that contact's sampled surface
     * velocity.
     */
    private static void addDeclinedSurfaceBlockingNormal(
            CollisionContact contact,
            Vector3d remaining,
            GravityFrame frame,
            List<Vector3d> tangentBlockingNormals,
            List<ContactConstraintProjector.Constraint>
                    tangentVelocityConstraints
    ) {
        Vector3d planeNormal = CurrentContactConstraintBuilder.tangentPlaneNormal(
                contact.normal(),
                frame
        );
        if (planeNormal == null) {
            return;
        }
        if (remaining.dot(planeNormal)
                >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return;
        }
        addTangentBlockingNormal(tangentBlockingNormals, planeNormal);
        CurrentContactConstraintBuilder.addConstraint(
                tangentVelocityConstraints,
                planeNormal,
                contact.surfaceVelocity()
        );
    }

    /**
     * Unique terrain rise cost for one accepted traversal candidate.
     *
     * <p>The metric is the sum of the discrete net rise of the explicit
     * rise/settle legs and the support-follow rise actually committed inside
     * the traverse leg. These are disjoint vertical provenance components, so
     * the cost never double-counts. Recovery belongs to a separate explicit
     * repair operation and never enters this metric; requested
     * gravity-down motion is netted out of the discrete legs, and the public
     * {@code GravityMoveResult.stepHeight} keeps its existing discrete-leg
     * semantics.</p>
     *
     * <p>Package-visible for the focused terrain-cost contract tests.</p>
     */
    static double terrainTraversalRiseCost(
            double discreteStepHeight,
            double supportFollowRise
    ) {
        return Math.max(0.0D, discreteStepHeight)
                + Math.max(0.0D, supportFollowRise);
    }

    /**
     * Debug diagnostics only. Logs which route owned the terrain decision so
     * the resolver's layered semantics are auditable under
     * {@code GravityDebugLog.ENABLED} without any release-path logging cost.
     */
    private static void logContinuousDecision(
            RouteOutcome baseline,
            StepUpIntent intent,
            GravityFrame frame,
            double terrainRiseBudget,
            boolean voxelTerrainEvidence
    ) {
        if (!GravityDebugLog.ENABLED) {
            return;
        }
        boolean continuousSlope =
                baseline.terminalGrounded()
                        && !baseline.blockedTangent()
                        && baseline.supportFollowRise()
                        > SUPPORT_RISE_ALLOWANCE;
        String decision = continuousSlope
                ? "CONTINUOUS_SUPPORT"
                : baseline.blockedTangent()
                        ? "TERRAIN_TRAVERSAL_ATTEMPT_PENDING"
                        : "ORDINARY_ROUTE";
        GravityDebugLog.log(
                "collision-terrain",
                "%s; terminalGrounded=%s blockedTangent=%s supportFollowRise=%.9f "
                        + "terrainRiseBudget=%.9f voxelTerrainEvidence=%s "
                        + "authoredStepHeight=%.9f requestedUp=%.9f frameUp=%s",
                decision,
                baseline.terminalGrounded(),
                baseline.blockedTangent(),
                baseline.supportFollowRise(),
                terrainRiseBudget,
                voxelTerrainEvidence,
                intent.maxStepHeight(),
                baseline.applied().dot(up(frame)),
                GravityDebugLog.vec(frame.up())
        );
    }

    /** Debug diagnostics for one accepted or rejected traversal attempt. */
    private static void logTraversalDecision(
            String decision,
            StepAttempt attempt,
            double baselineProgress,
            double terrainRiseBudget,
            RouteOutcome baseline,
            GravityFrame frame
    ) {
        if (!GravityDebugLog.ENABLED) {
            return;
        }
        RouteOutcome route = attempt.route();
        double traverseRise = route == null
                ? 0.0D
                : route.supportFollowRise();
        double terrainRiseCost = terrainTraversalRiseCost(
                attempt.discreteStepHeight(),
                traverseRise
        );
        GravityDebugLog.log(
                "collision-terrain",
                "%s; reason=%s terrainRiseBudget=%.9f "
                        + "terrainRiseCost=%.9f discreteStepHeight=%.9f "
                        + "supportFollowRise=%.9f baselineProgress=%.9f "
                        + "candidateProgress=%.9f finalGrounded=%s "
                        + "supportBlock=%s blockingContactNormals=%s "
                        + "tangentBlockingNormals=%s frameUp=%s",
                decision,
                attempt.rejectionReason() == null
                        ? "NONE"
                        : attempt.rejectionReason(),
                terrainRiseBudget,
                terrainRiseCost,
                attempt.discreteStepHeight(),
                traverseRise,
                baselineProgress,
                attempt.progress(),
                route != null && route.terminalGrounded(),
                route != null
                        ? route.supportBlock().map(Object::toString)
                        .orElse("none")
                        : "none",
                blockingNormalSummary(baseline),
                route != null
                        ? route.tangentBlockingNormals()
                        : baseline.tangentBlockingNormals(),
                GravityDebugLog.vec(frame.up())
        );
    }

    private static String blockingNormalSummary(
            RouteOutcome route
    ) {
        StringBuilder builder = new StringBuilder();
        for (CollisionContact contact : route.tangentBlockingContacts()) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(GravityDebugLog.vec(contact.normal()));
        }
        return builder.isEmpty() ? "none" : builder.toString();
    }

    private static boolean hasMovingObstacle(
            List<CollisionObstacle> obstacles,
            Vector3d samplePoint
    ) {
        for (CollisionObstacle obstacle : obstacles) {
            if (obstacle instanceof EntityObstacle entity && entity.motion().moving()) {
                return true;
            }
        }
        return false;
    }

    private static boolean workLimitExceeded(
            CollisionScene scene,
            ObbQueryContext context
    ) {
        return scene.diagnostics().limitExceeded()
                || context.workTracker().limitExceeded();
    }

    private static double clampToUnit(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /** Neutral canonical gravity-up axis (JOML copy from the frame orientation). */
    private static Vector3d up(GravityFrame frame) {
        return frame.orientation().axisY(new Vector3d());
    }

    private static MovementIndeterminateReason queryFailure(CollisionScene scene, ObbQueryContext context,
            MovementIndeterminateReason queryReason, MovementIndeterminateReason budgetReason) {
        return workLimitExceeded(scene, context) ? budgetReason : queryReason;
    }

    /**
     * Degraded result carrying exactly the translation the solver proved.
     *
     * <p>The applied displacement is an outcome, never a rollback. A zero
     * displacement means no prospective translation was proven legal before
     * the solver stopped; partial motion means every earlier stage proved its
     * displacement and only the remainder is unknown. Derived facts
     * (grounding, support, contact velocity) are deliberately suppressed
     * because the degraded result must not publish an uncertain derived
     * fact.</p>
     */
    private static GravityMoveResult degraded(
            Vector3d requestedMovement,
            Vector3d appliedMovement,
            GravityFrame frame,
            MovementIndeterminateReason reason
    ) {
        Vector3d applied =
                new Vector3d(
                        Objects.requireNonNull(
                                appliedMovement,
                                "appliedMovement"
                        )
                );

        return new GravityMoveResult(
                requestedMovement,
                applied,
                new Vector3d(),
                applied,
                false,
                false,
                false,
                false,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                0.0D,
                true,
                List.of(),
                0.0D,
                frame,
                Optional.empty(),
                List.of(),
                reason
        );
    }

    /** Degraded result with no proven new translation. */
    private static GravityMoveResult degraded(
            Vector3d requestedMovement,
            GravityFrame frame,
            MovementIndeterminateReason reason
    ) {
        return degraded(
                requestedMovement, new Vector3d(), frame, reason
        );
    }

    private static UnsnappedResolution resolvedResult(
            Vector3d requestedMovement,
            RouteOutcome route,
            double stepHeight,
            GravityFrame frame,
            MovementIndeterminateReason fallbackReason
    ) {
        GravityMoveResult move =
                result(
                        requestedMovement,
                        route,
                        stepHeight,
                        frame,
                        fallbackReason
                );

        return new UnsnappedResolution(
                move,

                /*
                 * An indeterminate route may not export a same-operation landing
                 * identity as correction authority.
                 */
                move.indeterminate()
                        ? Optional.empty()
                        : route.freshLandingFace()
        );
    }

    private static GravityMoveResult result(
            Vector3d requestedMovement,
            RouteOutcome route,
            double stepHeight,
            GravityFrame frame,
            MovementIndeterminateReason fallbackReason
    ) {
        boolean indeterminate =
                route.indeterminate() || fallbackReason != NONE;
        MovementIndeterminateReason indeterminateReason = indeterminate
                ? route.indeterminate()
                ? route.indeterminateReason()
                : fallbackReason
                : NONE;
        return new GravityMoveResult(
                requestedMovement,
                new Vector3d().add(route.applied()),
                new Vector3d(),
                route.applied(),
                route.blockedDown(),
                route.blockedUp(),
                route.blockedTangent(),
                route.supportingContactDuringMove(),
                route.movementSupportContact(),
                route.terminalGrounded(),
                route.walkableGround(),
                route.supportBlock(),
                stepHeight,
                indeterminate,
                route.tangentBlockingNormals(),
                route.supportFollowRise(),
                frame,
                indeterminate
                        ? Optional.empty()
                        : route.supportContact(),
                indeterminate
                        ? List.of()
                        : route.contactVelocityConstraints(),
                indeterminateReason
        );
    }

    private static boolean isFinite(Vector3d vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static void logStepDecision(ObbQueryContext context,
            boolean attempted,
            boolean accepted,
            double stepHeight,
            double baselineProgress,
            double candidateProgress,
            String rejectionReason
    ) {
        /*
         * The immutable decision observation belongs to the operation's own
         * result evidence (at most one record per operation) and is never read
         * by physics: it is recorded unconditionally so diagnostics and
         * focused regressions observe the SAME decision the solver made instead
         * of a debug-flag-dependent shadow of it.
         */
        context.setStepDecision(new ObbQueryContext.StepDecision(attempted, accepted, rejectionReason,
                baselineProgress, candidateProgress));
        GravityDebugLog.log(
                "collision-step",
                "stepAttempted=%s stepAccepted=%s stepHeight=%.9f "
                        + "baselineProgress=%.9f candidateProgress=%.9f "
                        + "stepRejectReason=%s supportLimitExceeded=%s supportLimitReason=%s",
                attempted,
                accepted,
                stepHeight,
                baselineProgress,
                candidateProgress,
                rejectionReason,
                context.supportWorkTracker().limitExceeded(),
                context.supportWorkTracker().limitReason()
        );
    }

    private record UnsnappedResolution(
            GravityMoveResult move,
            Optional<SupportFaceIdentity> freshLandingFace
    ) {
        UnsnappedResolution {
            Objects.requireNonNull(
                    move,
                    "move"
            );

            Objects.requireNonNull(
                    freshLandingFace,
                    "freshLandingFace"
            );
        }
    }

    private record RouteOutcome(
            CollisionBody finalBody,
            Vector3d applied,
            boolean blockedDown,
            boolean blockedUp,
            boolean blockedTangent,
            boolean supportingContactDuringMove,

            /*
             * Same-operation physical contact selected at the gravity-down leg.
             *
             * It can outlive endpoint support only until this Entity.move finishes.
             * Native material callbacks consume it; cross-tick support code must not.
             */
            Optional<GravitySupportContact> movementSupportContact,

            /*
             * Operation-local face established by the vertical landing leg.
             */
            Optional<SupportFaceIdentity> freshLandingFace,

            boolean terminalGrounded,
            boolean walkableGround,
            Optional<BlockPos> supportBlock,
            boolean indeterminate,
            double supportFollowRise,
            List<Vector3d> tangentBlockingNormals,
            List<CollisionContact> tangentBlockingContacts,
            Optional<GravitySupportContact> supportContact,
            List<ContactConstraintProjector.Constraint>
            contactVelocityConstraints,

            /*
             * Exact ordinary-route tangent request after the vertical leg and
             * before
             * tangent CCD/contact clipping.
             *
             * Step eligibility and the alternative step candidate must consume
             * this pair rather than the original pre-landing tangent request.
             */
            Vector3d tangentRequest,
            OwnedMotion tangentRequestOwnership,
            MovementIndeterminateReason indeterminateReason
    ) {
        RouteOutcome {
            Objects.requireNonNull(finalBody, "finalBody");
            Objects.requireNonNull(applied, "applied");
            Objects.requireNonNull(supportBlock, "supportBlock");
            Objects.requireNonNull(
                    freshLandingFace,
                    "freshLandingFace"
            );
            Objects.requireNonNull(
                    tangentBlockingNormals,
                    "tangentBlockingNormals"
            );
            Objects.requireNonNull(
                    tangentBlockingContacts,
                    "tangentBlockingContacts"
            );
            Objects.requireNonNull(
                    supportContact,
                    "supportContact"
            );
            Objects.requireNonNull(
                    contactVelocityConstraints,
                    "contactVelocityConstraints"
            );
            Objects.requireNonNull(
                    tangentRequest,
                    "tangentRequest"
            );
            Objects.requireNonNull(
                    movementSupportContact,
                    "movementSupportContact"
            );

            /*
             * RouteOutcome must not expose the mutable JOML instance supplied by
             * solveOrdinaryRoute / solveTraversalCandidate.
             */
            tangentRequest =
                    new Vector3d(tangentRequest);

            supportBlock =
                    supportBlock.map(Objects::requireNonNull);

            movementSupportContact =
                    movementSupportContact.map(Objects::requireNonNull);

            supportContact =
                    supportContact.map(Objects::requireNonNull);

            if (movementSupportContact.isPresent() && !blockedDown) {
                throw new IllegalArgumentException(
                        "movement support contact requires blockedDown"
                );
            }

            if (walkableGround && !terminalGrounded) {
                throw new IllegalArgumentException(
                        "walkable ground requires terminalGrounded support"
                );
            }

            tangentBlockingNormals =
                    List.copyOf(tangentBlockingNormals);

            tangentBlockingContacts =
                    List.copyOf(tangentBlockingContacts);

            contactVelocityConstraints =
                    CurrentContactConstraintBuilder.merge(
                            contactVelocityConstraints
                    );
        }

        /**
         * Defensive copy because JOML Vector3d is mutable.
         */
        public Vector3d tangentRequest() {
            return new Vector3d(tangentRequest);
        }
    }

    private record FinalVelocityAuthority(
            List<ContactConstraintProjector.Constraint> constraints,
            List<CollisionContact> blockingContacts,
            boolean indeterminate,
            MovementIndeterminateReason indeterminateReason
    ) {
        FinalVelocityAuthority {
            Objects.requireNonNull(constraints, "constraints");
            Objects.requireNonNull(
                    blockingContacts,
                    "blockingContacts"
            );

            constraints =
                    CurrentContactConstraintBuilder.merge(
                            constraints
                    );

            blockingContacts =
                    List.copyOf(blockingContacts);
        }

        static FinalVelocityAuthority failed(MovementIndeterminateReason reason) {
            return new FinalVelocityAuthority(
                    List.of(),
                    List.of(),
                    true, reason
            );
        }
    }

    private record SupportSelection(
            Optional<BlockPos> block,
            Optional<GravitySupportContact> contact
    ) {
        private static final SupportSelection NONE =
                new SupportSelection(Optional.empty(), Optional.empty());

        SupportSelection {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(contact, "contact");
            block = block.map(Objects::requireNonNull);
            contact = contact.map(Objects::requireNonNull);
        }
    }

    private record SweepOutcome(
            CollisionBody body,
            Vector3d applied,
            List<CollisionContact> blockingContacts,
            boolean indeterminate,
            boolean dependsOnMovingObstacle,
            double supportFollowRise,
            boolean tangentBlocked,
            List<Vector3d> tangentBlockingNormals,
            List<ContactConstraintProjector.Constraint>
                    contactVelocityConstraints,
            MovementIndeterminateReason indeterminateReason
    ) {
        SweepOutcome {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(applied, "applied");
            Objects.requireNonNull(blockingContacts, "blockingContacts");
            Objects.requireNonNull(
                    tangentBlockingNormals, "tangentBlockingNormals"
            );
            Objects.requireNonNull(
                    contactVelocityConstraints,
                    "contactVelocityConstraints"
            );
            blockingContacts = List.copyOf(blockingContacts);
            tangentBlockingNormals = List.copyOf(tangentBlockingNormals);
            contactVelocityConstraints =
                    CurrentContactConstraintBuilder.merge(
                            contactVelocityConstraints
                    );
        }

        static SweepOutcome clear(CollisionBody body) {
            return new SweepOutcome(
                    body, new Vector3d(), List.of(), false, false,
                    0.0D, false, List.of(), List.of(), NONE
            );
        }
    }

    /**
     * Why one gravity-tangent sweep stopped. Only {@link #MAX_BUMP} means the
     * hard iteration bound was actually reached with meaningful remaining
     * movement; every other termination has its own truthful diagnostic.
     */
    private enum TangentTermination {
        COMPLETED,
        ZERO_REMAINING,
        QUERY_BUDGET,
        NARROW_PHASE_INDETERMINATE,
        INITIAL_OVERLAP,
        NON_BLOCKING_ZERO_TOI,
        NO_PROGRESS,
        SUPPORT_RISE_EXCEEDED,
        CEILING_BLOCKED,
        MAX_BUMP
    }

    private record StepAttempt(
            RouteOutcome route,
            double progress,
            double discreteStepHeight,
            String rejectionReason
    ) {
        static StepAttempt rejected(String reason) {
            return new StepAttempt(
                    null, Double.NaN, 0.0D, reason
            );
        }
    }
}
