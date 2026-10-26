package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.List;
import java.util.Objects;

/**
 * Loader-neutral geometry-transition planner.
 *
 * <p>This owner holds the transition vocabulary's policy constants and the
 * complete pure planning algorithm: collision-axis deadband, exact frame
 * delta, direct pose fit, support compatibility, bounded support correction,
 * the PRESERVE_SUPPORT candidate, position-authority gating and the final
 * accept/defer decision. The vocabulary types themselves are the sibling
 * top-level types in this package
 * ({@link GeometryTransitionKind}, {@link GeometryTransitionStatus},
 * {@link GeometryTransitionResult}, {@link PositionAuthorityPolicy},
 * {@link PoseFitStatus}, {@link PoseFit}, {@link OperationFrameDiagnostics},
 * {@link OperationPoseTransition}, {@link OperationFrameResult},
 * {@link SupportPreserveOutcome}).</p>
 *
 * <p>Nothing here reads an entity, a level or a platform dimension type, and
 * nothing here logs: transition diagnostics are returned as immutable facts
 * for the target boundary to emit.</p>
 */
public final class GravityGeometryTransitionPlanner {
    private GravityGeometryTransitionPlanner() {}

    /**
     * Collision-axis deadband in radians.
     *
     * <p>Gravity sampling and the exact acceleration direction always use the
     * live sample; only the axial collision geometry may keep the previously
     * installed collision axis. The band is derived from contact-scale
     * geometry: with a character half-diagonal below one block and the ground
     * probe at {@code 8 * CONTACT_SKIN}, a one-radian error budget of
     * {@code 1e-5} moves a resting rim by at most ~10 micrometres, i.e. one
     * contact-skin decade above the probe band. Larger changes require a
     * legal pose; support preservation is an optional optimization.</p>
     */
    public static final double COLLISION_AXIS_ANGULAR_EPSILON_RADIANS =
            1.0E-5D;

    /**
     * Maximum installed-to-proposed collision-axis evolution that a resting
     * single-contact support may own through PRESERVE_SUPPORT.
     *
     * <p>This is a single-tick collision-axis continuity policy, not a
     * gameplay turn rate: only small, continuous radial axis evolution may
     * re-anchor onto the previous support plane. Larger discontinuous changes
     * (teleports between gravity sources, abrupt direction flips) fall back to
     * the ordinary legality/deferred path so the old plane can never pull the
     * body across a 45-180 degree flip. The deadband
     * ({@link #COLLISION_AXIS_ANGULAR_EPSILON_RADIANS}) remains the
     * no-geometry-change boundary; values above it and at or below this
     * constant are the continuous-evolution band that PRESERVE_SUPPORT
     * owns. Three degrees covers ordinary per-tick radial drift up to a
     * small-planet sprint while remaining a small-angle policy.</p>
     */
    public static final double MAX_PRESERVE_SUPPORT_AXIS_DELTA_RADIANS =
            Math.toRadians(3.0D);

    /** Stable reason for an indeterminate fit caused by leaving the captured envelope. */
    public static final String SCENE_COVERAGE_REASON = "SCENE_COVERAGE";

    /** Stable reason for an indeterminate fit caused by the shared work budget. */
    public static final String WORK_BUDGET_REASON = "WORK_BUDGET";

    /** Stable reason a positional candidate was rejected by the authority gate. */
    public static final String POSITION_AUTHORITY_REANCHOR_REASON =
            "POSITION_AUTHORITY_FORBIDS_REANCHOR";

    /**
     * Upper resting gap still considered the same support manifold: the
     * stateless ground probe itself accepts up to its probe distance, so a
     * snapshot taken from a grounded probe can describe the current pose with
     * a comparable gap. Penetration beyond one contact slop is never a rest.
     */
    private static final double RESTING_GAP_LIMIT =
            GravityGroundProbe.SUPPORT_CONTINUITY_REACQUIRE_DISTANCE
                    + CollisionTolerances.CONTACT_SKIN;
    private static final double PENETRATION_GAP_LIMIT =
            CollisionTolerances.CONTACT_SLOP;
    private static final double UNIT_NORMAL_EPSILON = 1.0E-6D;

