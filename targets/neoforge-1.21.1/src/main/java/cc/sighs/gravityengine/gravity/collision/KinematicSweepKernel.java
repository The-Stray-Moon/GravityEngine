package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
 * feet or gameplay ownership; those remain route policy.  All vectors are
 * neutral JOML values.</p>
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
            Vector3dc movement,
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
            Vector3dc movement,
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
            Vector3dc movement,
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
                            new Vector3d(movement),
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
                        double entering = new Vector3d(movement)
                                .sub(contact.surfaceVelocity().mul(window.durationTicks()))
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
            Vector3dc movement,
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
                    .thenComparingDouble(contact -> contact.normal().x)
                    .thenComparingDouble(contact -> contact.normal().y)
                    .thenComparingDouble(contact -> contact.normal().z);

    /**
     * Result of the homogeneous constrained-advance loop used by the
     * combined-vector route.  {@code blockingNormals} is raw geometric data;
     * the route decides gameplay flags from it.
     */
    public record HomogeneousAdvanceResult(
            CollisionBody body,
            Vector3d locomotion,
            List<Vector3d> blockingNormals,
            boolean indeterminate,
            MovementIndeterminateReason indeterminateReason
    ) {
        public HomogeneousAdvanceResult {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(locomotion, "locomotion");
            Objects.requireNonNull(
                    blockingNormals,
                    "blockingNormals"
            );

            locomotion = new Vector3d(locomotion);

            blockingNormals = blockingNormals.stream()
                    .map(normal -> {
                        Objects.requireNonNull(
                                normal,
                                "blocking normal"
                        );
                        return new Vector3d(normal);
                    })
                    .toList();
        }

        @Override
        public Vector3d locomotion() {
            return new Vector3d(locomotion);
        }

        @Override
        public List<Vector3d> blockingNormals() {
            return blockingNormals.stream()
                    .map(Vector3d::new)
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
            Vector3dc requestedMovement,
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
        Vector3d remaining = new Vector3d(requestedMovement);
        Vector3d locomotion = new Vector3d();
        List<ContactConstraintProjector.Constraint> constraints =
                new ArrayList<>();
        List<Vector3d> blockingNormals = new ArrayList<>();
        boolean indeterminate = false;
        MovementIndeterminateReason reason = NONE;

        for (int bump = 0; bump < maxBumps; bump++) {
            if (remaining.lengthSquared()
                    <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
                remaining.zero();
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
                locomotion.add(remaining);
                remaining.zero();
                break;
            }

            double toi = Math.max(0.0D, Math.min(1.0D, earliest.timeOfImpact()));
            Vector3d advance = new Vector3d(remaining).mul(toi);
            body = body.move(advance);
            locomotion.add(advance);
            remaining.sub(advance);
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
            Vector3d projected = projection.requireProjectedVector();
            double changeSquared =
                    new Vector3d(projected)
                            .sub(remaining)
                            .lengthSquared();
            if (changeSquared
                    <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED
                    && toi <= CollisionTolerances.TOI_EPSILON) {
                indeterminate = true;
                reason = ADVANCE_NO_PROGRESS;
                break;
            }
            remaining.set(projected);
        }

        if (!indeterminate && remaining.lengthSquared()
                > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            indeterminate = true;
            reason = ADVANCE_BUMP_LIMIT;
        }
        return new HomogeneousAdvanceResult(
                body, locomotion, blockingNormals, indeterminate, reason);
    }

    private static boolean addConstraint(
            List<ContactConstraintProjector.Constraint> constraints,
            List<Vector3d> normals,
            Vector3dc normal
    ) {
        for (ContactConstraintProjector.Constraint existing : constraints) {
            if (CollisionTolerances.sameConstraintIdentity(
                    existing.normal(), normal)) {
                return false;
            }
        }
        constraints.add(
                new ContactConstraintProjector.Constraint(new Vector3d(normal), 0.0D));
        if (normals.stream().noneMatch(existing ->
                CollisionTolerances.sameConstraintIdentity(
                        existing, normal))) {
            normals.add(new Vector3d(normal));
        }
        return true;
    }

    /** Detached route evidence; contacts describe impacts, never persistent planes. */
    public record ProjectedAdvanceResult(CollisionBody body, Vector3d applied,
            List<CollisionContact> impacts, MovementIndeterminateReason reason) {
        public ProjectedAdvanceResult {
            applied = new Vector3d(applied);
            impacts = List.copyOf(impacts);
        }
        @Override public Vector3d applied() { return new Vector3d(applied); }
    }

    /**
     * Full-vector hard-contact response. Provenance belongs to the caller;
     * neither the initial direction nor a gravity axis restricts projection.
     * Advance once per cotemporal batch, rebuild the current finite manifold,
     * then project the residual against real normals and sampled surface motion.
     * Every next segment queries the same immutable scene from the new exact
     * body and remaining time window. No historical plane survives a rebuild.
     */
    public static ProjectedAdvanceResult advanceProjected(CollisionBody start, Vector3dc movement,
            CollisionScene scene, SweepTimeWindow window, ObbQueryContext context,
            BodyCollisionDelta.Baseline baseline, int maxBumps) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(context, "context");
        if (maxBumps < 0) throw new IllegalArgumentException("maxBumps must be non-negative");
        CollisionBody body = start;
        Vector3d applied = new Vector3d();
        Vector3d remaining = new Vector3d(movement);
        SweepTimeWindow remainingWindow = window;
        List<CollisionContact> impacts = new ArrayList<>();
        MovementIndeterminateReason reason = NONE;
        for (int bump = 0; bump < maxBumps
                && remaining.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED; bump++) {
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
                applied.add(remaining);
                remaining.zero();
                break;
            }
            double toi = Math.clamp(batch.timeOfImpact(), 0, 1);
            Vector3d advance = new Vector3d(remaining).mul(toi);
            body = body.move(advance);
            applied.add(advance);
            remaining.sub(advance);
            remainingWindow = remainingWindow.remainingAfter(toi);
            if (remaining.lengthSquared() <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) break;

            // Include currently touching planes too: projecting this impact may
            // turn a previously tangent/separating neighbour inward. Re-query,
            // rather than retaining an infinite plane from an earlier impact.
            var current = CurrentContactQuery.contacts(body, scene, remainingWindow.startTicks(), context);
            if (current.indeterminate() || scene.diagnostics().limitExceeded()
                    || context.workTracker().limitExceeded()) {
                reason = COLLISION_QUERY_BUDGET;
                break;
            }
            List<ContactConstraintProjector.Constraint> constraints = new ArrayList<>();
            for (var contact : batch.contacts()) {
                CurrentContactConstraintBuilder.addConstraint(constraints, contact.normal(),
                        contact.surfaceVelocity().mul(remainingWindow.durationTicks()));
            }
            for (var contact : current.contacts()) {
                CurrentContactConstraintBuilder.addConstraint(constraints, contact.normal(),
                        contact.surfaceVelocity().mul(remainingWindow.durationTicks()));
            }
            var projection = ContactConstraintProjector.project(remaining, constraints);
            if (projection.infeasible()) {
                reason = ADVANCE_INFEASIBLE;
                break;
            }
            Vector3d projected = projection.requireProjectedVector();
            // Only planes participating in response own impact semantics. A
            // ceiling merely touched at the fully consumed endpoint did not
            // block this leg. A current neighbour activated by projection did.
            for (var contact : batch.contacts()) {
                if (projection.activePlanes().stream().anyMatch(plane ->
                        CollisionTolerances.sameConstraintIdentity(plane.normal(), contact.normal())))
                    impacts.add(contact);
            }
            for (var contact : current.contacts()) {
                if (projection.activePlanes().stream().anyMatch(plane ->
                        CollisionTolerances.sameConstraintIdentity(plane.normal(), contact.normal())))
                    impacts.add(contact);
            }
            if (toi <= CollisionTolerances.TOI_EPSILON
                    && projected.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED
                    && projected.distanceSquared(remaining) <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
                reason = ADVANCE_NO_PROGRESS;
                break;
            }
            remaining.set(projected);
        }
        if (reason == NONE && remaining.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED)
            reason = ADVANCE_BUMP_LIMIT;
        return new ProjectedAdvanceResult(body, applied, impacts, reason);
    }
}
