package cc.sighs.gravityengine.gravity.collision;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Deterministic Euclidean projection onto world-space half spaces.
 *
 * <p>Solves {@code min 0.5 * |x - desired|^2} subject to
 * {@code normal[i] dot x >= minimumDot[i]}. Every full-rank active set of
 * size zero through three is considered. This class has no game-state
 * dependencies and uses only neutral JOML vectors.</p>
 */
public final class ContactConstraintProjector {
    public static final double FEASIBILITY_EPSILON = 1.0E-9D;
    public static final double LAMBDA_EPSILON = 1.0E-10D;
    public static final double RANK_EPSILON = 1.0E-14D;
    private static final double DISTANCE_TIE_EPSILON = 1.0E-12D;

    private ContactConstraintProjector() {}

    public static Result project(Vector3dc desired, List<Constraint> inputConstraints) {
        requireFinite(desired, "desired");
        Objects.requireNonNull(inputConstraints, "inputConstraints");
        List<Constraint> constraints = inputConstraints.stream()
                .map(constraint -> Objects.requireNonNull(constraint, "constraint"))
                .sorted(CONSTRAINT_COMPARATOR)
                .toList();
        List<Constraint> enumerationConstraints = reduceNearParallel(constraints);

        Candidate best = isFeasible(desired, constraints)
                ? Candidate.unconstrained(new Vector3d(desired)) : null;
        int count = enumerationConstraints.size();
        for (int first = 0; first < count; first++) {
            best = choose(best, candidate(desired, constraints,
                    List.of(enumerationConstraints.get(first))));
        }
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                best = choose(best, candidate(desired, constraints,
                        List.of(enumerationConstraints.get(first), enumerationConstraints.get(second))));
            }
        }
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                for (int third = second + 1; third < count; third++) {
                    best = choose(best, candidate(desired, constraints, List.of(
                            enumerationConstraints.get(first), enumerationConstraints.get(second),
                            enumerationConstraints.get(third)
                    )));
                }
            }
        }

        if (best == null) return Result.infeasible(constraints);
        Status status = best.active().isEmpty() ? Status.UNCONSTRAINED : Status.PROJECTED;
        return new Result(status, best.vector(), constraints, best.active(),
                best.active().size(), best.linearPart(), best.affineOffset(),
                best.squaredCorrection());
    }

    /**
     * Deterministic non-penetrating velocity fallback for an infeasible affine
     * contact set.
     *
     * <p>An infeasible set (for example a rising support demanding upward
     * velocity while a ceiling simultaneously forbids it) has no exact
     * solution. The player's translation has already been resolved by the
     * collision solver and must never be rejected for this reason, so a
     * velocity response still needs a finite committed vector. This method
     * projects onto the stricter static half-spaces {@code n_i dot v >= 0},
     * whose feasible region always contains the zero vector. The projection
     * keeps every unconstrained tangent component, never adds momentum, and
     * removes only velocity that would drive the body into the contact it is
     * already inside.</p>
     *
     * <p>The result is deliberately independent of the affine bounds, so it is
     * stable under constraint order and under the infeasibility cause.</p>
     */
    public static Vector3d projectNonPenetrating(
            Vector3dc desired,
            List<Constraint> inputConstraints
    ) {
        requireFinite(desired, "desired");
        Objects.requireNonNull(inputConstraints, "inputConstraints");
        List<Constraint> staticConstraints = inputConstraints.stream()
                .map(constraint -> Objects.requireNonNull(constraint, "constraint"))
                .map(constraint -> new Constraint(
                        constraint.normal(), 0.0D))
                .sorted(CONSTRAINT_COMPARATOR)
                .toList();
        Result staticProjection = project(desired, staticConstraints);
        if (!staticProjection.infeasible()) {
            return staticProjection.requireProjectedVector();
        }
        /*
         * Static unilateral half-spaces share the non-negative orthant as a
         * common interior, so the projector above cannot report infeasible
         * unless the input itself is numerically degenerate. Remove inward
         * components deterministically rather than fabricating a vector.
         */
        Vector3d resolved = new Vector3d(desired);
        for (int pass = 0; pass < staticConstraints.size(); pass++) {
            boolean changed = false;
            for (Constraint constraint : staticConstraints) {
                Vector3d normal = constraint.normal();
                double inward = resolved.dot(normal);
                if (inward < 0.0D) {
                    resolved.fma(-inward, normal);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return resolved;
    }

    private static Candidate candidate(Vector3dc desired, List<Constraint> all, List<Constraint> active) {
        if (active.size() == 2) {
            return twoPlaneCandidate(desired, all, active);
        }
        double[][] gram = gram(active);
        double[] rhs = new double[active.size()];
        for (int index = 0; index < active.size(); index++) {
            Constraint constraint = active.get(index);
            rhs[index] = constraint.minimumDot() - constraint.normal().dot(desired);
        }
        double[] lambda = solve(gram, rhs);
        if (lambda == null) return null;
        for (double value : lambda) {
            if (!Double.isFinite(value) || value < -LAMBDA_EPSILON) return null;
        }

        Vector3d vector = snapNearZero(
                new Vector3d(desired).add(combineNormals(active, lambda)));
        if (!isFinite(vector) || !isFeasible(vector, all)) return null;
        for (Constraint constraint : active) {
            if (Math.abs(constraint.normal().dot(vector) - constraint.minimumDot())
                    > FEASIBILITY_EPSILON * 4.0D) return null;
        }

        LinearPart linearPart = buildLinearPart(active, gram);
        if (linearPart == null) return null;
        double[] offsetMultipliers = solve(gram,
                active.stream().mapToDouble(Constraint::minimumDot).toArray());
        if (offsetMultipliers == null) return null;
        Vector3d affineOffset = combineNormals(active, offsetMultipliers);
        Vector3d reconstructed = linearPart.apply(desired).add(affineOffset);
        if (!isFinite(affineOffset)
                || new Vector3d(reconstructed).sub(vector).lengthSquared() > 1.0E-18D) return null;
        return new Candidate(vector, List.copyOf(active), linearPart, affineOffset,
                new Vector3d(vector).sub(desired).lengthSquared());
    }

    /** Stable rank-two projection without squaring the normals' condition number. */
    private static Candidate twoPlaneCandidate(
            Vector3dc desired,
            List<Constraint> all,
            List<Constraint> active
    ) {
        Constraint first = active.get(0);
        Constraint second = active.get(1);
        Vector3d u = first.normal();
        Vector3d cross = new Vector3d(u).cross(second.normal());
        double sine = cross.length();
        // Distinct normals are not discarded merely because their wedge is
        // narrow. This orthogonal-basis solve avoids the squared condition
        // number of the Gram matrix; only a genuinely numerically rank-zero
        // second direction is rejected here.
        if (!(sine > RANK_EPSILON) || !Double.isFinite(sine)) return null;
        Vector3d w = cross.mul(1.0D / sine);
        // cross(cross(u, n2), u) is the component of n2 orthogonal
        // to u. Unlike n2 - u * dot(u, n2), it does not lose that
        // component when dot(u, n2) rounds to +/-1 for a very narrow wedge.
        Vector3d v = new Vector3d(w).cross(u);
        if (!isFinite(v) || !isFinite(w)) return null;

        double cosine = u.dot(second.normal());
        double uBound = first.minimumDot();
        double vBound = (second.minimumDot() - cosine * uBound) / sine;
        if (!Double.isFinite(vBound)) return null;
        Vector3d affineOffset = new Vector3d(u).mul(uBound).add(
                new Vector3d(v).mul(vBound));
        Vector3d vector = snapNearZero(
                new Vector3d(affineOffset).add(
                        new Vector3d(w).mul(desired.dot(w)))
        );
        if (!isFinite(vector) || !isFeasible(vector, all)) return null;
        for (Constraint constraint : active) {
            if (Math.abs(constraint.normal().dot(vector) - constraint.minimumDot())
                    > FEASIBILITY_EPSILON * 4.0D) return null;
        }

        Vector3d correction = new Vector3d(vector).sub(desired);
        double lambdaSecond = correction.dot(v) / sine;
        double lambdaFirst = correction.dot(u) - cosine * lambdaSecond;
        if (!Double.isFinite(lambdaFirst) || !Double.isFinite(lambdaSecond)
                || lambdaFirst < -LAMBDA_EPSILON
                || lambdaSecond < -LAMBDA_EPSILON) return null;

        LinearPart linearPart = new LinearPart(
                new Vector3d(w).mul(w.x), new Vector3d(w).mul(w.y), new Vector3d(w).mul(w.z)
        );
        Vector3d reconstructed = linearPart.apply(desired).add(affineOffset);
        if (new Vector3d(reconstructed).sub(vector).lengthSquared() > 1.0E-18D) return null;
        return new Candidate(
                vector, List.copyOf(active), linearPart, affineOffset,
                correction.lengthSquared()
        );
    }

    private static LinearPart buildLinearPart(List<Constraint> active, double[][] gram) {
        Vector3d x = projectLinearBasis(new Vector3d(1.0D, 0.0D, 0.0D), active, gram);
        Vector3d y = projectLinearBasis(new Vector3d(0.0D, 1.0D, 0.0D), active, gram);
        Vector3d z = projectLinearBasis(new Vector3d(0.0D, 0.0D, 1.0D), active, gram);
        return x == null || y == null || z == null ? null : new LinearPart(x, y, z);
    }

    private static Vector3d projectLinearBasis(Vector3dc basis, List<Constraint> active, double[][] gram) {
        double[] rhs = new double[active.size()];
        for (int index = 0; index < active.size(); index++) {
            rhs[index] = -active.get(index).normal().dot(basis);
        }
        double[] multipliers = solve(gram, rhs);
        return multipliers == null
                ? null
                : new Vector3d(basis).add(combineNormals(active, multipliers));
    }

    private static double[][] gram(List<Constraint> active) {
        double[][] gram = new double[active.size()][active.size()];
        for (int row = 0; row < active.size(); row++) {
            for (int column = 0; column < active.size(); column++) {
                gram[row][column] = active.get(row).normal().dot(active.get(column).normal());
            }
        }
        return gram;
    }

    /** Pivoted elimination for the only supported sizes: 1, 2, and 3. */
    private static double[] solve(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        if (size == 0) return new double[0];
        if (size > 3 || matrix.length != size) throw new IllegalArgumentException("matrix size");
        double[][] augmented = new double[size][size + 1];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, augmented[row], 0, size);
            augmented[row][size] = rhs[row];
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int bestRow = pivot;
            double bestMagnitude = Math.abs(augmented[pivot][pivot]);
            for (int row = pivot + 1; row < size; row++) {
                double magnitude = Math.abs(augmented[row][pivot]);
                if (magnitude > bestMagnitude) {
                    bestMagnitude = magnitude;
                    bestRow = row;
                }
            }
            if (!(bestMagnitude > RANK_EPSILON)) return null;
            if (bestRow != pivot) {
                double[] swap = augmented[pivot];
                augmented[pivot] = augmented[bestRow];
                augmented[bestRow] = swap;
            }
            double divisor = augmented[pivot][pivot];
            for (int column = pivot; column <= size; column++) augmented[pivot][column] /= divisor;
            for (int row = 0; row < size; row++) {
                if (row == pivot) continue;
                double factor = augmented[row][pivot];
                for (int column = pivot; column <= size; column++) {
                    augmented[row][column] -= factor * augmented[pivot][column];
                }
            }
        }
        double[] result = new double[size];
        for (int row = 0; row < size; row++) {
            result[row] = augmented[row][size];
            if (!Double.isFinite(result[row])) return null;
        }
        return result;
    }

    private static Vector3d combineNormals(List<Constraint> active, double[] scales) {
        Vector3d result = new Vector3d();
        for (int index = 0; index < active.size(); index++) {
            result.fma(scales[index], active.get(index).normal());
        }
        return result;
    }

    public static boolean isFeasible(Vector3dc vector, List<Constraint> constraints) {
        if (!isFinite(vector)) return false;
        for (Constraint constraint : constraints) {
            if (constraint.normal().dot(vector)
                    < constraint.minimumDot() - FEASIBILITY_EPSILON) return false;
        }
        return true;
    }

    /** Merge only bit-identical canonical normals; distinct narrow wedges survive. */
    private static List<Constraint> reduceNearParallel(List<Constraint> constraints) {
        ArrayList<Constraint> reduced = new ArrayList<>();
        for (Constraint candidate : constraints) {
            int equivalent = -1;
            for (int index = 0; index < reduced.size(); index++) {
                if (reduced.get(index).normal().equals(candidate.normal())) {
                    equivalent = index;
                    break;
                }
            }
            if (equivalent < 0) {
                reduced.add(candidate);
            } else if (candidate.minimumDot() > reduced.get(equivalent).minimumDot()) {
                reduced.set(equivalent, candidate);
            }
        }
        reduced.sort(CONSTRAINT_COMPARATOR);
        return List.copyOf(reduced);
    }

    private static Vector3d snapNearZero(Vector3d vector) {
        vector.x = snapNearZero(vector.x);
        vector.y = snapNearZero(vector.y);
        vector.z = snapNearZero(vector.z);
        return vector;
    }

    private static double snapNearZero(double value) {
        return Math.abs(value) <= 1.0E-15D ? 0.0D : value;
    }

    private static Candidate choose(Candidate current, Candidate candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        double scale = Math.max(1.0D, Math.max(current.squaredCorrection(), candidate.squaredCorrection()));
        double tolerance = DISTANCE_TIE_EPSILON * scale;
        if (candidate.squaredCorrection() < current.squaredCorrection() - tolerance) return candidate;
        if (current.squaredCorrection() < candidate.squaredCorrection() - tolerance) return current;
        int rank = Integer.compare(candidate.active().size(), current.active().size());
        if (rank < 0) return candidate;
        if (rank > 0) return current;
        return compareActive(candidate.active(), current.active()) < 0 ? candidate : current;
    }

    private static int compareActive(List<Constraint> left, List<Constraint> right) {
        for (int index = 0; index < left.size(); index++) {
            int comparison = CONSTRAINT_COMPARATOR.compare(left.get(index), right.get(index));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static void requireFinite(Vector3dc vector, String name) {
        if (!isFinite(vector)) throw new IllegalArgumentException(name + " must be finite: " + vector);
    }

    private static boolean isFinite(Vector3dc vector) {
        return vector != null && Double.isFinite(vector.x())
                && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    private static int compareVec(Vector3dc left, Vector3dc right) {
        int comparison = Double.compare(left.x(), right.x());
        if (comparison != 0) return comparison;
        comparison = Double.compare(left.y(), right.y());
        if (comparison != 0) return comparison;
        return Double.compare(left.z(), right.z());
    }

    private static final Comparator<Constraint> CONSTRAINT_COMPARATOR = (left, right) -> {
        int comparison = compareVec(left.normal(), right.normal());
        return comparison != 0 ? comparison : Double.compare(left.minimumDot(), right.minimumDot());
    };

    public record Constraint(Vector3d normal, double minimumDot) {
        public Constraint {
            requireFinite(normal, "normal");
            if (!Double.isFinite(minimumDot)) {
                throw new IllegalArgumentException("minimumDot must be finite: " + minimumDot);
            }
            double length = normal.length();
            if (!(length > CollisionTolerances.ZERO_VECTOR_EPSILON)) {
                throw new IllegalArgumentException("normal must be non-zero: " + normal);
            }
            normal = new Vector3d(normal).normalize();
            minimumDot /= length;
        }

        /** Fresh defensive normalized normal copy. */
        public Vector3d normal() {
            return new Vector3d(normal);
        }
    }

    public enum Status { UNCONSTRAINED, PROJECTED, INFEASIBLE }

    /** Column-major immutable 3x3 linear transform. */
    public record LinearPart(Vector3d xColumn, Vector3d yColumn, Vector3d zColumn) {
        public static final LinearPart IDENTITY = new LinearPart(
                new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, 1.0D, 0.0D),
                new Vector3d(0.0D, 0.0D, 1.0D)
        );

        public LinearPart {
            requireFinite(xColumn, "xColumn");
            requireFinite(yColumn, "yColumn");
            requireFinite(zColumn, "zColumn");
            xColumn = new Vector3d(xColumn);
            yColumn = new Vector3d(yColumn);
            zColumn = new Vector3d(zColumn);
        }

        /** Fresh defensive column copy. */
        public Vector3d xColumn() {
            return new Vector3d(xColumn);
        }

        public Vector3d yColumn() {
            return new Vector3d(yColumn);
        }

        public Vector3d zColumn() {
            return new Vector3d(zColumn);
        }

        public Vector3d apply(Vector3dc vector) {
            requireFinite(vector, "vector");
            return new Vector3d(xColumn).mul(vector.x())
                    .add(new Vector3d(yColumn).mul(vector.y()))
                    .add(new Vector3d(zColumn).mul(vector.z()));
        }
    }

    public record Result(
            Status status,
            Vector3d projectedVectorOrNull,
            List<Constraint> constraints,
            List<Constraint> activePlanes,
            int activeRank,
            LinearPart linearPart,
            Vector3d affineOffset,
            double squaredCorrection
    ) {
        public Result {
            Objects.requireNonNull(status, "status");
            constraints = List.copyOf(constraints);
            activePlanes = List.copyOf(activePlanes);
            Objects.requireNonNull(linearPart, "linearPart");
            requireFinite(affineOffset, "affineOffset");
            if (!Double.isFinite(squaredCorrection) && status != Status.INFEASIBLE) {
                throw new IllegalArgumentException("squaredCorrection must be finite");
            }
            if (status != Status.INFEASIBLE) {
                requireFinite(projectedVectorOrNull, "projectedVector");
                if (!isFeasible(projectedVectorOrNull, constraints)) {
                    throw new IllegalArgumentException("projected vector is infeasible");
                }
                projectedVectorOrNull = new Vector3d(projectedVectorOrNull);
            } else if (projectedVectorOrNull != null) {
                throw new IllegalArgumentException("infeasible result may not expose a vector");
            }
            affineOffset = new Vector3d(affineOffset);
        }

        /** Fresh projected-vector copy when present. */
        public Vector3d projectedVectorOrNull() {
            return projectedVectorOrNull == null ? null : new Vector3d(projectedVectorOrNull);
        }

        public Vector3d affineOffset() {
            return new Vector3d(affineOffset);
        }

        public Optional<Vector3d> projectedVector() {
            return Optional.ofNullable(projectedVectorOrNull())
                    .map(Vector3d::new);
        }

        public Vector3d requireProjectedVector() {
            return projectedVector().orElseThrow(
                    () -> new IllegalStateException("constraint set is infeasible"));
        }

        public boolean infeasible() { return status == Status.INFEASIBLE; }

        private static Result infeasible(List<Constraint> constraints) {
            return new Result(Status.INFEASIBLE, null, constraints, List.of(), 0,
                    LinearPart.IDENTITY, new Vector3d(), Double.POSITIVE_INFINITY);
        }
    }

    private record Candidate(Vector3d vector, List<Constraint> active, LinearPart linearPart,
                             Vector3d affineOffset, double squaredCorrection) {
        private static Candidate unconstrained(Vector3d desired) {
            return new Candidate(
                    new Vector3d(desired), List.of(), LinearPart.IDENTITY,
                    new Vector3d(), 0.0D);
        }
    }
}