    /**
     * Plans one operation-frame transition from an already-captured request.
     *
     * <p>This method must never read live world state and must never mutate an
     * entity: the request carries the frozen scene, the installed geometry and
     * the explicit position-authority contract, and the result is one
     * immutable {@link OperationPoseTransition}. A legal anchored candidate
     * remains available if optional support preservation fails:</p>
     *
     * <ol>
     *   <li>deadband: keep the installed occupancy and refresh evidence only;</li>
     *   <li>{@link GeometryTransitionKind#DIRECT_AT_ANCHOR}: same authoritative
     *       P, new exact axis, with no obligation to retain old support;</li>
     *   <li>{@link GeometryTransitionKind#PRESERVE_SUPPORT}: bounded reference
     *       re-anchor onto the previous trusted static single-face support,
     *       when position authority permits this optional optimization or
     *       recovery of an illegal direct pose;</li>
     *   <li>{@link GeometryTransitionKind#DEFERRED}: retain the installed
     *       representation; the ordinary movement still runs.</li>
     * </ol>
     *
     * <p>Candidate fit has a real third answer. An
     * {@link PoseFitStatus#INDETERMINATE} direct fit (a candidate outside the
     * captured envelope, or an exhausted shared work budget) defers
     * immediately: the same shared budget cannot answer a later candidate
     * either, and a partial obstacle list must never be read as "nothing
     * penetrated".</p>
     */
    public static OperationPoseTransition plan(
            GeometryTransitionRequest request
    ) {
        Objects.requireNonNull(request, "request");

        GravityFrame installedFrame = request.installedFrame();
        GravityFrame proposedFrame = request.proposedFrame();
        CollisionBody installedBody = request.installedBody();
        CharacterDimensions dimensions = request.dimensions();

        double exactFrameDeltaRadians =
                exactFrameAngularDistanceRadians(
                        installedFrame,
                        proposedFrame
                );
        boolean deadbandHit = !shouldUpdateCollisionGeometryFrame(
                installedFrame,
                proposedFrame
        );
        double maxCorrection =
                maxPreserveSupportCorrection(installedBody);
        boolean authorityAllowsReanchor =
                request.positionAuthority()
                        == PositionAuthorityPolicy.OPERATION_MAY_REANCHOR;

        if (deadbandHit) {
            /*
             * The collision axis stays installed; fresh tangent/strength
             * evidence belongs to this operation and does not revise physical
             * occupancy.
             */
            GravityFrame refreshedFrame = GravityFrame.completedOnUpAxis(
                    installedFrame.up(),
                    proposedFrame
            );
            return geometryDeadbandRetained(
                    refreshedFrame,
                    new OperationFrameDiagnostics(
                            PoseFitStatus.NOT_ATTEMPTED, null,
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            authorityAllowsReanchor, Double.NaN,
                            maxCorrection
                    )
            );
        }

        RestingContactSnapshot resting = request.restingContact();
        boolean preserveRestingSupport = authorityAllowsReanchor && resting != null
                && resting.usableAtStepStart(request.gameTick())
                && exactFrameDeltaRadians <= MAX_PRESERVE_SUPPORT_AXIS_DELTA_RADIANS
                && supportCompatibleWithFrame(resting, proposedFrame);
        KinematicPose anchoredPose = posePreservingPositionAnchor(
                dimensions,
                request.installedAnchor(),
                proposedFrame
        );

        PoseFit directFit =
                evaluatePoseFit(
                        request.scene(),
                        anchoredPose.body(),
                        request.queryContext()
                );
        // Position authority gates the optional displacement, not a legal
        // rotation at the caller's unchanged anchor.
        if (directFit.legal() && !preserveRestingSupport) {
            return acceptedTransition(
                    proposedFrame,
                    anchoredPose,
                    GeometryTransitionKind.DIRECT_AT_ANCHOR,
                    Vec3d.ZERO,
                    new OperationFrameDiagnostics(
                            directFit.status(), null,
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            authorityAllowsReanchor, Double.NaN,
                            maxCorrection
                    )
            );
        }
        if (directFit.indeterminate()) {
            return geometryUnchanged(
                    installedFrame,
                    directFit.reason(),
                    new OperationFrameDiagnostics(
                            directFit.status(), directFit.reason(),
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            authorityAllowsReanchor, Double.NaN,
                            maxCorrection
                    )
            );
        }
        double requiredCorrection = requiredSupportCorrection(
                dimensions, installedBody, proposedFrame, resting);
        if (!authorityAllowsReanchor) {
            /*
             * The direct endpoint is illegal and recovery requires position
             * authority. Stop
             * here: querying feet support or a candidate pose fit would consume
             * the shared support/narrow-phase budget of the ordinary movement
             * that still has to run, and it could not change the decision.
             */
            return geometryUnchanged(
                    installedFrame,
                    POSITION_AUTHORITY_REANCHOR_REASON,
                    new OperationFrameDiagnostics(
                            directFit.status(), directFit.reason(),
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            false, requiredCorrection, maxCorrection
                    )
            );
        }

        SupportPreservePlan supportPlan = planSupportPreservingPose(
                request,
                resting,
                exactFrameDeltaRadians
        );
        if (supportPlan.pose() != null) {
            // Keep the caller's exact anchor for a zero-correction handoff;
            // a center -> anchor round trip cannot authorize a position write.
            KinematicPose supportedPose = requiredCorrection == 0.0D
                    ? anchoredPose : supportPlan.pose();
            Vec3d supportCorrection = supportedPose
                    .positionAnchor()
                    .subtract(request.installedAnchor());
            return acceptedTransition(
                    proposedFrame,
                    supportedPose,
                    GeometryTransitionKind.PRESERVE_SUPPORT,
                    supportCorrection,
                    new OperationFrameDiagnostics(
                            directFit.status(), directFit.reason(),
                            true, supportPlan.fitStatus(), null,
                            authorityAllowsReanchor,
                            supportCorrection.dot(resting.normal()),
                            maxCorrection
                    )
            );
        }

        OperationFrameDiagnostics diagnostics = new OperationFrameDiagnostics(
                        directFit.status(), directFit.reason(),
                        supportPlan.candidateConstructed(),
                        supportPlan.fitStatus(),
                        supportPlan.reason(),
                        authorityAllowsReanchor,
                        supportPlan.requiredCorrection(),
                        maxCorrection);
        if (directFit.legal()) {
            // The same frozen scene already proved this candidate legal.
            // Failed/indeterminate optimization cannot revoke that proof or
            // reset the shared budget for subsequent movement queries.
            return acceptedTransition(proposedFrame, anchoredPose,
                    GeometryTransitionKind.DIRECT_AT_ANCHOR, Vec3d.ZERO, diagnostics);
        }
        return geometryUnchanged(installedFrame, supportPlan.reason(), diagnostics);
    }

