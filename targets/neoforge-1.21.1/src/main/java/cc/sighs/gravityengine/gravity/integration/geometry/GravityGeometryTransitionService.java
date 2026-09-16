package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.CollisionBodyProjection;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.minecraft.geometry.KinematicPose;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;
import java.util.Objects;

/**
 * Collision-safe geometry transition service.
 * Constructs, validates, recovers, and commits candidate bodies.
 */
public final class GravityGeometryTransitionService {
    private GravityGeometryTransitionService() {}

    /** Standalone size changes preserve network P, as Vanilla does. Changing
     * h changes C along world Y and is a geometry transaction, never a second
     * movement. Pose-fit keeps ordinary entities strict too; rotation/resize
     * rejects both new and deepened overlap without packet tolerance. */
    public static boolean dimensionsChangeLegal(Entity entity, CollisionBody oldBody,
                                                Vec3 positionAnchor, GravityFrame frame) {
        var dimensions = GravityEntityGeometry.dimensions(entity);
        if (CharacterDimensionPolicy.decide(dimensions.width(), dimensions.height())
                != CharacterDimensionPolicy.Decision.CAPSULE) return false;
        CollisionBody candidate = GravityEntityGeometry.candidateBody(
                entity, dimensions, positionAnchor, frame);
        if (candidate.equals(oldBody) || entity.noPhysics) return true;
        try {
            Aabb3d bounds = oldBody.enclosingAabb().union(candidate.enclosingAabb());
            var domain = CollisionCaptureDomain.forTranslation(bounds, new Vector3d(), entity.maxUpStep());
            long tick = entity.level().getGameTime();
            var scene = MinecraftCollisionSceneCapture.capture(entity, domain, tick, 0,
                    cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(tick, 0),
                    new CollisionWorkTracker(CollisionWorkBudget.defaults()));
            return BodyCollisionDelta.compare(oldBody, candidate,
                    (body, movement) -> scene.queryPoseFit(body), false).legal();
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return false;
        }
    }

    /**
     * Collision-axis deadband in radians.
     *
     * <p>Gravity sampling and the exact acceleration direction always use the
     * live {@code GravitySample}; only the axial collision geometry may
     * keep the previously installed collision axis.  The band is derived from
     * contact-scale geometry: with a character half-diagonal below one block
     * and the ground probe at {@code 8 * CONTACT_SKIN}, a one-radian error
     * budget of {@code 1e-5} moves a resting rim by at most ~10
     * micrometres, i.e. one contact-skin decade above the probe band.  Larger
     * legitimate changes are owned by
     * {@link GeometryTransitionKind#PRESERVE_SUPPORT} instead.</p>
     */
    public static final double COLLISION_AXIS_ANGULAR_EPSILON_RADIANS =
            1.0E-5D;

    /**
     * Maximum installed-to-proposed collision-axis evolution that a resting
     * single-contact support may own through PRESERVE_SUPPORT.
     *
     * <p>This is a single-tick collision-axis continuity policy, not a
     * gameplay turn rate: only small, continuous radial axis evolution may
     * re-anchor onto the previous support plane.  Larger discontinuous
     * changes (teleports between gravity sources, abrupt direction flips)
     * fall back to the ordinary legality/deferred path so the old plane can
     * never pull the body across a 45-180 degree flip.  The
     * deadband ({@link #COLLISION_AXIS_ANGULAR_EPSILON_RADIANS}) remains the
     * no-geometry-change boundary; values above it and at or below this
     * constant are the continuous-evolution band that PRESERVE_SUPPORT owns.
     * Three degrees covers ordinary per-tick radial drift up to a small-planet
     * sprint while remaining a small-angle policy.</p>
     */
    public static final double MAX_PRESERVE_SUPPORT_AXIS_DELTA_RADIANS =
            Math.toRadians(3.0D);

    public enum Status { UNCHANGED, APPLIED, RECOVERED, DEFERRED, FAILED }

    public record Result(Status status, boolean geometryChanged, Vec3 recoveryMovement) {
        public static final Result UNCHANGED = new Result(Status.UNCHANGED, false, Vec3.ZERO);
        public static final Result DEFERRED = new Result(Status.DEFERRED, false, Vec3.ZERO);
        public static Result applied() { return new Result(Status.APPLIED, true, Vec3.ZERO); }
        public static Result recovered(Vec3 m) { return new Result(Status.RECOVERED, true, m); }
        public static Result failed() { return new Result(Status.FAILED, false, Vec3.ZERO); }
    }

    /**
     * Outcome of one PRESERVE_SUPPORT eligibility decision.  A rejected
     * candidate carries a stable {@code fallbackReason} for runtime debug
     * (for example {@code FRAME_DELTA_TOO_LARGE} or
     * {@code CORRECTION_TOO_LARGE}); the caller falls back to the ordinary
     * legality/deferred path and never clamps a rejected correction.
     */
    record SupportPreserveOutcome(
            KinematicPose pose,
            String fallbackReason
    ) {
        SupportPreserveOutcome {
            if (pose == null) {
                Objects.requireNonNull(
                        fallbackReason, "fallbackReason");
            } else {
                Objects.requireNonNull(pose, "pose");
                if (fallbackReason != null) {
                    throw new IllegalArgumentException(
                            "accepted support-preserve outcome has a "
                                    + "fallback reason");
                }
            }
        }

        static SupportPreserveOutcome accepted(KinematicPose pose) {
            return new SupportPreserveOutcome(pose, null);
        }

        static SupportPreserveOutcome rejected(String fallbackReason) {
            return new SupportPreserveOutcome(null, fallbackReason);
        }

        boolean preserved() {
            return this.pose != null;
        }
    }

