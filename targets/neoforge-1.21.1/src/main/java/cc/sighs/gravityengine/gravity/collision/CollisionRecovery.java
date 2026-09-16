package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

import static cc.sighs.gravityengine.gravity.collision.MovementIndeterminateReason.*;

/**
 * Initial-overlap recovery using the shared minimum-norm half-space projector.
 *
 * <p>Every pass rebuilds penetration constraints from current geometry and
 * projects zero onto them as one order-independent convex problem. Does not
 * modify the entity.  All geometric values are neutral vectors.</p>
 */
public final class CollisionRecovery {
    private static final int MAX_PASSES = 4;

    private CollisionRecovery() {}

    /**
     * Result of the recovery pass.
     *
     * @param recoveredBody    the body after recovery displacement
     * @param recoveryMovement total recovery displacement applied
     * @param contacts         penetration contacts collected during recovery
     * @param recovered        true if recovery was successful (penetration resolved)
     * @param failureReason    diagnostic source of failure, NONE on success
     */
    public record RecoveryResult(
            CollisionBody recoveredBody,
            Vector3d recoveryMovement,
            List<CollisionContact> contacts,
            boolean recovered,
            MovementIndeterminateReason failureReason
    ) {
        public RecoveryResult(CollisionBody body, Vector3d movement, List<CollisionContact> contacts,
                              boolean recovered) {
            this(body, movement, contacts, recovered, recovered ? NONE : OTHER);
        }

        /** Legal starts bypass recovery while retaining the solver's uniform result shape. */
        public static RecoveryResult noRecovery(CollisionBody body) {
            return new RecoveryResult(body, new Vector3d(), List.of(), true);
        }

        public RecoveryResult {
            recoveryMovement = new Vector3d(recoveryMovement);
            contacts = List.copyOf(contacts);
        }

        /** Fresh defensive recovery-movement copy. */
        public Vector3d recoveryMovement() {
            return new Vector3d(recoveryMovement);
        }
    }

    public static RecoveryResult recover(
            CollisionBody body,
            CollisionObstacleQuery obstacleQuery
    ) {
        return recover(body, obstacleQuery, new ObbQueryContext());
    }

    public static RecoveryResult recover(
            CollisionBody body,
            CollisionObstacleQuery obstacleQuery,
            ObbQueryContext queryContext
    ) {
        CollisionBody current = body;
        Vector3d totalRecovery = new Vector3d();
        List<CollisionContact> history = new ArrayList<>();

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!queryContext.workTracker().recordRecoveryPass()) {
                throw new CollisionComplexityLimitException(
                        "recovery pass budget exceeded: " +
                                queryContext.workTracker().limitReason()
                );
            }
            List<CollisionObstacle> obstacles =
                    obstacleQuery.query(current, new Vector3d());
            List<CollisionContact> passContacts = collectPenetrations(
                    current, obstacles, queryContext
            );

            if (passContacts.isEmpty()) {
                return new RecoveryResult(
                        current, totalRecovery, List.copyOf(history), true);
            }

            history.addAll(passContacts);

            ContactConstraintProjector.Result projection =
                    ContactConstraintProjector.project(
                            new Vector3d(),
                            passContacts.stream()
                                    .map(contact -> new ContactConstraintProjector.Constraint(
                                            contact.normal(),
                                            contact.penetration()
                                                    + CollisionTolerances.CONTACT_SKIN
                                    ))
                                    .toList()
                    );
            if (projection.infeasible()) {
                return new RecoveryResult(
                        current, totalRecovery, List.copyOf(history), false, INITIAL_OVERLAP_RECOVERY_INFEASIBLE
                );
            }
            Vector3d passCorrection = projection.requireProjectedVector();

            if (passCorrection.lengthSquared()
                    <= CollisionTolerances.PENETRATION_EPSILON
                    * CollisionTolerances.PENETRATION_EPSILON) {
                obstacles = obstacleQuery.query(current, new Vector3d());
                List<CollisionContact> freshContacts = collectPenetrations(
                        current, obstacles, queryContext
                );
                boolean recovered = isRecovered(freshContacts);
                history.addAll(freshContacts);
                return new RecoveryResult(
                        current, totalRecovery, List.copyOf(history), recovered,
                        recovered ? NONE : INITIAL_OVERLAP_RECOVERY_NO_PROGRESS);
            }