    /**
     * One support-preserving planning attempt: either the pose to install, or
     * the stable reason it was rejected together with the fit evidence that
     * produced that reason.
     *
     * @param pose the pose to install, or {@code null} when the candidate was
     *        rejected
     * @param candidateConstructed whether a bounded candidate pose was actually
     *        built from the resting snapshot
     * @param fitStatus geometric fit of that candidate pose
     * @param reason stable rejection reason, or {@code null} when accepted
     * @param requiredCorrection signed support-normal correction this candidate
     *        requires, or {@code NaN} when it was never computable
     */
    private record SupportPreservePlan(
            KinematicPose pose,
            boolean candidateConstructed,
            PoseFitStatus fitStatus,
            String reason,
            double requiredCorrection
    ) {
        SupportPreservePlan {
            Objects.requireNonNull(fitStatus, "fitStatus");
            if (pose == null) {
                Objects.requireNonNull(reason, "reason");
            } else if (fitStatus != PoseFitStatus.LEGAL) {
                throw new IllegalArgumentException(
                        "an installable support pose must be a legal fit"
                );
            }
        }

        static SupportPreservePlan accepted(
                KinematicPose pose,
                double requiredCorrection
        ) {
            return new SupportPreservePlan(
                    pose, true, PoseFitStatus.LEGAL, null,
                    requiredCorrection
            );
        }

        static SupportPreservePlan rejected(
                boolean candidateConstructed,
                PoseFitStatus fitStatus,
                String reason,
                double requiredCorrection
        ) {
            return new SupportPreservePlan(
                    null, candidateConstructed, fitStatus, reason,
                    requiredCorrection
            );
        }
    }