    /**
     * Outcome of normal operation-pose preparation.  A preparation
     * displacement is a real support-preserving geometry correction selected
     * before collision; it is neither velocity nor collision recovery.
     */
    public record OperationFrameResult(
            GravityFrame selectedFrame,
            Status status,
            boolean geometryChanged,
            Vec3 preparationDisplacement,
            GeometryTransitionKind transitionKind
    ) {
        public OperationFrameResult {
            Objects.requireNonNull(selectedFrame, "selectedFrame");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(preparationDisplacement, "preparationDisplacement");
            Objects.requireNonNull(transitionKind, "transitionKind");
        }
    }

    /**
     * The explicit geometric-pose handoff decision for one outermost
     * character movement operation.
     *
     * <p>A transition kind names which candidate anchor policy owns the frame
     * change. It is deliberately not derivable from "the geometry changed":
     * a {@link #DIRECT_AT_ANCHOR} replacement and a
     * {@link #PRESERVE_SUPPORT} re-anchor both rebuild the exact body, but only
     * the latter has revalidated a real support plane and may carry the
     * previous completed endpoint forward.</p>
     */
    public enum GeometryTransitionKind {

        /**
         * Collision-axis deadband: the installed occupancy is worth keeping and
         * only the environmental evidence is refreshed.
         */
        UNCHANGED,

        /**
         * The exact body at the authoritative position anchor under the
         * proposed axis is legal without any support-plane correction.
         */
        DIRECT_AT_ANCHOR,

        /**
         * The proposed axis is legal only after a bounded correction that keeps
         * the previous trusted single-face support plane.
         */
        PRESERVE_SUPPORT,

        /** No legal candidate exists; the installed representation is retained. */
        DEFERRED
    }

    /**
     * Whether the operation invoking pose preparation owns the position anchor.
     *
     * <p>This separates "is this candidate geometrically legal" from "may this
     * call boundary move the authoritative P". An externally owned translation
     * proposal (Vanilla's packet loop is the canonical example) must not be
     * re-anchored mid-preparation: its owner already captured the anchor it
     * translates from and to.</p>
     */
    public enum PositionAuthorityPolicy {

        /** The operation owns P and may install a bounded support re-anchor. */
        OPERATION_MAY_REANCHOR,

        /**
         * An external owner captured P. Preparation must leave P untouched;
         * candidates that require a positional correction stay uninstalled.
         */
        EXTERNAL_POSITION_ANCHOR
    }

    /**
     * Whether one candidate body's exact occupancy could be proven legal
     * against the operation's frozen scene.
     *
     * <p>{@link #INDETERMINATE} is a real third answer, never a soft
     * "probably legal": a candidate that leaves the captured envelope or
     * exhausts the shared work budget cannot be installed or manufacture
     * support. Rejecting a handoff does not itself cancel movement, but an
     * exhausted hard-collision budget remains exhausted for the downstream
     * movement owner; this boundary never resets it.</p>
     */
    public enum PoseFitStatus {
        /** The exact body has no meaningful penetration in the frozen scene. */
        LEGAL,
        /** The exact body really penetrates a captured obstacle. */
        ILLEGAL,
        /**
         * The frozen scene could not answer: the candidate left the captured
         * envelope ({@code SCENE_COVERAGE}) or the shared work budget was
         * exhausted ({@code WORK_BUDGET}).
         */
        INDETERMINATE,
        /** The layer was never reached (deadband, authority gate, earlier rejection). */
        NOT_ATTEMPTED
    }

    /** Stable reason for an indeterminate fit caused by leaving the captured envelope. */
    static final String SCENE_COVERAGE_REASON = "SCENE_COVERAGE";
    /** Stable reason for an indeterminate fit caused by the shared work budget. */
    static final String WORK_BUDGET_REASON = "WORK_BUDGET";

    /**
     * One pose-fit evaluation: the status plus the exact obstacles that were
     * actually materialized for it.
     */
    record PoseFit(
            PoseFitStatus status,
            String reason,
            List<CollisionObstacle> obstacles
    ) {
        PoseFit {
            Objects.requireNonNull(status, "status");
            obstacles = List.copyOf(obstacles);
            if (status == PoseFitStatus.INDETERMINATE) {
                Objects.requireNonNull(reason, "reason");
            }
        }

        static PoseFit legal(List<CollisionObstacle> obstacles) {
            return new PoseFit(PoseFitStatus.LEGAL, null, obstacles);
        }

        static PoseFit illegal(List<CollisionObstacle> obstacles) {
            return new PoseFit(PoseFitStatus.ILLEGAL, "POSE_ILLEGAL", obstacles);
        }

        static PoseFit indeterminate(String reason) {
            return new PoseFit(
                    PoseFitStatus.INDETERMINATE, reason, List.of()
            );
        }

        boolean legal() {
            return this.status == PoseFitStatus.LEGAL;
        }

        boolean indeterminate() {
            return this.status == PoseFitStatus.INDETERMINATE;
        }
    }

