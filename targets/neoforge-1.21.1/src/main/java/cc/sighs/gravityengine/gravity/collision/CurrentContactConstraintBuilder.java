package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Builds velocity-specific constraints from finite current geometry.
 *
 * <p>Only the hard current manifold contributes constraints. The feet locator
 * can select a surface without body contact and therefore cannot add a plane.</p>
 *
 * <p>Constraint {@code n dot v >= n dot surfaceVelocity} preserves only the
 * moving-surface normal boundary motion; it never invents platform tangent
 * carry.  Tangent carry, when it exists, stays owned by the existing
 * support-motion system.</p>
 */
public final class CurrentContactConstraintBuilder {
    private static final double PURE_GRAVITY_AXIS_DOT = 1.0D - 1.0E-6D;
    private CurrentContactConstraintBuilder() {}

    /** Restricted tangent-route plane, never a full-vector hard-contact normal.
     * The near-vertical degeneracy band is the original character-route band. */
    public static Vector3d tangentPlaneNormal(Vector3d normal, GravityFrame frame) {
        Vector3d up = frame.orientation().axisY(new Vector3d());
        double upDot = normal.dot(up);
        if (Math.abs(upDot) >= PURE_GRAVITY_AXIS_DOT) return null;
        Vector3d plane = new Vector3d(normal).sub(new Vector3d(up).mul(upDot));
        if (plane.lengthSquared() <= CollisionTolerances.PARALLEL_PLANE_CROSS_SQUARED) return null;
        return plane.normalize();
    }

    /**
     * Current contact is evidence, not an infinite wall. Activate a plane only
     * when this relative velocity deepens exact overlap with its finite obstacle
     * at the frozen normalized obstacle instant.
     *
     * <p>For ordinary continuous FLOOR contact, character persistent velocity
     * retains Vanilla-like locomotion semantics: only gravity-down velocity is
     * clipped. The geometric rise required to follow the slope belongs to the
     * movement route and must not become persistent gravity-up actor velocity.</p>
     *
     * <p>Walls and ceilings continue to use their real hard-contact normals.</p>
     */
    public static List<ContactConstraintProjector.Constraint> characterConstraints(
            CollisionBody body,
            CurrentContactQuery.Result contacts,
            Vector3dc desiredVelocity,
            GravityFrame frame,
            double normalizedTime,
            ObbQueryContext context
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(desiredVelocity, "desiredVelocity");
        Objects.requireNonNull(frame, "frame");

        if (contacts.indeterminate()) {
            throw new CollisionComplexityLimitException(
                    "current velocity contacts"
            );
        }

        if (!Double.isFinite(normalizedTime)
                || normalizedTime < 0.0D
                || normalizedTime > 1.0D) {
            throw new IllegalArgumentException(
                    "normalized obstacle time"
            );
        }

        List<ContactConstraintProjector.Constraint> result =
                new ArrayList<>();

        boolean[] active =
                new boolean[contacts.contacts().size()];

        Vector3d velocity =
                new Vector3d(desiredVelocity);

        while (true) {
            boolean added = false;

            for (int i = 0; i < active.length; i++) {
                if (active[i]) {
                    continue;
                }

                CollisionContact contact =
                        contacts.contacts().get(i);

                if (!isEnteringFiniteObstacle(
                        body,
                        contact,
                        velocity,
                        normalizedTime,
                        context
                )) {
                    continue;
                }

                Vector3d responseNormal =
                        characterVelocityConstraintNormal(
                                contact.normal(),
                                frame
                        );

                active[i] = true;

                addConstraint(
                        result,
                        responseNormal,
                        contact.surfaceVelocity()
                );

                added = true;
            }

            if (!added) {
                break;
            }

            var projection =
                    ContactConstraintProjector.project(
                            desiredVelocity,
                            result
                    );

            if (projection.infeasible()) {
                break;
            }

            velocity =
                    projection.requireProjectedVector();
        }

        result.sort(CONSTRAINT_ORDER);

        return List.copyOf(result);
    }

    /**
     * Character locomotion stores free actor velocity, not the geometric rise
     * produced while following a walkable slope.
     *
     * FLOOR:
     *     clip only gravity-down velocity.
     *
     * WALL / CEILING:
     *     retain the real hard-contact normal.
     */
    private static Vector3d characterVelocityConstraintNormal(
            Vector3d contactNormal,
            GravityFrame frame
    ) {
        if (ContinuousSupportPolicy.classify(
                contactNormal,
                frame
        ) == ContinuousSupportPolicy.Classification.FLOOR) {

            return frame.orientation()
                    .axisY(new Vector3d());
        }

        return new Vector3d(contactNormal);
    }

