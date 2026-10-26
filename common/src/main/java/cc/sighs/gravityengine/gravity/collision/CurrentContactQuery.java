package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Unified current-instant contact query.
 *
 * <p>Final movement hard-contact rebuilds and final
 * penetration validation all answer the same question: which obstacles are in
 * contact with the body at one absolute operation-local obstacle time.  The
 * scene is queried with a zero-duration window at that instant and every
 * candidate is probed at the corresponding normalized obstacle time, so a
 * moving platform that has not yet arrived (or has already left) is never
 * treated as current contact. Terminal support is independently selected by
 * FeetSupportQuery over real obstacle faces.</p>
 */
public final class CurrentContactQuery {
    private CurrentContactQuery() {}

    public record Result(
            List<CollisionContact> contacts,
            boolean indeterminate
    ) {
        public Result {
            Objects.requireNonNull(contacts, "contacts");
            contacts = List.copyOf(contacts);
        }

        public static Result indeterminateResult() {
            return new Result(List.of(), true);
        }
    }

    /**
     * Queries the current contact manifold at {@code obstacleTimeTicks}
     * while sweeping the body by {@code probeMovement} (a zero probe movement
     * keeps the body in place; support probes use their contact-sized down
     * movement).
     */
    public static Result query(
            CollisionBody body,
            Vec3d probeMovement,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        return query(body, probeMovement, scene, obstacleTimeTicks, context, null);
    }

    private static Result query(CollisionBody body, Vec3d probeMovement, CollisionScene scene,
            double obstacleTimeTicks, ObbQueryContext context, Vec3d desiredVelocity) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(probeMovement, "probeMovement");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");

        double intervalTicks = scene.time().intervalTicks();
        if (!Double.isFinite(obstacleTimeTicks)
                || obstacleTimeTicks < 0.0D
                || obstacleTimeTicks > intervalTicks + CollisionTolerances.TOI_EPSILON) {
            throw new IllegalArgumentException(
                    "obstacleTimeTicks outside operation: "
                            + obstacleTimeTicks + " / " + intervalTicks
            );
        }

        double normalizedTime =
                Math.max(0.0D, Math.min(1.0D, obstacleTimeTicks / intervalTicks));

        List<CollisionObstacle> obstacles =
                scene.query(
                        body,
                        probeMovement,
                        new SweepTimeWindow(obstacleTimeTicks, 0.0D)
                );