    /**
     * Decision evidence produced while planning operation-pose preparation.
     * It exists purely so one debug line can describe the whole decision
     * without repeating any collision or support query.
     *
     * @param directFit geometric fit of the same-anchor candidate
     * @param directRejectReason stable reason the direct candidate did not win
     * @param supportCandidateConstructed whether the support-preserving pose
     *        was actually built from a trusted resting snapshot
     * @param supportFit geometric fit of that support-preserving pose
     * @param supportRejectReason stable reason the support candidate did not win
     * @param positionAuthorityAllowed whether this operation may install a
     *        positional re-anchor at all
     * @param requiredCorrection signed support-normal correction the
     *        support-preserving candidate requires, or {@code NaN} when it was
     *        never computed. It is the same signed quantity the commit applies.
     * @param maxCorrection derived correction bound for this body
     */
    record OperationFrameDiagnostics(
            PoseFitStatus directFit,
            String directRejectReason,
            boolean supportCandidateConstructed,
            PoseFitStatus supportFit,
            String supportRejectReason,
            boolean positionAuthorityAllowed,
            double requiredCorrection,
            double maxCorrection
    ) {
        OperationFrameDiagnostics {
            Objects.requireNonNull(directFit, "directFit");
            Objects.requireNonNull(supportFit, "supportFit");
        }
    }

    /**
     * Immutable result of Phase A (planning).  Planning evaluates candidates
     * against the operation's already-captured scene and never writes to the
     * entity.
     *
     * @param selectedFrame frame whose collision axis is currently installed;
     *                      equals {@code candidatePose.frame()} whenever a
     *                      geometry commit is planned
     * @param candidatePose pose to install, or {@code null} when the installed
     *                      representation is retained
     * @param kind          which anchor policy produced the decision
     * @param geometryChanged whether the commit rebuilds the exact body
     * @param rejectionReason stable diagnostic reason for the first candidate
     *                      that failed, or {@code null} on success
     * @param positionCorrection P displacement the candidate requires, zero
     *                      when no commit is planned
     * @param diagnostics   candidate-fit and support evidence for diagnostics
     */
    public record OperationPoseTransition(
            GravityFrame selectedFrame,
            KinematicPose candidatePose,
            GeometryTransitionKind kind,
            Vec3 positionCorrection,
            String rejectionReason,
            boolean geometryChanged,
            OperationFrameDiagnostics diagnostics
    ) {
        public OperationPoseTransition {
            Objects.requireNonNull(selectedFrame, "selectedFrame");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(positionCorrection, "positionCorrection");
            Objects.requireNonNull(diagnostics, "diagnostics");
            if (kind == GeometryTransitionKind.DEFERRED
                    || kind == GeometryTransitionKind.UNCHANGED) {
                if (candidatePose != null) {
                    throw new IllegalArgumentException(
                            kind + " transition cannot plan a pose commit"
                    );
                }
            } else if (candidatePose == null) {
                throw new IllegalArgumentException(
                        kind + " transition must carry the pose it installs"
                );
            }
        }

        public boolean installsPose() {
            return this.kind != GeometryTransitionKind.UNCHANGED
                    && this.kind != GeometryTransitionKind.DEFERRED;
        }

        OperationFrameResult toFrameResult() {
            if (this.kind == GeometryTransitionKind.UNCHANGED) {
                return new OperationFrameResult(
                        this.selectedFrame,
                        Status.UNCHANGED,
                        false,
                        Vec3.ZERO,
                        GeometryTransitionKind.UNCHANGED
                );
            }
            if (this.kind == GeometryTransitionKind.DEFERRED) {
                return new OperationFrameResult(
                        this.selectedFrame,
                        Status.DEFERRED,
                        false,
                        Vec3.ZERO,
                        GeometryTransitionKind.DEFERRED
                );
            }
            return new OperationFrameResult(
                    this.selectedFrame,
                    Status.APPLIED,
                    this.geometryChanged,
                    this.positionCorrection,
                    this.kind
            );
        }
    }

    // -----------------------------------------------------------------
    // P0-4: Operation-frame preparation
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
     * Operation-scene variant with an explicit position-authority contract.
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
        Objects.requireNonNull(positionAuthority, "positionAuthority");
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(proposedFrame, "proposedFrame");

        var runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().runtime();
        GravityFrame installedFrame = installedFallback(runtime);
        GravityEntityGeometry.requireGeometryMatchesFrame(
                entity, installedFrame, "operation-frame preparation"
        );
        EntityDimensions dimensions = GravityEntityGeometry.dimensions(entity);
        CollisionBody installedBody =
                GravityEntityGeometry.body(entity, installedFrame);
        Vec3 installedAnchor = entity.position();

        double exactFrameDeltaRadians =
                exactFrameAngularDistanceRadians(installedFrame, proposedFrame);
        boolean deadbandHit = !shouldUpdateCollisionGeometryFrame(
                installedFrame, proposedFrame
        );

