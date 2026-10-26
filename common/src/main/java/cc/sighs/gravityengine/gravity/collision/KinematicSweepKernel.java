package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.ScalarMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static cc.sighs.gravityengine.gravity.collision.MovementIndeterminateReason.*;

/**
 * Shape-neutral kinematic sweep lower kernel.
 *
 * <p>Owns the shared earliest-contact CCD query (scene query, work-budget
 * accounting, narrow-phase swept contact, cotemporal TOI selection and
 * deterministic ordering) used by both the combined-vector and character
 * routes.  It knows nothing about gravity frames, support, steps, slopes,
 * feet or gameplay ownership; those remain route policy. All vectors are
 * immutable neutral {@link Vec3d} values.</p>
 */
public final class KinematicSweepKernel {
    private KinematicSweepKernel() {}

    /**
     * Query-policy differences that are real accounting semantics of the two
     * routes, not solver branching: the character route charges contact
     * counts to the shared work budget, the passive route does not; the
     * passive route re-sorts cotemporal contacts deterministically.
     */
    public record EarliestQueryPolicy(
            boolean recordContactCounts,
            boolean deterministicContactSort
    ) {
        public static final EarliestQueryPolicy PASSIVE =
                new EarliestQueryPolicy(false, true);

        public static final EarliestQueryPolicy CHARACTER =
                new EarliestQueryPolicy(true, false);
    }

    /**
     * Earliest cotemporal contact batch.  {@code obstacles} is the query the
     * caller performed (opaque data so routes can classify moving obstacles
     * without a second scene query).
     */
    public record EarliestContactBatch(
            double timeOfImpact,
            List<CollisionContact> contacts,
            boolean indeterminate,
            boolean overlapping,
            List<CollisionObstacle> obstacles,
            MovementIndeterminateReason indeterminateReason
    ) {
        public EarliestContactBatch {
            Objects.requireNonNull(contacts, "contacts");
            Objects.requireNonNull(obstacles, "obstacles");
            contacts = List.copyOf(contacts);
            obstacles = List.copyOf(obstacles);
        }

        /** Batch for no earliest contact and no failure flags. */
        public static EarliestContactBatch none(
                List<CollisionObstacle> obstacles
        ) {
            return new EarliestContactBatch(
                    1.0D, List.of(), false, false, obstacles, NONE);
        }
    }

    /** Queries the scene once and finds the earliest cotemporal contact batch. */
    public static EarliestContactBatch queryEarliest(
            CollisionBody body,
            Vec3d movement,
            CollisionScene scene,
            SweepTimeWindow window,
            ObbQueryContext context,
            EarliestQueryPolicy policy
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");
        return queryEarliestOver(
                body,
                movement,
                scene.query(body, movement, window),
                window,
                scene.time().intervalTicks(),
                context,
                policy,
                null);
    }

    /**
     * Same query over an already-obtained obstacle list.  The caller owns the
     * scene query when it needs the same list for moving-obstacle
     * classification or rejection before narrow phase.
     */
    public static EarliestContactBatch queryEarliestOver(
            CollisionBody body,
            Vec3d movement,
            List<CollisionObstacle> obstacles,
            SweepTimeWindow window,
            double operationIntervalTicks,
            ObbQueryContext context,
            EarliestQueryPolicy policy
    ) {
        return queryEarliestOver(body, movement, obstacles, window,
                operationIntervalTicks, context, policy, null);
    }

    /** An operation-start overlap permits tangent/outward escape but supplies
     * a zero-time constraint against further inward relative displacement.
     * Penetration depth is never converted into automatic push-out.
     * A null baseline keeps the original
     * whole-request stop used by passive and explicit-recovery routes. */
    public static EarliestContactBatch queryEarliestOver(
            CollisionBody body,
            Vec3d movement,
            List<CollisionObstacle> obstacles,
            SweepTimeWindow window,
            double operationIntervalTicks,
            ObbQueryContext context,
            EarliestQueryPolicy policy,
            BodyCollisionDelta.Baseline collisionBaseline
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(obstacles, "obstacles");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");

        if (!Double.isFinite(operationIntervalTicks)
                || operationIntervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "operationIntervalTicks must be finite and positive: "
                            + operationIntervalTicks
            );
        }