    /**
     * Builds the PRESERVE_SUPPORT candidate, or rejects it with a stable
     * diagnostic reason. The candidate is evaluated against the same frozen
     * scene and work budget as the direct candidate.
     */
    private static SupportPreservePlan planSupportPreservingPose(
            GeometryTransitionRequest request,
            RestingContactSnapshot resting,
            double exactFrameDeltaRadians
    ) {
        CollisionBody installedBody = request.installedBody();
        GravityFrame proposedFrame = request.proposedFrame();
        double requiredCorrection = requiredSupportCorrection(
                request.dimensions(),
                installedBody,
                proposedFrame,
                resting
        );
        if (exactFrameDeltaRadians
                > MAX_PRESERVE_SUPPORT_AXIS_DELTA_RADIANS) {
            return SupportPreservePlan.rejected(
                    false, PoseFitStatus.NOT_ATTEMPTED,
                    "FRAME_DELTA_TOO_LARGE", requiredCorrection
            );
        }
        if (resting == null) {
            return SupportPreservePlan.rejected(
                    false, PoseFitStatus.NOT_ATTEMPTED,
                    "NO_RESTING_SUPPORT", requiredCorrection
            );
        }
        SupportPreserveOutcome candidate = supportPreservingPoseCandidate(
                request.gameTick(),
                request.dimensions(),
                installedBody,
                installedBody.center(),
                proposedFrame,
                resting
        );
        if (!candidate.preserved()) {
            return SupportPreservePlan.rejected(
                    false, PoseFitStatus.NOT_ATTEMPTED,
                    candidate.fallbackReason(), requiredCorrection
            );
        }
        KinematicPose reanchored = candidate.pose();
        PoseFit fit = evaluatePoseFit(
                request.scene(),
                reanchored.body(),
                request.queryContext()
        );
        if (fit.indeterminate()) {
            return SupportPreservePlan.rejected(
                    true, fit.status(), fit.reason(), requiredCorrection
            );
        }
        if (!fit.legal()) {
            return SupportPreservePlan.rejected(
                    true, fit.status(), "POSE_ILLEGAL", requiredCorrection
            );
        }
        String supportRejection = supportFaceRejectionReason(
                reanchored,
                proposedFrame,
                resting,
                request.scene(),
                request.queryContext()
        );
        if (supportRejection != null) {
            return SupportPreservePlan.rejected(
                    true, fit.status(), supportRejection, requiredCorrection
            );
        }
        Vec3d plannedCorrection = reanchored.positionAnchor()
                .subtract(request.installedAnchor());
        return SupportPreservePlan.accepted(
                reanchored,
                plannedCorrection.dot(resting.normal())
        );
    }

    /**
     * Re-validates that the proposed frame's re-anchored body still stands on
     * the snapshot's exact static single-face support.
     *
     * <p>The probe-band {@link FeetSupportQuery} owns both halves of the check:
     * that the frozen scene still provides a support plane near the candidate
     * body, and that the selected face is the same trusted static face the
     * snapshot named. It deliberately replaces a raw scan of the candidate's
     * movement obstacle list: that list uses the movement contact band, so a
     * body resting at a legal positive gap can sit outside it while its real
     * support face is still within the ground-probe band.</p>
     *
     * @return {@code null} when the same trusted face still supports the body,
     *         otherwise the stable rejection reason
     */
    private static String supportFaceRejectionReason(
            KinematicPose proposedPose,
            GravityFrame proposedFrame,
            RestingContactSnapshot resting,
            CollisionScene scene,
            ObbQueryContext queryContext
    ) {
        if (resting == null) {
            return "NO_RESTING_SUPPORT";
        }
        if (poseWorkLimitExceeded(scene, queryContext)
                || queryContext.supportWorkTracker().limitExceeded()) {
            return WORK_BUDGET_REASON;
        }

        FeetSupportQuery.Result support;
        try {
            support = FeetSupportQuery.query(
                    proposedPose.body(),
                    proposedFrame,
                    scene,
                    0.0D,
                    GravityGroundProbe
                            .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE,
                    resting.faceIdentity(),
                    queryContext
            );
        } catch (CollisionSceneCoverageException unavailable) {
            // The support envelope is larger than the candidate's pose-fit
            // envelope. Failure here rejects only this geometry handoff.
            return SCENE_COVERAGE_REASON;
        }

        if (poseWorkLimitExceeded(scene, queryContext)
                || queryContext.supportWorkTracker().limitExceeded()) {
            return WORK_BUDGET_REASON;
        }
        if (support.indeterminate()) {
            // An unresolved support query is not necessarily budget exhaustion.
            return "SUPPORT_INDETERMINATE";
        }
        if (support.candidates().isEmpty()) {
            return "SUPPORT_PLANE_MISSING";
        }
        boolean trustedFace = support.candidates().stream().anyMatch(face ->
                face.upDot()
                        >= TerrainTraversalPolicy
                        .MIN_CONTINUOUS_SUPPORT_UP_DOT
                        && Objects.equals(
                                face.identity().block(),
                                resting.supportBlock().orElse(null))
                        && face.normal().dot(
                                resting.normal())
                        > 1 - CollisionTolerances.GEOMETRIC_AXIS_EPSILON
        );
        return trustedFace ? null : "SUPPORT_REVALIDATION_FAILED";
    }

