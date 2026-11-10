package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.*;

/**
 * Deterministic Euclidean projection onto world-space half spaces.
 *
 * <p>Solves {@code min 0.5 * |x - desired|^2} subject to
 * {@code normal[i] dot x >= minimumDot[i]}. Every full-rank active set of
 * size zero through three is considered. This class has no game-state
 * dependencies and uses only immutable neutral {@link Vec3d} values.</p>
 */
public final class ContactConstraintProjector {
    public static final double FEASIBILITY_EPSILON = 1.0E-9D;
    public static final double LAMBDA_EPSILON = 1.0E-10D;
    public static final double RANK_EPSILON = 1.0E-14D;
    private static final double DISTANCE_TIE_EPSILON = 1.0E-12D;

    private ContactConstraintProjector() {}

    public static Result project(Vec3d desired, List<Constraint> inputConstraints) {
        requireFinite(desired, "desired");
        Objects.requireNonNull(inputConstraints, "inputConstraints");
        ArrayList<Constraint> sorted = new ArrayList<>(inputConstraints.size());
        for (Constraint constraint : inputConstraints) {
            sorted.add(Objects.requireNonNull(constraint, "constraint"));
        }
        sorted.sort(CONSTRAINT_COMPARATOR);
        List<Constraint> constraints = List.copyOf(sorted);
        List<Constraint> enumerationConstraints = reduceNearParallel(constraints);

        ProjectionWorkspace workspace = new ProjectionWorkspace();
        // Feasibility tolerance validates computed active-set candidates; it
        // is not permission for a known inward input to win at zero cost.
        // Repeated sub-threshold violations otherwise accumulate through CCD's
        // touching band and an unchanged residual cannot finish its contact.
        if (constraints.stream().allMatch(c -> c.normal().dot(desired) >= c.minimumDot()
                - cc.sighs.gravityengine.math.geometry.GeometryTolerance.COMPARISON)) {
            workspace.setUnconstrained(desired);
        }
        int count = enumerationConstraints.size();
        for (int first = 0; first < count; first++) {
            evaluateGeneral(
                    desired,
                    constraints,
                    workspace,
                    enumerationConstraints.get(first),
                    null,
                    null,
                    1
            );
        }
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                evaluateTwoPlane(
                        desired,
                        constraints,
                        workspace,
                        enumerationConstraints.get(first),
                        enumerationConstraints.get(second)
                );
            }
        }
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                for (int third = second + 1; third < count; third++) {
                    evaluateGeneral(
                            desired,
                            constraints,
                            workspace,
                            enumerationConstraints.get(first),
                            enumerationConstraints.get(second),
                            enumerationConstraints.get(third),
                            3
                    );
                }
            }
        }

        if (!workspace.hasBest) return Result.infeasible(constraints);
        Status status = workspace.bestUnconstrained
                ? Status.UNCONSTRAINED : Status.PROJECTED;
        Vec3d projectedVector = workspace.bestUnconstrained
                ? desired
                : new Vec3d(
                        workspace.bestX,
                        workspace.bestY,
                        workspace.bestZ
                );
        Vec3d affineOffset = workspace.bestUnconstrained
                ? Vec3d.ZERO
                : new Vec3d(
                        workspace.affineX,
                        workspace.affineY,
                        workspace.affineZ
                );
        LinearPart linearPart = workspace.bestUnconstrained
                ? LinearPart.IDENTITY
                : new LinearPart(
                        new Vec3d(
                                workspace.bestXColumnX,
                                workspace.bestXColumnY,
                                workspace.bestXColumnZ
                        ),
                        new Vec3d(
                                workspace.bestYColumnX,
                                workspace.bestYColumnY,
                                workspace.bestYColumnZ
                        ),
                        new Vec3d(
                                workspace.bestZColumnX,
                                workspace.bestZColumnY,
                                workspace.bestZColumnZ
                        )
                );
        return new Result(
                status,
                projectedVector,
                constraints,
                workspace.activePlanes(),
                workspace.bestRank,
                linearPart,
                affineOffset,
                workspace.bestSquaredCorrection
        );
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
    public static Vec3d projectNonPenetrating(
            Vec3d desired,
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
        Vec3d resolved = desired;
        for (int pass = 0; pass < staticConstraints.size(); pass++) {
            boolean changed = false;
            for (Constraint constraint : staticConstraints) {
                Vec3d normal = constraint.normal();
                double inward = resolved.dot(normal);
                if (inward < 0.0D) {
                    resolved = resolved.fma(-inward, normal);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return resolved;
    }

    private static void evaluateGeneral(
            Vec3d desired,
            List<Constraint> all,
            ProjectionWorkspace workspace,
            Constraint first,
            Constraint second,
            Constraint third,
            int rank
    ) {
        fillGram(workspace, first, second, third, rank);
        double rhsFirst = first.minimumDot() - first.normal().dot(desired);
        double rhsSecond = rank > 1
                ? second.minimumDot() - second.normal().dot(desired) : 0.0D;
        double rhsThird = rank > 2
                ? third.minimumDot() - third.normal().dot(desired) : 0.0D;
        if (!solve(workspace, rank, rhsFirst, rhsSecond, rhsThird)) return;
        if (!validMultipliers(workspace, rank)) return;

        combineNormals(
                first,
                second,
                third,
                rank,
                workspace.solution[0],
                rank > 1 ? workspace.solution[1] : 0.0D,
                rank > 2 ? workspace.solution[2] : 0.0D,
                workspace.combined
        );
        double vectorX = snapNearZero(desired.x() + workspace.combined[0]);
        double vectorY = snapNearZero(desired.y() + workspace.combined[1]);
        double vectorZ = snapNearZero(desired.z() + workspace.combined[2]);
        if (!isFeasible(vectorX, vectorY, vectorZ, all)) return;
        if (!activeConstraintsMet(first, second, third, rank, vectorX, vectorY, vectorZ)) return;

        if (!projectLinearBasis(workspace, first, second, third, rank, 1.0D, 0.0D, 0.0D,
                Vec3d.X, 0)) return;
        if (!projectLinearBasis(workspace, first, second, third, rank, 0.0D, 1.0D, 0.0D,
                Vec3d.Y, 3)) return;
        if (!projectLinearBasis(workspace, first, second, third, rank, 0.0D, 0.0D, 1.0D,
                Vec3d.Z, 6)) return;

        if (!solve(
                workspace,
                rank,
                first.minimumDot(),
                rank > 1 ? second.minimumDot() : 0.0D,
                rank > 2 ? third.minimumDot() : 0.0D
        )) return;
        double scaleFirst = workspace.solution[0];
        double scaleSecond = rank > 1 ? workspace.solution[1] : 0.0D;
        double scaleThird = rank > 2 ? workspace.solution[2] : 0.0D;
        combineNormals(
                first,
                second,
                third,
                rank,
                scaleFirst,
                scaleSecond,
                scaleThird,
                workspace.combined
        );
        double affineX = workspace.combined[0];
        double affineY = workspace.combined[1];
        double affineZ = workspace.combined[2];
        if (!Double.isFinite(affineX)
                || !Double.isFinite(affineY)
                || !Double.isFinite(affineZ)) return;

        double reconstructedX = workspace.xColumnX * desired.x()
                + workspace.yColumnX * desired.y()
                + workspace.zColumnX * desired.z()
                + affineX;
        double reconstructedY = workspace.xColumnY * desired.x()
                + workspace.yColumnY * desired.y()
                + workspace.zColumnY * desired.z()
                + affineY;
        double reconstructedZ = workspace.xColumnZ * desired.x()
                + workspace.yColumnZ * desired.y()
                + workspace.zColumnZ * desired.z()
                + affineZ;
        double correctionX = reconstructedX - vectorX;
        double correctionY = reconstructedY - vectorY;
        double correctionZ = reconstructedZ - vectorZ;
        if (!Double.isFinite(reconstructedX)
                || !Double.isFinite(reconstructedY)
                || !Double.isFinite(reconstructedZ)) return;
        double reconstructionError = correctionX * correctionX
                + correctionY * correctionY
                + correctionZ * correctionZ;
        if (reconstructionError > 1.0E-18D) return;

        double correction = (vectorX - desired.x()) * (vectorX - desired.x())
                + (vectorY - desired.y()) * (vectorY - desired.y())
                + (vectorZ - desired.z()) * (vectorZ - desired.z());
        consider(
                workspace,
                rank,
                vectorX,
                vectorY,
                vectorZ,
                correction,
                first,
                second,
                third,
                affineX,
                affineY,
                affineZ
        );
    }

    /**
     * Stable rank-two projection without squaring the normals' condition
     * number. The scalar form preserves the cross-product/orthogonal-basis
     * solve used by the original two-plane path.
     */
    private static void evaluateTwoPlane(
            Vec3d desired,
            List<Constraint> all,
            ProjectionWorkspace workspace,
            Constraint first,
            Constraint second
    ) {
        Vec3d u = first.normal();
        Vec3d n2 = second.normal();

        double crossX = u.y() * n2.z() - u.z() * n2.y();
        double crossY = u.z() * n2.x() - u.x() * n2.z();
        double crossZ = u.x() * n2.y() - u.y() * n2.x();
        double sine = Math.sqrt(
                crossX * crossX + crossY * crossY + crossZ * crossZ
        );
        if (!(sine > RANK_EPSILON) || !Double.isFinite(sine)) return;

        double inverseSine = 1.0D / sine;
        double wX = crossX * inverseSine;
        double wY = crossY * inverseSine;
        double wZ = crossZ * inverseSine;
        double vX = wY * u.z() - wZ * u.y();
        double vY = wZ * u.x() - wX * u.z();
        double vZ = wX * u.y() - wY * u.x();
        if (!Double.isFinite(vX)
                || !Double.isFinite(vY)
                || !Double.isFinite(vZ)
                || !Double.isFinite(wX)
                || !Double.isFinite(wY)
                || !Double.isFinite(wZ)) return;

        double cosine = u.x() * n2.x() + u.y() * n2.y() + u.z() * n2.z();
        double firstBound = first.minimumDot();
        double secondBound =
                (second.minimumDot() - cosine * firstBound) / sine;
        if (!Double.isFinite(secondBound)) return;
        double affineX = u.x() * firstBound + vX * secondBound;
        double affineY = u.y() * firstBound + vY * secondBound;
        double affineZ = u.z() * firstBound + vZ * secondBound;
        double desiredAlongW = wX * desired.x()
                + wY * desired.y()
                + wZ * desired.z();
        double vectorX = snapNearZero(affineX + wX * desiredAlongW);
        double vectorY = snapNearZero(affineY + wY * desiredAlongW);
        double vectorZ = snapNearZero(affineZ + wZ * desiredAlongW);
        if (!isFeasible(vectorX, vectorY, vectorZ, all)) return;
        if (!activeConstraintsMet(first, second, null, 2, vectorX, vectorY, vectorZ)) return;

        double correctionX = vectorX - desired.x();
        double correctionY = vectorY - desired.y();
        double correctionZ = vectorZ - desired.z();
        double lambdaSecond = (
                correctionX * vX + correctionY * vY + correctionZ * vZ
        ) / sine;
        double lambdaFirst = correctionX * u.x()
                + correctionY * u.y()
                + correctionZ * u.z()
                - cosine * lambdaSecond;
        if (!Double.isFinite(lambdaFirst)
                || !Double.isFinite(lambdaSecond)
                || lambdaFirst < -LAMBDA_EPSILON
                || lambdaSecond < -LAMBDA_EPSILON) return;

        workspace.xColumnX = wX * wX;
        workspace.xColumnY = wY * wX;
        workspace.xColumnZ = wZ * wX;
        workspace.yColumnX = wX * wY;
        workspace.yColumnY = wY * wY;
        workspace.yColumnZ = wZ * wY;
        workspace.zColumnX = wX * wZ;
        workspace.zColumnY = wY * wZ;
        workspace.zColumnZ = wZ * wZ;

        double reconstructedX = workspace.xColumnX * desired.x()
                + workspace.yColumnX * desired.y()
                + workspace.zColumnX * desired.z()
                + affineX;
        double reconstructedY = workspace.xColumnY * desired.x()
                + workspace.yColumnY * desired.y()
                + workspace.zColumnY * desired.z()
                + affineY;
        double reconstructedZ = workspace.xColumnZ * desired.x()
                + workspace.yColumnZ * desired.y()
                + workspace.zColumnZ * desired.z()
                + affineZ;
        double errorX = reconstructedX - vectorX;
        double errorY = reconstructedY - vectorY;
        double errorZ = reconstructedZ - vectorZ;
        if (!Double.isFinite(reconstructedX)
                || !Double.isFinite(reconstructedY)
                || !Double.isFinite(reconstructedZ)) return;
        if (errorX * errorX + errorY * errorY + errorZ * errorZ > 1.0E-18D) return;

        consider(
                workspace,
                2,
                vectorX,
                vectorY,
                vectorZ,
                correctionX * correctionX
                        + correctionY * correctionY
                        + correctionZ * correctionZ,
                first,
                second,
                null,
                affineX,
                affineY,
                affineZ
        );
    }

    private static void fillGram(
            ProjectionWorkspace workspace,
            Constraint first,
            Constraint second,
            Constraint third,
            int rank
    ) {
        workspace.gram[0] = first.normal().dot(first.normal());
        if (rank < 2) return;
        workspace.gram[1] = first.normal().dot(second.normal());
        workspace.gram[3] = second.normal().dot(first.normal());
        workspace.gram[4] = second.normal().dot(second.normal());
        if (rank < 3) return;
        workspace.gram[2] = first.normal().dot(third.normal());
        workspace.gram[5] = second.normal().dot(third.normal());
        workspace.gram[6] = third.normal().dot(first.normal());
        workspace.gram[7] = third.normal().dot(second.normal());
        workspace.gram[8] = third.normal().dot(third.normal());
    }

    private static boolean projectLinearBasis(
            ProjectionWorkspace workspace,
            Constraint first,
            Constraint second,
            Constraint third,
            int rank,
            double basisX,
            double basisY,
            double basisZ,
            Vec3d basis,
            int columnOffset
    ) {
        if (!solve(
                workspace,
                rank,
                -first.normal().dot(basis),
                rank > 1 ? -second.normal().dot(basis) : 0.0D,
                rank > 2 ? -third.normal().dot(basis) : 0.0D
        )) return false;
        combineNormals(
                first,
                second,
                third,
                rank,
                workspace.solution[0],
                rank > 1 ? workspace.solution[1] : 0.0D,
                rank > 2 ? workspace.solution[2] : 0.0D,
                workspace.combined
        );
        double columnX = basisX + workspace.combined[0];
        double columnY = basisY + workspace.combined[1];
        double columnZ = basisZ + workspace.combined[2];
        if (!Double.isFinite(columnX)
                || !Double.isFinite(columnY)
                || !Double.isFinite(columnZ)) {
            throw new IllegalArgumentException("linear part must be finite");
        }
        if (columnOffset == 0) {
            workspace.xColumnX = columnX;
            workspace.xColumnY = columnY;
            workspace.xColumnZ = columnZ;
        } else if (columnOffset == 3) {
            workspace.yColumnX = columnX;
            workspace.yColumnY = columnY;
            workspace.yColumnZ = columnZ;
        } else {
            workspace.zColumnX = columnX;
            workspace.zColumnY = columnY;
            workspace.zColumnZ = columnZ;
        }
        return true;
    }

    /** Pivoted elimination for the only supported sizes: 1, 2, and 3. */
    private static boolean solve(
            ProjectionWorkspace workspace,
            int size,
            double rhsFirst,
            double rhsSecond,
            double rhsThird
    ) {
        if (size == 0) return true;
        if (size > 3) throw new IllegalArgumentException("matrix size");
        double[] augmented = workspace.augmented;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                augmented[row * 4 + column] =
                        workspace.gram[row * 3 + column];
            }
            augmented[row * 4 + size] = switch (row) {
                case 0 -> rhsFirst;
                case 1 -> rhsSecond;
                default -> rhsThird;
            };
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int bestRow = pivot;
            double bestMagnitude = Math.abs(augmented[pivot * 4 + pivot]);
            for (int row = pivot + 1; row < size; row++) {
                double magnitude = Math.abs(augmented[row * 4 + pivot]);
                if (magnitude > bestMagnitude) {
                    bestMagnitude = magnitude;
                    bestRow = row;
                }
            }
            if (!(bestMagnitude > RANK_EPSILON)) return false;
            if (bestRow != pivot) {
                for (int column = 0; column <= size; column++) {
                    int left = pivot * 4 + column;
                    int right = bestRow * 4 + column;
                    double swap = augmented[left];
                    augmented[left] = augmented[right];
                    augmented[right] = swap;
                }
            }
            double divisor = augmented[pivot * 4 + pivot];
            for (int column = pivot; column <= size; column++) {
                augmented[pivot * 4 + column] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == pivot) continue;
                double factor = augmented[row * 4 + pivot];
                for (int column = pivot; column <= size; column++) {
                    augmented[row * 4 + column] -=
                            factor * augmented[pivot * 4 + column];
                }
            }
        }
        for (int row = 0; row < size; row++) {
            double value = augmented[row * 4 + size];
            if (!Double.isFinite(value)) return false;
            workspace.solution[row] = value;
        }
        return true;
    }

    private static boolean validMultipliers(
            ProjectionWorkspace workspace,
            int rank
    ) {
        for (int index = 0; index < rank; index++) {
            double value = workspace.solution[index];
            if (!Double.isFinite(value) || value < -LAMBDA_EPSILON) return false;
        }
        return true;
    }

    private static void combineNormals(
            Constraint first,
            Constraint second,
            Constraint third,
            int rank,
            double firstScale,
            double secondScale,
            double thirdScale,
            double[] result
    ) {
        result[0] = 0.0D;
        result[1] = 0.0D;
        result[2] = 0.0D;
        if (rank > 0) {
            result[0] = result[0] + firstScale * first.normal().x();
            result[1] = result[1] + firstScale * first.normal().y();
            result[2] = result[2] + firstScale * first.normal().z();
        }
        if (rank > 1) {
            result[0] = result[0] + secondScale * second.normal().x();
            result[1] = result[1] + secondScale * second.normal().y();
            result[2] = result[2] + secondScale * second.normal().z();
        }
        if (rank > 2) {
            result[0] = result[0] + thirdScale * third.normal().x();
            result[1] = result[1] + thirdScale * third.normal().y();
            result[2] = result[2] + thirdScale * third.normal().z();
        }
    }

    private static boolean isFeasible(
            double x,
            double y,
            double z,
            List<Constraint> constraints
    ) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        for (Constraint constraint : constraints) {
            Vec3d normal = constraint.normal();
            double dot = normal.x() * x + normal.y() * y + normal.z() * z;
            if (dot < constraint.minimumDot() - FEASIBILITY_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean activeConstraintsMet(
            Constraint first,
            Constraint second,
            Constraint third,
            int rank,
            double x,
            double y,
            double z
    ) {
        if (!activeConstraintMet(first, x, y, z)) return false;
        if (rank > 1 && !activeConstraintMet(second, x, y, z)) return false;
        return rank <= 2 || activeConstraintMet(third, x, y, z);
    }

    private static boolean activeConstraintMet(
            Constraint constraint,
            double x,
            double y,
            double z
    ) {
        Vec3d normal = constraint.normal();
        double dot = normal.x() * x + normal.y() * y + normal.z() * z;
        return Math.abs(dot - constraint.minimumDot())
                <= FEASIBILITY_EPSILON * 4.0D;
    }

    private static void consider(
            ProjectionWorkspace workspace,
            int rank,
            double vectorX,
            double vectorY,
            double vectorZ,
            double squaredCorrection,
            Constraint first,
            Constraint second,
            Constraint third,
            double affineX,
            double affineY,
            double affineZ
    ) {
        if (!workspace.hasBest
                || isBetter(
                        workspace,
                        rank,
                        squaredCorrection,
                        first,
                        second,
                        third
                )) {
            workspace.setCandidate(
                    rank,
                    vectorX,
                    vectorY,
                    vectorZ,
                    squaredCorrection,
                    first,
                    second,
                    third,
                    affineX,
                    affineY,
                    affineZ
            );
        }
    }

    private static boolean isBetter(
            ProjectionWorkspace workspace,
            int rank,
            double squaredCorrection,
            Constraint first,
            Constraint second,
            Constraint third
    ) {
        double scale = Math.max(
                1.0D,
                Math.max(workspace.bestSquaredCorrection, squaredCorrection)
        );
        double tolerance = DISTANCE_TIE_EPSILON * scale;
        if (squaredCorrection
                < workspace.bestSquaredCorrection - tolerance) return true;
        if (workspace.bestSquaredCorrection
                < squaredCorrection - tolerance) return false;
        int rankComparison = Integer.compare(rank, workspace.bestRank);
        if (rankComparison < 0) return true;
        if (rankComparison > 0) return false;
        return compareActive(
                first,
                second,
                third,
                rank,
                workspace.bestFirst,
                workspace.bestSecond,
                workspace.bestThird
        ) < 0;
    }

    private static int compareActive(
            Constraint first,
            Constraint second,
            Constraint third,
            int rank,
            Constraint bestFirst,
            Constraint bestSecond,
            Constraint bestThird
    ) {
        for (int index = 0; index < rank; index++) {
            Constraint left = activeAt(first, second, third, index);
            Constraint right = activeAt(bestFirst, bestSecond, bestThird, index);
            int comparison = CONSTRAINT_COMPARATOR.compare(left, right);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static Constraint activeAt(
            Constraint first,
            Constraint second,
            Constraint third,
            int index
    ) {
        return switch (index) {
            case 0 -> first;
            case 1 -> second;
            default -> third;
        };
    }

    public static boolean isFeasible(Vec3d vector, List<Constraint> constraints) {
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

    private static double snapNearZero(double value) {
        return Math.abs(value) <= 1.0E-15D ? 0.0D : value;
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!isFinite(vector)) throw new IllegalArgumentException(name + " must be finite: " + vector);
    }

    private static boolean isFinite(Vec3d vector) {
        return vector != null && Double.isFinite(vector.x())
                && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    private static int compareVec(Vec3d left, Vec3d right) {
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

    public record Constraint(Vec3d normal, double minimumDot) {
        public Constraint {
            requireFinite(normal, "normal");
            if (!Double.isFinite(minimumDot)) {
                throw new IllegalArgumentException("minimumDot must be finite: " + minimumDot);
            }
            double lengthSquared = normal.lengthSquared();
            if (!Double.isFinite(lengthSquared)) {
                throw new IllegalArgumentException(
                        "normal must be non-degenerate: " + normal
                );
            }
            double length = Math.sqrt(lengthSquared);
            if (!(length > CollisionTolerances.ZERO_VECTOR_EPSILON)) {
                throw new IllegalArgumentException("normal must be non-zero: " + normal);
            }
            double inverseLength = 1.0D / length;
            normal = new Vec3d(
                    normal.x() * inverseLength,
                    normal.y() * inverseLength,
                    normal.z() * inverseLength
            );
            minimumDot /= length;
        }

        /** The immutable normalized normal. */
        public Vec3d normal() {
            return normal;
        }
    }

    public enum Status { UNCONSTRAINED, PROJECTED, INFEASIBLE }

    /** Column-major immutable 3x3 linear transform. */
    public record LinearPart(Vec3d xColumn, Vec3d yColumn, Vec3d zColumn) {
        public static final LinearPart IDENTITY = new LinearPart(
                new Vec3d(1.0D, 0.0D, 0.0D),
                new Vec3d(0.0D, 1.0D, 0.0D),
                new Vec3d(0.0D, 0.0D, 1.0D)
        );

        public LinearPart {
            requireFinite(xColumn, "xColumn");
            requireFinite(yColumn, "yColumn");
            requireFinite(zColumn, "zColumn");
        }

        /** The immutable column. */
        public Vec3d xColumn() {
            return xColumn;
        }

        public Vec3d apply(Vec3d vector) {
            requireFinite(vector, "vector");
            double x = vector.x();
            double y = vector.y();
            double z = vector.z();
            return new Vec3d(
                    xColumn.x() * x + yColumn.x() * y + zColumn.x() * z,
                    xColumn.y() * x + yColumn.y() * y + zColumn.y() * z,
                    xColumn.z() * x + yColumn.z() * y + zColumn.z() * z
            );
        }
    }

    public record Result(
            Status status,
            Vec3d projectedVectorOrNull,
            List<Constraint> constraints,
            List<Constraint> activePlanes,
            int activeRank,
            LinearPart linearPart,
            Vec3d affineOffset,
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
            } else if (projectedVectorOrNull != null) {
                throw new IllegalArgumentException("infeasible result may not expose a vector");
            }
        }

        /** The immutable projected vector when present. */
        public Vec3d projectedVectorOrNull() {
            return projectedVectorOrNull;
        }

        public Optional<Vec3d> projectedVector() {
            return Optional.ofNullable(projectedVectorOrNull);
        }

        public Vec3d requireProjectedVector() {
            Vec3d projected = projectedVectorOrNull;
            if (projected == null) {
                throw new IllegalStateException("constraint set is infeasible");
            }
            return projected;
        }

        public boolean infeasible() { return status == Status.INFEASIBLE; }

        private static Result infeasible(List<Constraint> constraints) {
            return new Result(Status.INFEASIBLE, null, constraints, List.of(), 0,
                    LinearPart.IDENTITY, Vec3d.ZERO, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * Operation-owned primitive storage reused by every active-set candidate
     * in one projection. No instance escapes this class.
     */
    private static final class ProjectionWorkspace {
        private final double[] gram = new double[9];
        private final double[] augmented = new double[12];
        private final double[] solution = new double[3];
        private final double[] combined = new double[3];

        private boolean hasBest;
        private boolean bestUnconstrained;
        private double bestX;
        private double bestY;
        private double bestZ;
        private double bestSquaredCorrection;
        private int bestRank;
        private Constraint bestFirst;
        private Constraint bestSecond;
        private Constraint bestThird;
        private double xColumnX;
        private double xColumnY;
        private double xColumnZ;
        private double yColumnX;
        private double yColumnY;
        private double yColumnZ;
        private double zColumnX;
        private double zColumnY;
        private double zColumnZ;
        private double bestXColumnX;
        private double bestXColumnY;
        private double bestXColumnZ;
        private double bestYColumnX;
        private double bestYColumnY;
        private double bestYColumnZ;
        private double bestZColumnX;
        private double bestZColumnY;
        private double bestZColumnZ;
        private double affineX;
        private double affineY;
        private double affineZ;

        private void setUnconstrained(Vec3d desired) {
            this.hasBest = true;
            this.bestUnconstrained = true;
            this.bestX = desired.x();
            this.bestY = desired.y();
            this.bestZ = desired.z();
            this.bestSquaredCorrection = 0.0D;
            this.bestRank = 0;
            this.bestFirst = null;
            this.bestSecond = null;
            this.bestThird = null;
            this.bestXColumnX = 1.0D;
            this.bestXColumnY = 0.0D;
            this.bestXColumnZ = 0.0D;
            this.bestYColumnX = 0.0D;
            this.bestYColumnY = 1.0D;
            this.bestYColumnZ = 0.0D;
            this.bestZColumnX = 0.0D;
            this.bestZColumnY = 0.0D;
            this.bestZColumnZ = 1.0D;
            this.affineX = 0.0D;
            this.affineY = 0.0D;
            this.affineZ = 0.0D;
        }

        private void setCandidate(
                int rank,
                double vectorX,
                double vectorY,
                double vectorZ,
                double squaredCorrection,
                Constraint first,
                Constraint second,
                Constraint third,
                double affineX,
                double affineY,
                double affineZ
        ) {
            this.hasBest = true;
            this.bestUnconstrained = false;
            this.bestX = vectorX;
            this.bestY = vectorY;
            this.bestZ = vectorZ;
            this.bestSquaredCorrection = squaredCorrection;
            this.bestRank = rank;
            this.bestFirst = first;
            this.bestSecond = second;
            this.bestThird = third;
            this.bestXColumnX = this.xColumnX;
            this.bestXColumnY = this.xColumnY;
            this.bestXColumnZ = this.xColumnZ;
            this.bestYColumnX = this.yColumnX;
            this.bestYColumnY = this.yColumnY;
            this.bestYColumnZ = this.yColumnZ;
            this.bestZColumnX = this.zColumnX;
            this.bestZColumnY = this.zColumnY;
            this.bestZColumnZ = this.zColumnZ;
            this.affineX = affineX;
            this.affineY = affineY;
            this.affineZ = affineZ;
        }

        private List<Constraint> activePlanes() {
            return switch (bestRank) {
                case 0 -> List.of();
                case 1 -> List.of(bestFirst);
                case 2 -> List.of(bestFirst, bestSecond);
                default -> List.of(bestFirst, bestSecond, bestThird);
            };
        }
    }
}