        double normalizedStart =
                window.normalizedStartTicks(
                        operationIntervalTicks
                );

        double normalizedDuration =
                window.normalizedDurationTicks(
                        operationIntervalTicks
                );

        double earliestToi = 1.0D;
        List<CollisionContact> earliest = new ArrayList<>();
        boolean indeterminate = false;
        // Keep the first failure even if a later obstacle exhausts the shared budget.
        MovementIndeterminateReason reason = NONE;
        boolean overlapping = false;
        List<CollisionContact> toleratedBaselineOverlaps = new ArrayList<>();
        List<CollisionContact> newInitialOverlaps = new ArrayList<>();
        for (CollisionObstacle obstacle : obstacles) {
            if (!context.workTracker().recordNarrowPhaseTest()) {
                return observed(context, body, movement, window,
                        new EarliestContactBatch(
                                1.0D, List.of(), true, false, obstacles,
                                reason != NONE ? reason : COLLISION_QUERY_BUDGET),
                        toleratedBaselineOverlaps, newInitialOverlaps);
            }
            SweepContactResult result =
                    CollisionNarrowPhase.sweptContactResult(
                            body,
                            movement,
                            obstacle,
                            normalizedStart,
                            normalizedDuration,
                            context);
            if (result.indeterminate()) {
                indeterminate = true;
                if (reason == NONE) reason = context.workTracker().limitExceeded()
                        ? COLLISION_QUERY_BUDGET : SWEEP_QUERY_INDETERMINATE;
                continue;
            }
            if (policy.recordContactCounts()
                    && !context.workTracker().recordContacts(
                            result.contacts().size())) {
                return observed(context, body, movement, window,
                        new EarliestContactBatch(
                                1.0D, List.of(), true, false, obstacles,
                                reason != NONE ? reason : COLLISION_QUERY_BUDGET),
                        toleratedBaselineOverlaps, newInitialOverlaps);
            }
            if (result.initialState() == SweepInitialState.OVERLAPPING) {
                boolean existing;
                try {
                    existing = collisionBaseline != null
                            && result.contacts().stream().allMatch(
                            collisionBaseline::permitsExistingDepth);
                } catch (CollisionComplexityLimitException exception) {
                    return observed(context, body, movement, window,
                            new EarliestContactBatch(
                                    1.0D, List.of(), true, false, obstacles,
                                    COLLISION_QUERY_BUDGET),
                            toleratedBaselineOverlaps, newInitialOverlaps);
                }
                if (existing) {
                    toleratedBaselineOverlaps.addAll(result.contacts());
                    for (CollisionContact contact : result.contacts()) {
                        double entering = movement
                                .subtract(contact.surfaceVelocity().multiply(window.durationTicks()))
                                .dot(contact.normal());
                        if (entering < -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                            if (earliestToi > CollisionTolerances.TOI_EPSILON) {
                                earliest.clear();
                            }
                            earliestToi = 0.0D;
                            earliest.add(new CollisionContact(contact.obstacle(), contact.point(),
                                    contact.normal(), 0.0D, 0.0D, contact.surfaceVelocity(), contact.obstacleTime()));
                        }
                    }
                } else {
                    overlapping = true;
                    newInitialOverlaps.addAll(result.contacts());
                }
                continue;
            }
            for (CollisionContact contact : result.contacts()) {
                if (contact.timeOfImpact()
                        < earliestToi - CollisionTolerances.TOI_EPSILON) {
                    earliestToi = contact.timeOfImpact();
                    earliest.clear();
                    earliest.add(contact);
                } else if (Math.abs(
                        contact.timeOfImpact() - earliestToi
                ) <= CollisionTolerances.TOI_EPSILON) {
                    earliestToi = Math.min(earliestToi, contact.timeOfImpact());
                    earliest.add(contact);
                }
            }
        }
        if (indeterminate) {
            return observed(context, body, movement, window,
                    new EarliestContactBatch(
                            1.0D, List.of(), true, false, obstacles, reason),
                    toleratedBaselineOverlaps, newInitialOverlaps);
        }
        if (overlapping) {
            return observed(context, body, movement, window,
                    new EarliestContactBatch(
                            1.0D, List.of(), false, true, obstacles, NONE),
                    toleratedBaselineOverlaps, newInitialOverlaps);
        }
        if (earliest.isEmpty()) {
            return observed(context, body, movement, window,
                    EarliestContactBatch.none(obstacles),
                    toleratedBaselineOverlaps, newInitialOverlaps);
        }
        if (policy.deterministicContactSort()) {
            earliest.sort(CONTACT_ORDER);
        }
        return observed(context, body, movement, window,
                new EarliestContactBatch(
                        earliestToi,
                        earliest,
                        false,
                        false,
                        obstacles, NONE),
                toleratedBaselineOverlaps, newInitialOverlaps);
    }

    private static EarliestContactBatch observed(
            ObbQueryContext context,
            CollisionBody body,
            Vec3d movement,
            SweepTimeWindow window,
            EarliestContactBatch result,
            List<CollisionContact> toleratedBaselineOverlaps,
            List<CollisionContact> newInitialOverlaps
    ) {
        context.recordCollisionSweep(
                body,
                movement,
                window,
                result,
                toleratedBaselineOverlaps,
                newInitialOverlaps
        );
        return result;
    }

    /**
     * Deterministic cotemporal contact order shared by the passive route:
     * earliest time, then stable obstacle provenance, then normal components.
     */
    public static final Comparator<CollisionContact> CONTACT_ORDER =
            Comparator.comparingDouble(CollisionContact::timeOfImpact)
                    .thenComparing(
                            CollisionContact::obstacle,
                            CollisionObstacle.STABLE_COMPARATOR)
                    .thenComparingDouble(contact -> contact.normal().x())
                    .thenComparingDouble(contact -> contact.normal().y())
                    .thenComparingDouble(contact -> contact.normal().z());

    /**
     * Result of the homogeneous constrained-advance loop used by the
     * combined-vector route.  {@code blockingNormals} is raw geometric data;
     * the route decides gameplay flags from it.
     */
    public record HomogeneousAdvanceResult(
            CollisionBody body,
            Vec3d locomotion,
            List<Vec3d> blockingNormals,
            List<CollisionContact> impacts,
            boolean indeterminate,
            MovementIndeterminateReason indeterminateReason
    ) {
        public HomogeneousAdvanceResult {
            impacts = List.copyOf(impacts);
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(locomotion, "locomotion");
            Objects.requireNonNull(
                    blockingNormals,
                    "blockingNormals"
            );

            blockingNormals = blockingNormals.stream()
                    .map(normal -> {
                        Objects.requireNonNull(
                                normal,
                                "blocking normal"
                        );
                        return normal;
                    })
                    .toList();
        }

        @Override
        public List<Vec3d> blockingNormals() {
            return blockingNormals.stream()
                    .map(Vec3d::new)
                    .toList();
        }
    }

    /**
     * Homogeneous constrained CCD: advance to the earliest cotemporal
     * contact, install every entering constraint, project the remaining
     * movement, and repeat up to {@code maxBumps}.  Stagnation, non-closing
     * zero-TOI states, exhausted bumps and unresolved remainder return the
     * locomotion proven by completed segments together with an indeterminate
     * marker; callers must never treat that marker as a reason to discard the
     * proven prefix.  The kernel has no notion of vertical/tangent, support,
     * steps or gameplay flags.
     */
    public static HomogeneousAdvanceResult advanceHomogeneous(
            CollisionBody startBody,
            Vec3d requestedMovement,
            CollisionScene scene,
            SweepTimeWindow window,
            ObbQueryContext context,
            int maxBumps
    ) {
        Objects.requireNonNull(startBody, "startBody");
        Objects.requireNonNull(requestedMovement, "requestedMovement");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(context, "context");
        if (maxBumps < 0) {
            throw new IllegalArgumentException(
                    "maxBumps must be non-negative");
        }

        CollisionBody body = startBody;
        SweepTimeWindow remainingWindow = window;
        Vec3d remaining = requestedMovement;
        Vec3d locomotion = Vec3d.ZERO;
        List<ContactConstraintProjector.Constraint> constraints =
                new ArrayList<>();
        List<Vec3d> blockingNormals = new ArrayList<>();
        List<CollisionContact> impacts = new ArrayList<>();
        boolean indeterminate = false;
        MovementIndeterminateReason reason = NONE;

        for (int bump = 0; bump < maxBumps; bump++) {
            if (remaining.lengthSquared()
                    <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
                remaining = Vec3d.ZERO;
                break;
            }
            EarliestContactBatch earliest = queryEarliest(
                    body,
                    remaining,
                    scene,
                    remainingWindow,
                    context,
                    EarliestQueryPolicy.PASSIVE);
            if (earliest.indeterminate() || earliest.overlapping()) {
                indeterminate = true;
                reason = earliest.indeterminate() ? earliest.indeterminateReason() : SWEEP_INITIAL_OVERLAP;
                break;
            }
            if (earliest.contacts().isEmpty()) {
                body = body.move(remaining);
                locomotion = locomotion.add(remaining);
                remaining = Vec3d.ZERO;
                break;
            }

            double toi = Math.max(0.0D, Math.min(1.0D, earliest.timeOfImpact()));
            for (var contact : earliest.contacts()) {
                if (remaining.dot(contact.normal()) < -CollisionTolerances.ENTERING_PLANE_EPSILON)
                    impacts.add(contact);
            }
            Vec3d advance = remaining.multiply(toi);
            body = body.move(advance);
            locomotion = locomotion.add(advance);
            remaining = remaining.subtract(advance);
            remainingWindow =
                    remainingWindow.remainingAfter(toi);

            boolean added = false;
            for (CollisionContact contact : earliest.contacts()) {
                if (remaining.dot(contact.normal())
                        >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                    continue;
                }
                added |= addConstraint(
                        constraints, blockingNormals, contact.normal());
            }
            if (!added && toi <= CollisionTolerances.TOI_EPSILON) {
                indeterminate = true;
                reason = ADVANCE_NON_BLOCKING_ZERO_TOI;
                break;
            }

            ContactConstraintProjector.Result projection =
                    ContactConstraintProjector.project(remaining, constraints);
            if (projection.infeasible()) {
                indeterminate = true;
                reason = ADVANCE_INFEASIBLE;
                break;
            }
            Vec3d projected = projection.requireProjectedVector();
            double changeSquared =
                    projected
                            .subtract(remaining)
                            .lengthSquared();
            if (changeSquared
                    <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED
                    && toi <= CollisionTolerances.TOI_EPSILON) {
                indeterminate = true;
                reason = ADVANCE_NO_PROGRESS;
                break;
            }
            remaining = projected;
        }

        if (!indeterminate && remaining.lengthSquared()
                > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            indeterminate = true;
            reason = ADVANCE_BUMP_LIMIT;
        }
        return new HomogeneousAdvanceResult(
                body, locomotion, blockingNormals, impacts, indeterminate, reason);
    }

    private static boolean addConstraint(
            List<ContactConstraintProjector.Constraint> constraints,
            List<Vec3d> normals,
            Vec3d normal
    ) {
        for (ContactConstraintProjector.Constraint existing : constraints) {
            if (CollisionTolerances.sameConstraintIdentity(
                    existing.normal(), normal)) {
                return false;
            }
        }
        constraints.add(
                new ContactConstraintProjector.Constraint(normal, 0.0D));
        if (normals.stream().noneMatch(existing ->
                CollisionTolerances.sameConstraintIdentity(
                        existing, normal))) {
            normals.add(normal);
        }
        return true;
    }

    /** Detached route evidence; contacts describe impacts, never persistent planes. */
    public record ProjectedAdvanceResult(CollisionBody body, Vec3d applied,
            List<CollisionContact> impacts, MovementIndeterminateReason reason) {
        public ProjectedAdvanceResult {

            impacts = List.copyOf(impacts);
        }
    }

    /**
     * Full-vector hard-contact response. Provenance belongs to the caller;
     * neither the initial direction nor a gravity axis restricts projection.
     * Advance once per cotemporal batch, rebuild the current finite manifold,
     * then project the residual against real normals and sampled surface motion.
     * Every next segment queries the same immutable scene from the new exact
     * body and remaining time window. No historical plane survives a rebuild.
     */
    public static ProjectedAdvanceResult advanceProjected(CollisionBody start, Vec3d movement,
            CollisionScene scene, SweepTimeWindow window, ObbQueryContext context,
            BodyCollisionDelta.Baseline baseline, int maxBumps) {
        return advanceProjected(start, movement, scene, window, context, baseline, maxBumps,
                HARD_CONTACT_RESPONSE);
    }

    /** Operation-local route policy; geometry and time advancement remain kernel-owned. */
    interface ProjectedResponse {
        default void advanced(Vec3d movement, double fraction) {}

        default Vec3d contact(
                CollisionBody body,
                Vec3d remaining,
                SweepTimeWindow window,
                List<CollisionContact> contacts
        ) {
            return remaining;
        }

        default Vec3d additionalConstraintNormal(
                CollisionContact contact,
                Vec3d desired
        ) {
            return null;
        }

        default void projected(
                Vec3d desired,
                ContactConstraintProjector.Result projection
        ) {}
    }
    private static final ProjectedResponse HARD_CONTACT_RESPONSE = new ProjectedResponse() {};

    static ProjectedAdvanceResult advanceProjected(CollisionBody start, Vec3d movement,
            CollisionScene scene, SweepTimeWindow window, ObbQueryContext context,
            BodyCollisionDelta.Baseline baseline, int maxBumps, ProjectedResponse response) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(context, "context");
        if (maxBumps < 0) throw new IllegalArgumentException("maxBumps must be non-negative");
        CollisionBody body = start;
        Vec3d applied = Vec3d.ZERO;
        Vec3d remaining = movement;
        SweepTimeWindow remainingWindow = window;
        List<CollisionContact> impacts = new ArrayList<>();
        MovementIndeterminateReason reason = NONE;
        boolean verifyRemainingTime = false;
        try {
            for (int bump = 0; bump < maxBumps
                    && (remaining.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED
                    || verifyRemainingTime); bump++) {
                var obstacles = scene.query(body, remaining, remainingWindow);
                if (scene.diagnostics().limitExceeded() || context.workTracker().limitExceeded()) {
                    reason = COLLISION_QUERY_BUDGET;
                    break;
                }
                var batch = queryEarliestOver(body, remaining, obstacles, remainingWindow,
                        scene.time().intervalTicks(), context, EarliestQueryPolicy.CHARACTER, baseline);
                if (batch.indeterminate() || batch.overlapping()) {
                    reason = batch.indeterminate() ? batch.indeterminateReason() : SWEEP_INITIAL_OVERLAP;
                    break;
                }
                if (batch.contacts().isEmpty()) {
                    body = body.move(remaining);
                    applied = applied.add(remaining);
                    response.advanced(remaining, 1);
                    remaining = Vec3d.ZERO;
                    verifyRemainingTime = false;
                    break;
                }
                double toi = ScalarMath.clamp(batch.timeOfImpact(), 0, 1);
                Vec3d advance = remaining.multiply(toi);
                body = body.move(advance);
                applied = applied.add(advance);
                response.advanced(advance, toi);
                remaining = remaining.subtract(advance);
                remainingWindow = remainingWindow.remainingAfter(toi);
                verifyRemainingTime = remainingWindow.durationTicks() > 0
                        && obstacles.stream().anyMatch(o -> o instanceof EntityObstacle e && e.motion().moving());
                if (remaining.lengthSquared() <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED
                        && !verifyRemainingTime) break;

                // Include currently touching planes too: projecting this impact may
                // turn a previously tangent/separating neighbour inward. Re-query,
                // rather than retaining an infinite plane from an earlier impact.
                var current = CurrentContactQuery.contacts(body, scene, remainingWindow.startTicks(), context);
                if (current.indeterminate() || scene.diagnostics().limitExceeded()
                        || context.workTracker().limitExceeded()) {
                    reason = COLLISION_QUERY_BUDGET;
                    break;
                }
                Vec3d desired = response.contact(
                        body,
                        remaining,
                        remainingWindow,
                        batch.contacts()
                );

                List<ContactConstraintProjector.Constraint> constraints =
                        new ArrayList<>();

                for (var contact : batch.contacts()) {
                    Vec3d surfaceDisplacement = contact.surfaceVelocity()
                            .multiply(remainingWindow.durationTicks());
                    // A moving rigid contact needs a positive convergence gap
                    // after projection. Landing at a rounded zero gap can put
                    // the next finite voxel edge on the penetrating side and
                    // fabricate a wall. This is positional CCD separation only;
                    // velocity response still consumes native surface velocity.
                    if (contact.obstacle() instanceof EntityObstacle rigid && rigid.motion().requiresGeneralSweep())
                        surfaceDisplacement = surfaceDisplacement.add(contact.normal()
                                .multiply(CollisionTolerances.CCD_CONVERGENCE_GAP));

                    CurrentContactConstraintBuilder.addConstraint(
                            constraints,
                            contact.normal(),
                            surfaceDisplacement
                    );

                    Vec3d extra =
                            response.additionalConstraintNormal(contact, desired);

                    if (extra != null) {
                        CurrentContactConstraintBuilder.addConstraint(
                                constraints,
                                extra,
                                surfaceDisplacement
                        );
                    }
                }

                var projection = CurrentContactConstraintBuilder.projectDisplacement(
                        body,
                        desired,
                        constraints,
                        current.contacts(),
                        remainingWindow.durationTicks(),
                        remainingWindow.startTicks() / scene.time().intervalTicks(),
                        context,
                        contact -> response.additionalConstraintNormal(contact, desired)
                );
                if (projection.infeasible()) {
                    reason = ADVANCE_INFEASIBLE;
                    break;
                }
                Vec3d projected = projection.requireProjectedVector();
                response.projected(desired, projection);
                // Only planes participating in response own impact semantics. A
                // ceiling merely touched at the fully consumed endpoint did not
                // block this leg. A current neighbour activated by projection did.
                for (var contact : batch.contacts()) {
                    Vec3d extra =
                            response.additionalConstraintNormal(contact, desired);

                    if (projection.activePlanes().stream().anyMatch(plane ->
                            CollisionTolerances.sameConstraintIdentity(
                                    plane.normal(), contact.normal()
                            )
                                    || extra != null
                                    && CollisionTolerances.sameConstraintIdentity(
                                    plane.normal(), extra
                            ))) {
                        impacts.add(contact);
                    }
                }

                for (var contact : current.contacts()) {
                    Vec3d extra =
                            response.additionalConstraintNormal(contact, desired);

                    if (projection.activePlanes().stream().anyMatch(plane ->
                            CollisionTolerances.sameConstraintIdentity(
                                    plane.normal(), contact.normal()
                            )
                                    || extra != null
                                    && CollisionTolerances.sameConstraintIdentity(
                                    plane.normal(), extra
                            ))) {
                        impacts.add(contact);
                    }
                }
                if (toi <= CollisionTolerances.TOI_EPSILON
                        && (projected.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED || verifyRemainingTime)
                        && projected.equals(remaining)) {
                    // A sub-slop correction can still remove an entering normal
                    // component (e.g. rounded capsule normals at a voxel seam).
                    // Re-sweep it; only an unchanged residual proves stagnation.
                    reason = ADVANCE_NO_PROGRESS;
                    break;
                }
                remaining = projected;
            }
        } catch (CollisionComplexityLimitException exhausted) {
            // A secondary query never revokes an already completed CCD segment.
            reason = COLLISION_QUERY_BUDGET;
        }
        if (reason == NONE && (remaining.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED || verifyRemainingTime))
            reason = ADVANCE_BUMP_LIMIT;
        return new ProjectedAdvanceResult(body, applied, impacts, reason);
    }
}