    /**
     * Pose-fit evaluation of one candidate body against the operation's frozen
     * scene, fail-closed.
     *
     * <p>This is the only place a transition candidate materializes pose-fit
     * obstacles, and it never re-reads the live world: a coverage failure and
     * an exhausted shared work budget are reported as
     * {@link PoseFitStatus#INDETERMINATE} instead of being reinterpreted as
     * "nothing penetrated the partial list". Only the explicit
     * {@link CollisionSceneCoverageException} of a candidate fit is captured;
     * real programming errors still propagate.</p>
     */
    public static PoseFit evaluatePoseFit(
            CollisionScene scene,
            CollisionBody body,
            ObbQueryContext queryContext
    ) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(queryContext, "queryContext");

        if (poseWorkLimitExceeded(scene, queryContext)) {
            return PoseFit.indeterminate(WORK_BUDGET_REASON);
        }

        List<CollisionObstacle> obstacles;
        try {
            obstacles = scene.queryPoseFit(body);
        } catch (CollisionSceneCoverageException unavailable) {
            return PoseFit.indeterminate(SCENE_COVERAGE_REASON);
        }

        if (poseWorkLimitExceeded(scene, queryContext)) {
            // The scene may have returned only part of its obstacle list.
            return PoseFit.indeterminate(WORK_BUDGET_REASON);
        }

        boolean penetrates = CurrentContactQuery
                .requiresPenetrationRecovery(body, obstacles, queryContext);
        if (poseWorkLimitExceeded(scene, queryContext)) {
            return PoseFit.indeterminate(WORK_BUDGET_REASON);
        }