        OperationPoseTransition transition = planOperationFrame(
                entity,
                runtime,
                proposedFrame,
                scene,
                positionAuthority,
                queryContext,
                installedFrame,
                installedBody,
                dimensions,
                installedAnchor,
                exactFrameDeltaRadians,
                deadbandHit
        );

        logGeometryTransitionDecision(
                entity,
                installedFrame,
                proposedFrame,
                exactFrameDeltaRadians,
                deadbandHit,
                positionAuthority,
                transition
        );

        return commitOperationPoseTransition(
                entity,
                installedFrame,
                installedAnchor,
                transition
        );
    }

    /**
     * Phase A: evaluate every candidate against the operation's frozen scene
     * and return one immutable decision.
     *
     * <p>This method must never write entity state: no {@code setPosRaw}, no
     * installed-axis update, no velocity/ground/fall-distance/support change.
     * Candidate evaluation order is the ownership order, and the first legal
     * candidate wins:</p>
     *
     * <ol>
     *   <li>deadband: keep the installed occupancy and refresh evidence only;</li>
     *   <li>{@link GeometryTransitionKind#DIRECT_AT_ANCHOR}: same authoritative
     *       P, new exact axis, no support-plane dependency;</li>
     *   <li>{@link GeometryTransitionKind#PRESERVE_SUPPORT}: bounded reference
     *       re-anchor onto the previous trusted static single-face support;</li>
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
    static OperationPoseTransition planOperationFrame(
            Entity entity,
            cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState runtime,
            GravityFrame proposedFrame,
            CollisionScene scene,
            PositionAuthorityPolicy positionAuthority,
            ObbQueryContext queryContext,
            GravityFrame installedFrame,
            CollisionBody installedBody,
            EntityDimensions dimensions,
            Vec3 installedAnchor,
            double exactFrameDeltaRadians,
            boolean deadbandHit
    ) {
        double maxCorrection = maxPreserveSupportCorrection(installedBody);
        boolean authorityAllowsReanchor =
                positionAuthority
                        == PositionAuthorityPolicy.OPERATION_MAY_REANCHOR;
        if (deadbandHit) {
            // The collision axis stays installed; fresh tangent/strength
            // evidence belongs to this operation and does not revise physical
            // occupancy.
            GravityFrame refreshedFrame =
                    runtime.frameForInstalledAxis(proposedFrame);
            return geometryDeadbandRetained(
                    refreshedFrame,
                    new OperationFrameDiagnostics(
                            PoseFitStatus.NOT_ATTEMPTED, null,
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            authorityAllowsReanchor, Double.NaN, maxCorrection
                    )
            );
        }

        RestingContactSnapshot resting =
                runtime.restingContactSnapshot();
        KinematicPose anchoredPose = KinematicPose.preservePositionAnchor(
                dimensions, installedAnchor, proposedFrame
        );

        PoseFit directFit =
                evaluatePoseFit(scene, anchoredPose.body(), queryContext);
        if (directFit.legal()) {
            return acceptedTransition(
                    proposedFrame,
                    anchoredPose,
                    GeometryTransitionKind.DIRECT_AT_ANCHOR,
                    Vec3.ZERO,
                    new OperationFrameDiagnostics(
                            directFit.status(), null,
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            authorityAllowsReanchor, Double.NaN, maxCorrection
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
                            authorityAllowsReanchor, Double.NaN, maxCorrection
                    )
            );
        }
        if (!authorityAllowsReanchor) {
            /*
             * The direct endpoint is really illegal and this operation may not
             * move P, so no positional candidate can ever be installed. Stop
             * here: querying feet support or a candidate pose fit would consume
             * the shared support/narrow-phase budget of the ordinary movement
             * that still has to run, and it could not change the decision.
             */
            return geometryUnchanged(
                    installedFrame,
                    "POSITION_AUTHORITY_FORBIDS_REANCHOR",
                    new OperationFrameDiagnostics(
                            directFit.status(), directFit.reason(),
                            false, PoseFitStatus.NOT_ATTEMPTED, null,
                            false, Double.NaN, maxCorrection
                    )
            );
        }

        SupportPreservePlan supportPlan = planSupportPreservingPose(
                entity,
                proposedFrame,
                scene,
                queryContext,
                dimensions,
                installedBody,
                MinecraftGeometryAdapter.toMinecraft(installedBody.center()),
                resting,
                exactFrameDeltaRadians,
                installedAnchor
        );
        if (supportPlan.pose() != null) {
            Vec3 supportCorrection = supportPlan.pose()
                    .positionAnchor()
                    .subtract(installedAnchor);
            return acceptedTransition(
                    proposedFrame,
                    supportPlan.pose(),
                    GeometryTransitionKind.PRESERVE_SUPPORT,
                    supportCorrection,
                    new OperationFrameDiagnostics(
                            directFit.status(), directFit.reason(),
                            true, supportPlan.fitStatus(), null,
                            true,
                            supportCorrection.dot(resting.normal()),
                            maxCorrection
                    )
            );
        }

        return geometryUnchanged(
                installedFrame,
                supportPlan.reason(),
                new OperationFrameDiagnostics(
                        directFit.status(), directFit.reason(),
                        supportPlan.candidateConstructed(),
                        supportPlan.fitStatus(),
                        supportPlan.reason(),
                        true,
                        supportPlan.requiredCorrection(),
                        maxCorrection
                )
        );
    }

    /**
     * Phase B: install at most one planned pose.
     *
     * <p>A failed or unplanned candidate never reaches this method, so no
     * half-updated geometry or runtime state can exist.</p>
     */
    static OperationFrameResult commitOperationPoseTransition(
            Entity entity,
            GravityFrame installedFrame,
            Vec3 installedAnchor,
            OperationPoseTransition transition
    ) {
        Objects.requireNonNull(transition, "transition");
        if (!transition.installsPose()) {
            return transition.toFrameResult();
        }
        KinematicPose proposedPose = transition.candidatePose();
        GravityFrame proposedFrame = proposedPose.frame();
        GravityEntityGeometry.commitCustomBody(
                entity,
                proposedFrame,
                proposedPose.positionAnchor(),
                proposedPose.body()
        );
        Vec3 positionCorrection =
                proposedPose.positionAnchor().subtract(installedAnchor);
        if (transition.kind() == GeometryTransitionKind.DIRECT_AT_ANCHOR
                && positionCorrection.lengthSqr()
                > GravityEntityGeometry.GEOMETRY_FRAME_EPSILON_SQUARED) {
            throw new IllegalStateException(
                    "direct frame handoff must not translate the "
                            + "authoritative position anchor: installed="
                            + installedAnchor + ", proposed="
                            + proposedPose.positionAnchor()
                            + ", frame=" + proposedFrame
            );
        }
        if (transition.kind() == GeometryTransitionKind.PRESERVE_SUPPORT) {
            GravityDebugLog.movement(entity, "SUPPORT", "anchor",
                    "up=%s positionCorrection=%s physicalSupport=true "
                            + "stableGround=true footEligible=true "
                            + "tractionEligible=true transitionKind=%s",
                    GravityDebugLog.exactVec(proposedFrame.up()),
                    GravityDebugLog.vec(positionCorrection),
                    transition.kind());
        }
        return new OperationFrameResult(
                proposedFrame,
                Status.APPLIED,
                transition.geometryChanged(),
                positionCorrection,
                transition.kind()
        );
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
     * diagnostic reason.  The candidate is evaluated against the same frozen
     * scene and work budget as the direct candidate.
     */
    private static SupportPreservePlan planSupportPreservingPose(
            Entity entity,
            GravityFrame proposedFrame,
            CollisionScene scene,
            ObbQueryContext queryContext,
            EntityDimensions dimensions,
            CollisionBody oldBody,
            Vec3 oldCenter,
            RestingContactSnapshot resting,
            double exactFrameDeltaRadians,
            Vec3 installedAnchor
    ) {
        double requiredCorrection = requiredSupportCorrection(
                dimensions, oldBody, proposedFrame, resting
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
        SupportPreserveOutcome candidate =
                supportPreservingPoseCandidate(
                        entity, dimensions, oldBody, oldCenter,
                        proposedFrame, resting
                );
        if (!candidate.preserved()) {
            return SupportPreservePlan.rejected(
                    false, PoseFitStatus.NOT_ATTEMPTED,
                    candidate.fallbackReason(), requiredCorrection
            );
        }
        KinematicPose reanchored = candidate.pose();
        PoseFit fit = evaluatePoseFit(scene, reanchored.body(), queryContext);
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
                reanchored, proposedFrame, resting, scene, queryContext
        );
        if (supportRejection != null) {
            return SupportPreservePlan.rejected(
                    true, fit.status(), supportRejection, requiredCorrection
            );
        }
        Vec3 plannedCorrection = reanchored.positionAnchor()
                .subtract(installedAnchor);
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
                        >= cc.sighs.gravityengine.gravity.collision
                        .TerrainTraversalPolicy.MIN_CONTINUOUS_SUPPORT_UP_DOT
                        && Objects.equals(
                                face.identity().block(),
                                resting.supportBlock().orElse(null))
                        && face.normal().dot(
                                MinecraftGeometryAdapter.toJoml(
                                        resting.normal(), new Vector3d()))
                        > 1 - CollisionTolerances.GEOMETRIC_AXIS_EPSILON
        );
        return trustedFace ? null : "SUPPORT_REVALIDATION_FAILED";
    }

    /**
     * Pose-fit evaluation of one candidate body against the operation's frozen
     * scene, fail-closed.
     *
     * <p>This is the only place a transition candidate materializes pose-fit obstacles,
     * and it never re-reads the live world: a coverage failure and an exhausted
     * shared work budget are reported as
     * {@link PoseFitStatus#INDETERMINATE} instead of being reinterpreted as
     * "nothing penetrated the partial list". Only the explicit
     * {@link CollisionSceneCoverageException} of a candidate fit is captured;
     * real programming errors still propagate.</p>
     *
     * <p>The query uses {@link CollisionScene#queryPoseFit} semantics: a
     * geometry installation asks whether the complete new pose may exist, so a
     * captured pose-strict ordinary entity is part of that fit. The ordinary
     * movement route keeps its own entity-contact policy.</p>
     */
    static PoseFit evaluatePoseFit(
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

        boolean penetrates = GravityCollisionEngine
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

    /**
     * Signed support-normal correction the PRESERVE_SUPPORT candidate requires:
     * the difference between the projected support radius of the proposed-axis
     * capsule and of the installed capsule.  This is diagnostic evidence only;
     * it never authorizes a candidate and it never runs a collision query.
     *
     * <p>It is the same signed quantity the candidate derives as
     * {@code currentSeparation - proposedSeparation}: the accepted resting
     * separation is preserved rather than collapsed onto the witness plane, so
     * the logged value and the committed displacement stay one number.</p>
     */
    private static double requiredSupportCorrection(
            EntityDimensions dimensions,
            CollisionBody installedBody,
            GravityFrame proposedFrame,
            RestingContactSnapshot resting
    ) {
        if (resting == null
                || !resting.planePreservationEligible()
                || resting.supportBlock().isEmpty()) {
            return Double.NaN;
        }
        Vec3 normal = resting.normal();
        if (!Double.isFinite(normal.lengthSqr())
                || Math.abs(normal.lengthSqr() - 1.0D) > 1.0E-6D) {
            return Double.NaN;
        }
        return supportPlaneCorrection(
                dimensions,
                installedBody,
                MinecraftGeometryAdapter.toMinecraft(installedBody.center()),
                proposedFrame,
                normal
        );
    }

    /**
     * Projected-radius difference between the proposed-axis and the installed
     * character bodies around the same physical center.
     */
    private static double supportPlaneCorrection(
            EntityDimensions dimensions,
            CollisionBody installedBody,
            Vec3 center,
            GravityFrame proposedFrame,
            Vec3 normal
    ) {
        double currentRadius =
                CollisionBodyProjection.radiusAlong(installedBody, normal);
        CollisionBody proposedAtCenter =
                GravityEntityGeometry.characterBodyAtCenter(
                        dimensions.width(), dimensions.height(),
                        center, proposedFrame.up());
        double proposedRadius =
                CollisionBodyProjection.radiusAlong(proposedAtCenter, normal);
        return proposedRadius - currentRadius;
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
                Vec3.ZERO,
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
                Vec3.ZERO,
                null,
                false,
                Objects.requireNonNull(diagnostics, "diagnostics")
        );
    }

    private static OperationPoseTransition acceptedTransition(
            GravityFrame selectedFrame,
            KinematicPose candidatePose,
            GeometryTransitionKind kind,
            Vec3 positionCorrection,
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
        Vec3 first = installed.down();
        Vec3 second = proposed.down();
        double dot = Math.max(-1.0D, Math.min(1.0D, first.dot(second)));
        if (dot < 0.0D) {
            return true;
        }
        Vec3 cross = first.cross(second);
        double sine = Math.sin(COLLISION_AXIS_ANGULAR_EPSILON_RADIANS);
        return cross.lengthSqr() > sine * sine;
    }

    /**
     * Exact angular distance between two collision axes in radians.
     *
     * <p>This is a real measurement, not a conservative classification: it is defined
     * on {@code [0, PI]}, returns {@code PI} only for an exact reversal, and
     * reports {@code 91.6} degrees as {@code ~1.599} radians instead of
     * {@code PI}. The {@code atan2(sin, dot)} form stays stable across the
     * whole range, including small angles.</p>
     */
    public static double exactFrameAngularDistanceRadians(
            GravityFrame installed,
            GravityFrame proposed
    ) {
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(proposed, "proposed");
        Vec3 first = installed.down();
        Vec3 second = proposed.down();
        double dot = Math.max(-1.0D, Math.min(1.0D, first.dot(second)));
        double sin = first.cross(second).length();
        return Math.atan2(sin, dot);
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
     * correction.  Legality is verified by the caller's commit tail.</p>
     */
    static SupportPreserveOutcome supportPreservingPoseCandidate(
            Entity entity,
            EntityDimensions dimensions,
            CollisionBody oldBody,
            Vec3 oldCenter,
            GravityFrame proposedFrame,
            RestingContactSnapshot resting
    ) {
        Objects.requireNonNull(entity, "entity");
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
        long gameTick = entity.level().getGameTime();
        if (resting.gameTick() > gameTick
                || gameTick - resting.gameTick() > 1L) {
            return SupportPreserveOutcome.rejected(
                    "STALE_RESTING_SNAPSHOT");
        }
        Vec3 normal = resting.normal();
        if (!Double.isFinite(normal.lengthSqr())
                || Math.abs(normal.lengthSqr() - 1.0D) > 1.0E-6D) {
            return SupportPreserveOutcome.rejected(
                    "INVALID_SUPPORT_NORMAL");
        }
        if (!supportCompatibleWithFrame(resting, proposedFrame)) {
            return SupportPreserveOutcome.rejected(
                    "SUPPORT_NOT_WALKABLE");
        }
        double currentRadius =
                CollisionBodyProjection.radiusAlong(oldBody, normal);
        Vec3 currentSupportPoint =
                oldCenter.subtract(normal.scale(currentRadius));
        double currentSeparation = currentSupportPoint
                .subtract(resting.contactPoint())
                .dot(normal);
        if (currentSeparation < -PENETRATION_GAP_LIMIT
                || currentSeparation > RESTING_GAP_LIMIT) {
            return SupportPreserveOutcome.rejected(
                    "RESTING_GAP");
        }
        CollisionBody proposedAtOldCenter = GravityEntityGeometry.characterBodyAtCenter(
                dimensions.width(), dimensions.height(), oldCenter, proposedFrame.up());
        double proposedRadius = CollisionBodyProjection.radiusAlong(
                proposedAtOldCenter, normal);
        Vec3 proposedSupportPoint =
                oldCenter.subtract(normal.scale(proposedRadius));
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
            if (GravityDebugLog.ENABLED) {
                GravityDebugLog.log(
                        entity,
                        "geometry-frame",
                        "decision=CORRECTION_TOO_LARGE supportNormal=%s "
                                + "witness=%s "
                                + "supportUpDot=%.9f "
                                + "currentSeparation=%.9f "
                                + "proposedSeparation=%.9f "
                                + "requiredSupportCorrection=%.9f "
                                + "maxAllowedSupportCorrection=%.9f "
                                + "fallback=DEFERRED",
                        GravityDebugLog.vec(normal),
                        GravityDebugLog.vec(resting.contactPoint()),
                        normal.dot(proposedFrame.up()),
                        currentSeparation,
                        proposedSeparation,
                        correction,
                        maxCorrection
                );
            }
            return SupportPreserveOutcome.rejected(
                    "CORRECTION_TOO_LARGE");
        }
        Vec3 reanchoredCenter = oldCenter.add(
                normal.scale(correction));
        if (Math.abs(correction)
                <= GravityEntityGeometry.GEOMETRY_FRAME_EPSILON) {
            /*
             * The re-anchored plane is the pose the body already occupies:
             * return the exact P-preserving pose so a zero correction cannot
             * introduce a numerically different anchor.
             */
            return SupportPreserveOutcome.accepted(
                    KinematicPose.preservePositionAnchor(
                            dimensions,
                            GravityEntityGeometry.positionAnchorFromBodyCenter(
                                    oldCenter, dimensions.height()),
                            proposedFrame));
        }
        return SupportPreserveOutcome.accepted(
                KinematicPose.preservePositionAnchor(
                        dimensions,
                        GravityEntityGeometry.positionAnchorFromBodyCenter(
                                reanchoredCenter, dimensions.height()),
                        proposedFrame));
    }

    /** Stable-standing continuity uses the same slope boundary as foot grounding.
     * A steep physical face retains collision authority, never anchoring authority. */
    static boolean supportCompatibleWithFrame(
            RestingContactSnapshot resting,
            GravityFrame proposedFrame
    ) {
        Objects.requireNonNull(resting, "resting");
        Objects.requireNonNull(proposedFrame, "proposedFrame");
        return ContinuousSupportPolicy.classify(
                MinecraftGeometryAdapter.toJoml(
                        resting.normal(), new Vector3d()),
                proposedFrame) == ContinuousSupportPolicy.Classification.FLOOR;
    }

    /**
     * Dynamic sanity bound for the PRESERVE_SUPPORT positional correction.
     *
     * <p>Even inside the small frame-delta band a malformed or stale witness
     * could demand an abnormal translation.  The allowed correction is
     * derived from the body's enclosing bounding radius times the sine of the
     * maximum frame delta (the largest corner displacement of a pure
     * rotation), plus the ground probe and contact skin tolerances.  A
     * correction beyond this bound abandons the support-preserving candidate;
     * it is never clamped and committed.</p>
     */
    static double maxPreserveSupportCorrection(CollisionBody body) {
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
        if (!GravityDebugLog.ENABLED || deadbandHit) {
            return;
        }
        OperationFrameDiagnostics diagnostics = transition.diagnostics();
        GravityDebugLog.log(
                entity,
                "geometry-frame",
                "decision=%s installedDown=%s proposedDown=%s "
                        + "exactFrameDeltaDeg=%.6f deadbandHit=%s "
                        + "positionAuthorityPolicy=%s "
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
                        ? Status.DEFERRED
                        : Status.APPLIED
        );
    }

    /**
     * Upper resting gap still considered the same support manifold: the
     * stateless ground probe itself accepts up to its probe distance, so a
     * snapshot taken from a grounded probe can describe the current pose with
     * a comparable gap.  Penetration beyond one contact slop is never a rest.
     */
    private static final double RESTING_GAP_LIMIT =
            GravityGroundProbe
                    .SUPPORT_CONTINUITY_REACQUIRE_DISTANCE
                    + CollisionTolerances.CONTACT_SKIN;
    private static final double PENETRATION_GAP_LIMIT =
            CollisionTolerances.CONTACT_SLOP;

    public static GravityFrame installedFallback(
            cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState runtime
    ) {
        GravityFrame installed = runtime.geometryReferenceFrame();
        if (installed == null) {
            throw new IllegalStateException(
                    "custom operation has no installed collision axis"
            );
        }
        return installed;
    }

    /** Exact legal-start test: touching is legal; only meaningful overlap is not. */
    static boolean isLegalKinematicPose(
            CollisionBody body,
            List<cc.sighs.gravityengine.gravity.collision.CollisionObstacle> obstacles
    ) {
        return !GravityCollisionEngine.requiresPenetrationRecovery(body, obstacles);
    }

    // -----------------------------------------------------------------
    // P0-5: Vanilla transition (no inflated box)
    // -----------------------------------------------------------------

    public static Result installVanilla(Entity entity) {
        if (!PlayerBodyHandoff.mayChangeBody(entity)) return Result.DEFERRED;
        Vec3 positionAnchor = entity.position();
        Vec3 velocity = entity.getDeltaMovement();
        EntityDimensions dims = GravityEntityGeometry.dimensions(entity);
        AABB candidate = dims.makeBoundingBox(positionAnchor);

        if (entity.level().noCollision(entity, candidate)) {
            GravityEntityGeometry.commitVanillaBody(entity, positionAnchor, candidate);
            entity.setDeltaMovement(velocity);
            entity.fallDistance = 0f;
            entity.setOnGround(false);
            return Result.applied();
        }

        // Attempt bounded recovery using dimensions
        Result recovered = attemptVanillaRecovery(entity, dims, positionAnchor, velocity);
        if (recovered != null) {
            entity.setDeltaMovement(velocity);
            return recovered;
        }

        // A proxy enclosure cannot become an exact vanilla collider. Keep the
        // accepted body, support and velocity until a legal handoff succeeds.
        return Result.DEFERRED;
    }

    private static Result attemptVanillaRecovery(Entity entity, EntityDimensions dims, Vec3 positionAnchor, Vec3 velocity) {
        double maxStep = entity.maxUpStep();
        if (maxStep <= 1.0E-6D) return null;
        double stepSize = Math.max(maxStep * 0.5, 0.0625);
        int maxSteps = Math.min((int) Math.ceil(maxStep / stepSize) + 2, 20);
        double halfWidth = dims.width() * 0.5;
        double height = dims.height();

        for (int i = 1; i <= maxSteps; i++) {
            double offset = stepSize * i;
            if (offset > maxStep + 0.5) break;
            Vec3 tryPositionAnchor = positionAnchor.add(0, offset, 0);
            AABB tryBox = new AABB(
                    tryPositionAnchor.x - halfWidth, tryPositionAnchor.y, tryPositionAnchor.z - halfWidth,
                    tryPositionAnchor.x + halfWidth, tryPositionAnchor.y + height, tryPositionAnchor.z + halfWidth);
            if (entity.level().noCollision(entity, tryBox)) {
                GravityEntityGeometry.commitVanillaBody(entity, tryPositionAnchor, tryBox);
                entity.fallDistance = 0f;
                entity.setOnGround(false);
                return Result.recovered(new Vec3(0, offset, 0));
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Custom body installation
    // -----------------------------------------------------------------

    public static Result installCustomFromPositionAnchor(Entity entity, GravityState state) {
        if (!PlayerBodyHandoff.mayChangeBody(entity)) return Result.DEFERRED;
        // A body-only plan (e.g. Elytra) is valid under ordinary world gravity too.
        EntityDimensions dims = GravityEntityGeometry.dimensions(entity);
        if (CharacterDimensionPolicy.decide(dims.width(), dims.height())
                != CharacterDimensionPolicy.Decision.CAPSULE) return Result.failed();
        Vec3 positionAnchor = entity.position();
        Vec3 velocity = entity.getDeltaMovement();
        GravityFrame frame = GravityEntityGeometry.frameAtPositionAnchor(entity, state, positionAnchor);
        CollisionBody body = GravityEntityGeometry.candidateBody(
                entity, dims, positionAnchor, frame
        );

        CollisionScene scene = MinecraftCollisionSceneCapture.captureAround(
                entity, body, entity.maxUpStep());
        boolean initiallyLegal =
                !cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine.requiresPenetrationRecovery(
                                body,
                                scene.query(body, new Vector3d()));
        if (!initiallyLegal) {
            cc.sighs.gravityengine.gravity.collision.CollisionObstacleQuery
                    obstacleQuery =
                    (queryBody, movement) -> scene.query(
                            queryBody, movement);
            cc.sighs.gravityengine.gravity.collision.CollisionRecovery.RecoveryResult recovery;
            try {
                recovery = CollisionRecovery.recover(
                        body,
                        obstacleQuery,
                        new cc.sighs.gravityengine.gravity.collision
                                .ObbQueryContext());
            } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException limit) {
                // Work-budget exhaustion in transition recovery is a failed
                // transition, never a fallback that pretends recovery happened.
                return Result.failed();
            }

            if (recovery.recovered()) {
                Vector3d recoveryVec = recovery.recoveryMovement();
                double maxDisplacement = entity.getBbWidth() + entity.maxUpStep() + 0.5;
                if (recoveryVec.length() > maxDisplacement
                        || !Double.isFinite(recoveryVec.lengthSquared())) {
                    return Result.failed();
                }
                Vec3 newPositionAnchor = positionAnchor.add(
                        MinecraftGeometryAdapter.toMinecraft(recoveryVec));
                GravityEntityGeometry.commitCustomBody(
                        entity, frame, newPositionAnchor, recovery.recoveredBody()
                );
                entity.setDeltaMovement(velocity);
                return Result.recovered(
                        MinecraftGeometryAdapter.toMinecraft(recoveryVec));
            } else {
                return Result.failed();
            }
        }

        GravityEntityGeometry.commitCustomBody(entity, frame, positionAnchor, body);
        entity.setDeltaMovement(velocity);
        return Result.applied();
    }
}
