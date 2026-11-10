package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
    public static Vec3d tangentPlaneNormal(Vec3d normal, GravityFrame frame) {
        Vec3d up = frame.orientation().axisY();
        double upDot = normal.dot(up);
        if (Math.abs(upDot) >= PURE_GRAVITY_AXIS_DOT) return null;
        Vec3d plane = normal.subtract(up.multiply(upDot));
        if (plane.lengthSquared() <= CollisionTolerances.PARALLEL_PLANE_CROSS_SQUARED) return null;
        return plane.normalized();
    }

    /**
     * Current contact is evidence, not an infinite wall. Activate a plane only
     * when this relative velocity deepens exact overlap with its finite obstacle
     * at the frozen normalized obstacle instant.
     *
     * <p>For stationary non-voxel continuous FLOOR contact, character persistent velocity
     * retains Vanilla-like locomotion semantics: only gravity-down velocity is
     * clipped. The geometric rise required to follow the slope belongs to the
     * movement route and must not become persistent gravity-up actor velocity.</p>
     *
     * <p>Voxel and moving contacts, walls and ceilings use their real hard-contact
     * normals. Replacing those with gravity-up can re-enter a wall after the
     * position route has correctly stopped, or violate a moving surface's affine
     * normal velocity.</p>
     */
    public static List<ContactConstraintProjector.Constraint> characterConstraints(
            CollisionBody body,
            CurrentContactQuery.Result contacts,
            Vec3d desiredVelocity,
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

        Vec3d velocity =
                desiredVelocity;

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

                Vec3d responseNormal =
                        characterVelocityConstraintNormal(
                                contact,
                                frame
                        );

                active[i] = true;

                addConstraint(
                        result,
                        responseNormal,
                        contact.surfaceVelocity()
                );

                Vec3d edgeNormal = characterEdgeConstraintNormal(
                        contact, desiredVelocity, frame
                );
                if (edgeNormal != null) {
                    addConstraint(result, edgeNormal, Vec3d.ZERO);
                }

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
     * Stationary non-voxel continuous FLOOR:
     *     clip only gravity-down velocity.
     *
     * Voxel / moving / WALL / CEILING:
     *     retain the real hard-contact normal.
     */
    private static Vec3d characterVelocityConstraintNormal(
            CollisionContact contact,
            GravityFrame frame
    ) {
        Vec3d contactNormal = contact.normal();

        if (ContinuousSupportPolicy.classify(contactNormal, frame)
                == ContinuousSupportPolicy.Classification.FLOOR
                && !(contact.obstacle() instanceof BlockObstacle)
                && contact.surfaceVelocity().lengthSquared() == 0) {
            return frame.orientation().axisY();
        }

        return contactNormal;
    }

    static Vec3d characterEdgeConstraintNormal(
            CollisionContact contact,
            Vec3d desired,
            GravityFrame frame
    ) {
        if (!(contact.obstacle() instanceof BlockObstacle)
                || TerrainTraversalPolicy.isWorldAxisNormal(contact.normal())
                || contact.normal().dot(frame.up())
                <= CollisionTolerances.ENTERING_PLANE_EPSILON
                || desired.dot(frame.up())
                > CollisionTolerances.ENTERING_PLANE_EPSILON) {
            return null;
        }

        return tangentPlaneNormal(contact.normal(), frame);
    }

    static boolean isEnteringFiniteObstacle(CollisionBody body, CollisionContact contact,
            Vec3d desiredVelocity, double normalizedTime, ObbQueryContext context) {
        Vec3d relative = desiredVelocity.subtract(contact.surfaceVelocity());
        return entersFiniteObstacle(body, contact, relative, normalizedTime, context);
    }

    /** Activate neighbours against each newly projected residual, using actual
     * finite geometry rather than infinite planes at voxel seams/edges. */
    static ContactConstraintProjector.Result projectDisplacement(
            CollisionBody body,
            Vec3d desired,
            List<ContactConstraintProjector.Constraint> impactConstraints,
            List<CollisionContact> current,
            double remainingTicks,
            double normalizedTime,
            ObbQueryContext context
    ) {
        return projectDisplacement(
                body,
                desired,
                impactConstraints,
                current,
                remainingTicks,
                normalizedTime,
                context,
                contact -> null
        );
    }

    /**
     * 每次投影后重新检查当前有限邻接面。
     * 角色策略约束只补充真实硬接触约束，不替换它。
     */
    static ContactConstraintProjector.Result projectDisplacement(
            CollisionBody body,
            Vec3d desired,
            List<ContactConstraintProjector.Constraint> impactConstraints,
            List<CollisionContact> current,
            double remainingTicks,
            double normalizedTime,
            ObbQueryContext context,
            java.util.function.Function<CollisionContact, Vec3d> additionalNormal
    ) {
        List<ContactConstraintProjector.Constraint> constraints =
                new ArrayList<>(impactConstraints);
        boolean[] active = new boolean[current.size()];

        while (true) {
            var projection =
                    ContactConstraintProjector.project(desired, constraints);

            if (projection.infeasible()) {
                return projection;
            }

            boolean added = false;

            for (int i = 0; i < current.size(); i++) {
                if (active[i]) {
                    continue;
                }

                var contact = current.get(i);
                Vec3d surfaceDisplacement =
                        contact.surfaceVelocity().multiply(remainingTicks);

                if (!entersFiniteObstacle(
                        body,
                        contact,
                        projection.requireProjectedVector()
                                .subtract(surfaceDisplacement),
                        normalizedTime,
                        context
                )) {
                    continue;
                }

                active[i] = true;

                addConstraint(
                        constraints,
                        contact.normal(),
                        surfaceDisplacement
                );

                Vec3d extra = additionalNormal.apply(contact);
                if (extra != null) {
                    addConstraint(constraints, extra, surfaceDisplacement);
                }

                added = true;
            }

            if (!added) {
                return projection;
            }
        }
    }

    private static boolean entersFiniteObstacle(CollisionBody body, CollisionContact contact,
            Vec3d relative, double normalizedTime, ObbQueryContext context) {
        if (relative.dot(contact.normal()) >= -CollisionTolerances.ENTERING_PLANE_EPSILON) return false;
        Vec3d probe = relative.normalized()
                .multiply(GravityGroundProbe.PROBE_DISTANCE);
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
                left.normal().x(),
                right.normal().x()
        );
        if (result != 0) return result;
        result = Double.compare(
                left.normal().y(),
                right.normal().y()
        );
        if (result != 0) return result;
        result = Double.compare(
                left.normal().z(),
                right.normal().z()
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

    /** Public overload of {@link #addConstraint(List, Vec3d, Vec3d)}. */
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
            Vec3d normal,
            Vec3d surfaceVelocity
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
            Vec3d normal,
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