        return penetrates
                ? PoseFit.illegal(obstacles)
                : PoseFit.legal(obstacles);
    }

    /**
     * Scene materialization and narrow phase can have different accounting
     * owners. Neither may be exhausted when accepting a pose. This reads
     * existing counters only; it never resets a budget or runs another query.
     * Support's separate budget is checked only by support revalidation.
     */
    private static boolean poseWorkLimitExceeded(
            CollisionScene scene,
            ObbQueryContext queryContext
    ) {
        return scene.diagnostics().limitExceeded()
                || queryContext.workTracker().limitExceeded();
    }

    /** Exact legal-start test: touching is legal; only meaningful overlap is not. */
    public static boolean isLegalKinematicPose(
            CollisionBody body,
            List<CollisionObstacle> obstacles
    ) {
        return !CurrentContactQuery.requiresPenetrationRecovery(
                body,
                obstacles
        );
    }

    /**
     * Builds the PRESERVE_SUPPORT candidate pose, or rejects it when the
     * previous resting support cannot safely own this frame change.
     *
     * <p>Ownership gates, in evaluation order: the snapshot must name a
     * static voxel support, carry a trusted single-contact witness (a
     * manifold composite never defines a re-anchor plane), come from the
     * same or immediately preceding authoritative move, still describe the
     * body's current rest plane (a teleport or ledge departure fails the gap
     * check before any reanchor is attempted), remain physically supportable
     * under the proposed frame, and require only a bounded positional
     * correction. Legality is verified by the caller's commit tail.</p>
     */
    public static SupportPreserveOutcome supportPreservingPoseCandidate(
            long gameTick,
            CharacterDimensions dimensions,
            CollisionBody oldBody,
            Vec3d oldCenter,
            GravityFrame proposedFrame,
            RestingContactSnapshot resting
    ) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(oldBody, "oldBody");
        Objects.requireNonNull(oldCenter, "oldCenter");
        Objects.requireNonNull(proposedFrame, "proposedFrame");
        Objects.requireNonNull(resting, "resting");
        if (!resting.staticSupport()
                || resting.supportBlock().isEmpty()) {
            return SupportPreserveOutcome.rejected(
                    "UNSUPPORTED_SNAPSHOT");
        }
        if (!resting.planePreservationEligible()) {
            return SupportPreserveOutcome.rejected(
                    "MANIFOLD_SUPPORT");
        }
        if (resting.gameTick() > gameTick
                || gameTick - resting.gameTick() > 1L) {
            return SupportPreserveOutcome.rejected(
                    "STALE_RESTING_SNAPSHOT");
        }
        Vec3d normal = resting.normal();
        if (!Double.isFinite(normal.lengthSquared())
                || Math.abs(normal.lengthSquared() - 1.0D)
                > UNIT_NORMAL_EPSILON) {
            return SupportPreserveOutcome.rejected(
                    "INVALID_SUPPORT_NORMAL");
        }
        if (!supportCompatibleWithFrame(resting, proposedFrame)) {
            return SupportPreserveOutcome.rejected(
                    "SUPPORT_NOT_WALKABLE");
        }
        double currentRadius =
                CollisionBodyProjection.radiusAlong(oldBody, normal);
        Vec3d currentSupportPoint =
                oldCenter.subtract(normal.multiply(currentRadius));
        double currentSeparation = currentSupportPoint
                .subtract(resting.contactPoint())
                .dot(normal);
        if (currentSeparation < -PENETRATION_GAP_LIMIT
                || currentSeparation > RESTING_GAP_LIMIT) {
            return SupportPreserveOutcome.rejected(
                    "RESTING_GAP");
        }
        CollisionBody proposedAtOldCenter = characterBodyAtCenter(
                dimensions,
                oldCenter,
                proposedFrame.up()
        );
        double proposedRadius = CollisionBodyProjection.radiusAlong(
                proposedAtOldCenter, normal);
        Vec3d proposedSupportPoint =
                oldCenter.subtract(normal.multiply(proposedRadius));
        double proposedSeparation = proposedSupportPoint
                .subtract(resting.contactPoint())
                .dot(normal);
        /*
         * Explicit support handoff keeps the separation the accepted resting
         * snapshot already has. A previous snapshot may legally carry
         * 0 < currentSeparation <= probe/contact band, so the correction is the
         * difference between the two separations, never the proposed separation
         * itself: collapsing onto the witness plane would suck the body down
         * every time the frame changes.
         */
        double correction = currentSeparation - proposedSeparation;
        double maxCorrection = maxPreserveSupportCorrection(oldBody);
        if (Math.abs(correction) > maxCorrection) {
            return SupportPreserveOutcome.rejected(
                    "CORRECTION_TOO_LARGE");
        }
        Vec3d reanchoredCenter = oldCenter.add(
                normal.multiply(correction));
        if (correction == 0.0D) {
            /*
             * The re-anchored plane is the pose the body already occupies:
             * return the exact P-preserving pose so a zero correction cannot
             * introduce a numerically different anchor. Nonzero corrections
             * must not be rounded away: repeated small rotations would then
             * accumulate a gap beyond the fresh support probe.
             */
            return SupportPreserveOutcome.accepted(
                    posePreservingPositionAnchor(
                            dimensions,
                            positionAnchorFromCenter(oldCenter,
                                    dimensions.height()),
                            proposedFrame
                    )
            );
        }
        return SupportPreserveOutcome.accepted(
                posePreservingPositionAnchor(
                        dimensions,
                        positionAnchorFromCenter(
                                reanchoredCenter,
                                dimensions.height()
                        ),
                        proposedFrame
                )
        );
    }

    /** Stable-standing continuity uses the same slope boundary as foot grounding.
     * A steep physical face retains collision authority, never anchoring authority. */
    public static boolean supportCompatibleWithFrame(
            RestingContactSnapshot resting,
            GravityFrame proposedFrame
    ) {
        Objects.requireNonNull(resting, "resting");
        Objects.requireNonNull(proposedFrame, "proposedFrame");
        return ContinuousSupportPolicy.classify(
                resting.normal(),
                proposedFrame) == ContinuousSupportPolicy.Classification.FLOOR;
    }

    /**
     * Dynamic sanity bound for the PRESERVE_SUPPORT positional correction.
     *
     * <p>Even inside the small frame-delta band a malformed or stale witness
     * could demand an abnormal translation. The allowed correction is
     * derived from the body's enclosing bounding radius times the sine of the
     * maximum frame delta (the largest corner displacement of a pure
     * rotation), plus the ground probe and contact skin tolerances. A
     * correction beyond this bound abandons the support-preserving candidate;
     * it is never clamped and committed.</p>
     */
    public static double maxPreserveSupportCorrection(CollisionBody body) {
        Objects.requireNonNull(body, "body");
        Aabb3d bounds = body.enclosingAabb();
        double x = bounds.maxX() - bounds.minX();
        double y = bounds.maxY() - bounds.minY();
        double z = bounds.maxZ() - bounds.minZ();
        double boundingRadius =
                0.5D * Math.sqrt(x * x + y * y + z * z);
        return boundingRadius
                * Math.sin(MAX_PRESERVE_SUPPORT_AXIS_DELTA_RADIANS)
                + GravityGroundProbe
                .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE
                + CollisionTolerances.CONTACT_SKIN;
    }

    /**
     * Signed support-normal correction the PRESERVE_SUPPORT candidate requires:
     * the difference between the projected support radius of the proposed-axis
     * capsule and of the installed capsule. This is diagnostic evidence only;
     * it never authorizes a candidate and it never runs a collision query.
     *
     * <p>It is the same signed quantity the candidate derives as
     * {@code currentSeparation - proposedSeparation}: the accepted resting
     * separation is preserved rather than collapsed onto the witness plane, so
     * the logged value and the committed displacement stay one number.</p>
     */
    static double requiredSupportCorrection(
            CharacterDimensions dimensions,
            CollisionBody installedBody,
            GravityFrame proposedFrame,
            RestingContactSnapshot resting
    ) {
        if (resting == null
                || !resting.planePreservationEligible()
                || resting.supportBlock().isEmpty()) {
            return Double.NaN;
        }
        Vec3d normal = resting.normal();
        if (!Double.isFinite(normal.lengthSquared())
                || Math.abs(normal.lengthSquared() - 1.0D)
                > UNIT_NORMAL_EPSILON) {
            return Double.NaN;
        }
        return supportPlaneCorrection(
                dimensions,
                installedBody,
                installedBody.center(),
                proposedFrame,
                normal
        );
    }

    /**
     * Projected-radius difference between the proposed-axis and the installed
     * character bodies around the same physical center.
     */
    static double supportPlaneCorrection(
            CharacterDimensions dimensions,
            CollisionBody installedBody,
            Vec3d center,
            GravityFrame proposedFrame,
            Vec3d normal
    ) {
        double currentRadius =
                CollisionBodyProjection.radiusAlong(installedBody, normal);
        CollisionBody proposedAtCenter = characterBodyAtCenter(
                dimensions,
                center,
                proposedFrame.up()
        );
        double proposedRadius =
                CollisionBodyProjection.radiusAlong(proposedAtCenter, normal);
        return proposedRadius - currentRadius;
    }

    /**
     * P-preserving construction policy: update the collision axis while
     * retaining the authoritative position exactly.
     */
    public static KinematicPose posePreservingPositionAnchor(
            CharacterDimensions dimensions,
            Vec3d positionAnchor,
            GravityFrame frame
    ) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(positionAnchor, "positionAnchor");
        Objects.requireNonNull(frame, "frame");
        Vec3d center = KinematicPose.characterCenter(
                positionAnchor,
                dimensions.height()
        );
        return new KinematicPose(
                positionAnchor,
                center,
                frame,
                characterBodyAtCenter(dimensions, center, frame.up())
        );
    }

    /**
     * Exact axial character body at a physical center.
     *
     * <p>Wide/short dimensions are rejected by
     * {@link CharacterDimensionPolicy} exactly as the target adapter does.</p>
     */
    public static CharacterCapsule characterBodyAtCenter(
            CharacterDimensions dimensions,
            Vec3d center,
            Vec3d up
    ) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(up, "up");
        CharacterDimensionPolicy.requireCapsule(
                dimensions.width(),
                dimensions.height()
        );
        return CharacterCapsule.fromDimensions(
                center,
                dimensions.width(),
                dimensions.height(),
                up
        );
    }

    /**
     * C -> P, matching the canonical character geometry: the network anchor is
     * the physical center minus world-Y half height.
     */
    private static Vec3d positionAnchorFromCenter(
            Vec3d center,
            double height
    ) {
        return center.add(0.0D, -height * 0.5D, 0.0D);
    }

    private static OperationPoseTransition geometryUnchanged(
            GravityFrame installedFrame,
            String rejectionReason,
            OperationFrameDiagnostics diagnostics
    ) {
        return new OperationPoseTransition(
                Objects.requireNonNull(installedFrame, "installed frame"),
                null,
                GeometryTransitionKind.DEFERRED,
                Vec3d.ZERO,
                rejectionReason,
                false,
                Objects.requireNonNull(diagnostics, "diagnostics")
        );
    }

    /**
     * Deadband outcome: the installed occupancy is retained and the
     * operation's fresh evidence is published as the selected frame.
     */
    private static OperationPoseTransition geometryDeadbandRetained(
            GravityFrame refreshedFrame,
            OperationFrameDiagnostics diagnostics
    ) {
        return new OperationPoseTransition(
                Objects.requireNonNull(refreshedFrame, "refreshed frame"),
                null,
                GeometryTransitionKind.UNCHANGED,
                Vec3d.ZERO,
                null,
                false,
                Objects.requireNonNull(diagnostics, "diagnostics")
        );
    }

    private static OperationPoseTransition acceptedTransition(
            GravityFrame selectedFrame,
            KinematicPose candidatePose,
            GeometryTransitionKind kind,
            Vec3d positionCorrection,
            OperationFrameDiagnostics diagnostics
    ) {
        return new OperationPoseTransition(
                Objects.requireNonNull(selectedFrame, "selected frame"),
                Objects.requireNonNull(candidatePose, "candidate pose"),
                Objects.requireNonNull(kind, "transition kind"),
                Objects.requireNonNull(positionCorrection, "positionCorrection"),
                null,
                true,
                Objects.requireNonNull(diagnostics, "diagnostics")
        );
    }

    /**
     * True when a proposed frame's collision axis differs enough from
     * the installed collision axis to rebuild the axial collision geometry.
     *
     * <p>The threshold uses the squared cross-product so small angles never
     * round through {@code acos}; exact gravity force direction stays
     * decoupled from this collision-geometry decision.</p>
     */
    public static boolean shouldUpdateCollisionGeometryFrame(
            GravityFrame installed,
            GravityFrame proposed
    ) {
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(proposed, "proposed");
        Vec3d first = installed.down();
        Vec3d second = proposed.down();
        double dot = Math.max(-1.0D, Math.min(1.0D, first.dot(second)));
        if (dot < 0.0D) {
            return true;
        }
        Vec3d cross = first.cross(second);
        double sine = Math.sin(COLLISION_AXIS_ANGULAR_EPSILON_RADIANS);
        return cross.lengthSquared() > sine * sine;
    }

    /**
     * Exact angular distance between two collision axes in radians.
     *
     * <p>This is a real measurement, not a conservative classification: it is
     * defined on {@code [0, PI]}, returns {@code PI} only for an exact
     * reversal, and reports {@code 91.6} degrees as {@code ~1.599} radians
     * instead of {@code PI}. The {@code atan2(sin, dot)} form stays stable
     * across the whole range, including small angles.</p>
     */
    public static double exactFrameAngularDistanceRadians(
            GravityFrame installed,
            GravityFrame proposed
    ) {
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(proposed, "proposed");
        Vec3d first = installed.down();
        Vec3d second = proposed.down();
        double dot = Math.max(-1.0D, Math.min(1.0D, first.dot(second)));
        double sin = first.cross(second).length();
        return Math.atan2(sin, dot);
    }

}