        List<CollisionContact> contacts = new ArrayList<>();
        for (CollisionObstacle obstacle : obstacles) {
            if (!context.workTracker().recordNarrowPhaseTest()) {
                return Result.indeterminateResult();
            }
            SweepContactResult result =
                    CollisionNarrowPhase.currentContactProbeResult(
                            body,
                            probeMovement,
                            obstacle,
                            normalizedTime,
                            context
                    );
            if (result.indeterminate()) {
                return Result.indeterminateResult();
            }
            contacts.addAll(result.contacts());
            if (desiredVelocity != null) {
                // At a corner each world-axis probe can retain a separating
                // axis even though combined relative motion enters the volume.
                // Test that direction too, without promoting a future gap hit.
                Vec3d relative = desiredVelocity.subtract(obstacle.velocityAt(SupportRegionPolicy.closestObstaclePointTo(obstacle, body.center(), normalizedTime), normalizedTime));
                if (relative.lengthSquared() > CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
                    if (!context.workTracker().recordNarrowPhaseTest()) return Result.indeterminateResult();
                    var entering = CollisionNarrowPhase.currentContactProbeResult(body,
                            relative.normalized()
                                    .multiply(GravityGroundProbe.PROBE_DISTANCE),
                            obstacle,
                            normalizedTime,
                            context);
                    if (entering.indeterminate()) return Result.indeterminateResult();
                    if (entering.initialState() != SweepInitialState.SEPARATED) {
                        for (var contact : entering.contacts()) {
                            boolean duplicate = contacts.stream().anyMatch(existing -> existing.obstacle().equals(obstacle)
                                    && CollisionTolerances.sameConstraintIdentity(existing.normal(), contact.normal()));
                            if (!duplicate) contacts.add(contact);
                        }
                    }
                }
            }
            if (probeMovement.lengthSquared() == 0 && result.contacts().isEmpty()) {
                // A zero sweep intentionally ignores non-entering touching SAT
                // axes. Recover hard touching constraints with contact-sized
                // directional queries, independently of semantic feet support.
                for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                    if (!context.workTracker().recordNarrowPhaseTest()) return Result.indeterminateResult();
                    var touching = CollisionNarrowPhase.currentContactProbeResult(body,
                            Vec3d.ZERO.withComponent(axis, sign * GravityGroundProbe.PROBE_DISTANCE),
                            obstacle, normalizedTime, context);
                    if (touching.indeterminate()) return Result.indeterminateResult();
                    // Directional queries expose non-entering touching axes;
                    // a future TOI across a real gap is not current contact.
                    if (touching.initialState() == SweepInitialState.SEPARATED) continue;
                    for (var contact : touching.contacts()) {
                        boolean duplicate = false;
                        for (var existing : contacts) if (existing.obstacle().equals(obstacle)
                                && CollisionTolerances.sameConstraintIdentity(existing.normal(), contact.normal())) duplicate = true;
                        if (!duplicate) contacts.add(contact);
                    }
                }
            }
        }

        // Capsule contacts already carry exact dedicated shape-pair witnesses.
        // Only generic boxes need the
        // oriented-box corner expansion below; no character corners are invented.
        if (body instanceof cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox box) {
            List<CollisionContact> witnesses = new ArrayList<>();
            for (var contact : contacts) {
                Vec3d normal = contact.normal();
                double minimum = Double.POSITIVE_INFINITY;
                List<Vec3d> corners = new ArrayList<>();
                var half = box.halfExtents();
                for (int bits = 0; bits < 8; bits++) {
                    var corner = box.localPointToWorld(new Vec3d(
                            (bits & 1) == 0 ? -half.x() : half.x(),
                            (bits & 2) == 0 ? -half.y() : half.y(),
                            (bits & 4) == 0 ? -half.z() : half.z()));
                    minimum = Math.min(minimum, corner.dot(normal));
                    corners.add(corner);
                }
                int before = witnesses.size();
                for (var corner : corners) {
                    if (corner.dot(normal) > minimum + CollisionTolerances.CONTACT_SKIN) continue;
                    var closest = SupportRegionPolicy.closestObstaclePointTo(contact.obstacle(), corner, normalizedTime);
                    double reach = GravityGroundProbe.PROBE_DISTANCE + contact.penetration();
                    if (corner.distanceSquared(closest) > reach * reach) continue;
                    witnesses.add(new CollisionContact(contact.obstacle(), corner, normal, contact.penetration(),
                            contact.timeOfImpact(), contact.obstacle().velocityAt(closest, normalizedTime), normalizedTime));
                }
                if (before == witnesses.size()) witnesses.add(contact);
            }
            contacts = witnesses;
        }
        contacts.sort(KinematicSweepKernel.CONTACT_ORDER);
        return new Result(contacts, false);
    }

    /** Current contact manifold for an unmoved final body. */
    public static Result contacts(
            CollisionBody body,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        return query(
                body,
                Vec3d.ZERO,
                scene,
                obstacleTimeTicks,
                context
        );
    }

    public static boolean hasMeaningfulPenetration(Result result) {
        Objects.requireNonNull(result, "result");
        if (result.indeterminate()) {
            return true;
        }
        return result.contacts().stream().anyMatch(
                contact -> contact.penetration()
                        > CollisionTolerances.PENETRATION_EPSILON
        );
    }

    /**
     * Final-body penetration predicate at an explicit operation-local
     * obstacle time.
     *
     * <p>Moving obstacles are probed at that same instant, so a platform that
     * has not yet arrived (or has already left) cannot own the final body.
     * An indeterminate solve is treated as penetration, because a solver that
     * cannot prove separation must not be allowed to claim it.</p>
     */
    public static boolean hasMeaningfulPenetrationAt(
            CollisionBody body,
            CollisionScene scene,
            double obstacleTimeTicks,
            ObbQueryContext context
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(context, "context");
        return hasMeaningfulPenetration(
                contacts(body, scene, obstacleTimeTicks, context)
        );
    }

    /**
     * Legality predicate for one body at one scene instant.
     *
     * <p>Equivalent to "the body has no meaningful penetration at operation
     * start". Tests and operation-local callers should prefer this
     * scene-consuming overload: moving obstacles are tested at the
     * operation-start instant instead of by recapturing live world state.</p>
     */
    public static boolean isClear(
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
     * Operation-start overlap check over a caller-provided obstacle list.
     *
     * <p>This is the exact initial-overlap predicate shared by recovery and
     * validation: it asks whether any listed obstacle already penetrates the
     * body beyond the penetration deadband.</p>
     */
    public static boolean requiresPenetrationRecovery(
            CollisionBody body,
            List<CollisionObstacle> obstacles,
            ObbQueryContext queryContext
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(obstacles, "obstacles");
        Objects.requireNonNull(queryContext, "queryContext");

        for (CollisionObstacle obstacle : obstacles) {
            Optional<CollisionContact> contact =
                    CollisionNarrowPhase.staticContact(
                            body,
                            obstacle,
                            queryContext
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
                body,
                obstacles,
                new ObbQueryContext()
        );
    }

    /** Current manifold including simultaneous edge/corner entry for this
     * velocity. Frozen obstacles and existing touching bands remain authority. */
    public static Result velocityContacts(CollisionBody body, Vec3d desiredVelocity,
            CollisionScene scene, double obstacleTimeTicks, ObbQueryContext context) {
        return query(body, Vec3d.ZERO, scene, obstacleTimeTicks, context,
                Objects.requireNonNull(desiredVelocity, "desiredVelocity"));
    }
}