    static boolean isEnteringFiniteObstacle(CollisionBody body, CollisionContact contact,
            Vector3dc desiredVelocity, double normalizedTime, ObbQueryContext context) {
        Vector3d relative = new Vector3d(desiredVelocity).sub(contact.surfaceVelocity());
        if (relative.dot(contact.normal()) >= -CollisionTolerances.ENTERING_PLANE_EPSILON) return false;
        Vector3d probe = relative.normalize(GravityGroundProbe.PROBE_DISTANCE);
        if (!context.workTracker().recordNarrowPhaseTest())
            throw new CollisionComplexityLimitException("finite contact baseline budget");
        double before = CollisionNarrowPhase.staticContactAt(body, contact.obstacle(), normalizedTime, context)
                .map(CollisionContact::penetration).orElse(0.0D);
        if (!context.workTracker().recordNarrowPhaseTest())
            throw new CollisionComplexityLimitException("finite contact probe budget");
        double after = CollisionNarrowPhase.staticContactAt(body.move(probe), contact.obstacle(), normalizedTime, context)
                .map(CollisionContact::penetration).orElse(0.0D);
        return after > before + CollisionTolerances.PENETRATION_EPSILON;
    }

    private static final Comparator<ContactConstraintProjector.Constraint>
            CONSTRAINT_ORDER = (left, right) -> {
        int result = Double.compare(
                left.normal().x,
                right.normal().x
        );
        if (result != 0) return result;
        result = Double.compare(
                left.normal().y,
                right.normal().y
        );
        if (result != 0) return result;
        result = Double.compare(
                left.normal().z,
                right.normal().z
        );
        if (result != 0) return result;
        return Double.compare(
                left.minimumDot(),
                right.minimumDot()
        );
    };

    /**
     * Deterministic merge entry point for already-computed constraints.
     *
     * <p>Same-facing near-parallel duplicates are collapsed by
     * {@link CollisionTolerances#sameConstraintIdentity} keeping the stricter
     * (larger) minimum dot, and the output is sorted deterministically so
     * contact enumeration order never changes the exported set.</p>
     */
    public static List<ContactConstraintProjector.Constraint> merge(
            List<ContactConstraintProjector.Constraint> constraints
    ) {
        Objects.requireNonNull(constraints, "constraints");
        List<ContactConstraintProjector.Constraint> merged =
                new ArrayList<>();
        for (ContactConstraintProjector.Constraint constraint : constraints) {
            addConstraint(
                    merged,
                    Objects.requireNonNull(
                            constraint,
                            "constraint"
                    )
            );
        }
        merged.sort(CONSTRAINT_ORDER);
        return List.copyOf(merged);
    }

    /** Public overload of {@link #addConstraint(List, Vector3d, Vector3d)}. */
    public static void addConstraint(
            List<ContactConstraintProjector.Constraint> result,
            ContactConstraintProjector.Constraint constraint
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(constraint, "constraint");
        addConstraint(
                result,
                constraint.normal(),
                constraint.minimumDot()
        );
    }

    /**
     * Adds one constraint, merging same-facing near-parallel duplicates by
     * keeping the stricter (largest) minimum dot.
     *
     * <p>Surface-velocity affine data is preserved exactly:
     * {@code minimumDot = normal dot surfaceVelocity}. A later homogeneous
     * or weaker duplicate never overwrites it.</p>
     */
    public static void addConstraint(
            List<ContactConstraintProjector.Constraint> result,
            Vector3d normal,
            Vector3d surfaceVelocity
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(surfaceVelocity, "surfaceVelocity");
        addConstraint(
                result,
                normal,
                normal.dot(surfaceVelocity)
        );
    }

    private static void addConstraint(
            List<ContactConstraintProjector.Constraint> result,
            Vector3d normal,
            double minimumDot
    ) {
        for (int i = 0; i < result.size(); i++) {
            ContactConstraintProjector.Constraint existing =
                    result.get(i);
            if (CollisionTolerances.sameConstraintIdentity(
                    existing.normal(),
                    normal
            )) {
                if (minimumDot > existing.minimumDot()) {
                    result.set(
                            i,
                            new ContactConstraintProjector.Constraint(
                                    existing.normal(),
                                    minimumDot
                            )
                    );
                }
                return;
            }
        }
        result.add(
                new ContactConstraintProjector.Constraint(
                        normal,
                        minimumDot
                )
        );
    }

    /**
     * Adds one constraint, merging same-facing near-parallel duplicates by
     * keeping the stricter (largest) minimum dot.
     */
}