            List<CollisionObstacle> corridorObstacles =
                    obstacleQuery.query(current, passCorrection);
            MovementIndeterminateReason corridorFailure = recoveryCorridorFailure(
                    current,
                    passCorrection,
                    passContacts,
                    corridorObstacles,
                    queryContext
            );
            if (corridorFailure != NONE) {
                return new RecoveryResult(
                        current, totalRecovery, List.copyOf(history), false, corridorFailure);
            }

            current = current.move(passCorrection);
            totalRecovery.add(passCorrection);
        }

        // Max passes reached; check final state
        List<CollisionObstacle> obstacles =
                obstacleQuery.query(current, new Vector3d());
        List<CollisionContact> finalContacts = collectPenetrations(
                current, obstacles, queryContext
        );
        if (finalContacts.isEmpty()) {
            return new RecoveryResult(
                    current, totalRecovery, List.copyOf(history), true);
        }

        history.addAll(finalContacts);
        boolean recovered = isRecovered(finalContacts);
        return new RecoveryResult(
                current,
                totalRecovery,
                List.copyOf(history),
                recovered, recovered ? NONE : INITIAL_OVERLAP_RECOVERY_PASS_LIMIT);
    }

    public static List<CollisionContact> collectPenetrations(
            CollisionBody body,
            List<CollisionObstacle> obstacles
    ) {
        return collectPenetrations(body, obstacles, new ObbQueryContext());
    }

    public static List<CollisionContact> collectPenetrations(
            CollisionBody body,
            List<CollisionObstacle> obstacles,
            ObbQueryContext queryContext
    ) {
        List<CollisionContact> contacts = new ArrayList<>();
        for (CollisionObstacle obstacle : obstacles) {
            if (!queryContext.workTracker().recordNarrowPhaseTest()) {
                throw new CollisionComplexityLimitException(
                        "penetration narrow-phase budget exceeded: " +
                                queryContext.workTracker().limitReason()
                );
            }
            var contact = CollisionNarrowPhase.staticContact(
                    body, obstacle, queryContext
            );
            if (contact.isPresent()) {
                if (!queryContext.workTracker().recordContacts(1)) {
                    throw new CollisionComplexityLimitException(
                            "recovery contact budget exceeded: " +
                                    queryContext.workTracker().limitReason()
                    );
                }
                contacts.add(contact.get());
            }
        }
        return VoxelContactReducer.reduce(contacts, queryContext.workTracker());
    }

    private static boolean isRecovered(List<CollisionContact> contacts) {
        for (CollisionContact contact : contacts) {
            if (contact.penetration() > CollisionTolerances.PENETRATION_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * Recovery displacement corridor check.
     *
     * <p>Initial-overlap recovery is an instantaneous geometry correction at
     * the operation start (or transition instant). The body displacement is
     * swept against obstacles frozen at that instant: a moving obstacle is
     * probed with a zero-duration interval so its later operation motion
     * never blocks (or unblocks) the start-of-operation recovery corridor.
     * Static obstacles are unaffected because their displacement is zero.</p>
     */
    private static MovementIndeterminateReason recoveryCorridorFailure(
            CollisionBody body,
            Vector3dc correction,
            List<CollisionContact> startingContacts,
            List<CollisionObstacle> corridorObstacles,
            ObbQueryContext queryContext
    ) {
        for (CollisionObstacle obstacle : corridorObstacles) {
            if (containsObstacle(startingContacts, obstacle)) continue;
            if (!queryContext.workTracker().recordNarrowPhaseTest()) {
                throw new CollisionComplexityLimitException(
                        "recovery corridor budget exceeded: " +
                                queryContext.workTracker().limitReason()
                );
            }
            SweepContactResult result = CollisionNarrowPhase.sweptContactResult(
                    body,
                    new Vector3d(correction),
                    obstacle,
                    0.0D,
                    0.0D,
                    queryContext
            );
            if (result.indeterminate()) {
                return queryContext.workTracker().limitExceeded()
                        ? INITIAL_OVERLAP_RECOVERY_BUDGET : INITIAL_OVERLAP_RECOVERY_CORRIDOR_INDETERMINATE;
            }
            if (result.initialState() == SweepInitialState.OVERLAPPING || result.hasBlockingContacts()) {
                return INITIAL_OVERLAP_RECOVERY_CORRIDOR_BLOCKED;
            }
        }
        return NONE;
    }

    private static boolean containsObstacle(
            List<CollisionContact> contacts,
            CollisionObstacle obstacle
    ) {
        for (CollisionContact contact : contacts) {
            if (CollisionObstacle.STABLE_COMPARATOR.compare(
                    contact.obstacle(), obstacle) == 0) {
                return true;
            }
        }
        return false;
    }
}
