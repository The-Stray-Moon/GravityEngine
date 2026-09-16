package cc.sighs.gravityengine.gravity.collision.geometry;

import cc.sighs.gravityengine.math.geometry.Obb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.Sphere3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * Pure sphere/OBB collision kernel.
 *
 * <p>Static contact uses the exact signed distance between a
 * {@link Sphere3d} and an axis-arbitrary {@link Obb3d}; translational sweeps
 * use bounded conservative advancement followed by an exact
 * piecewise-quadratic refinement in the OBB's local frame.  Indeterminate
 * numerical states are reported, never turned into misses.  The kernel has no
 * Minecraft or gravity-source dependency and never runs per-body contact
 * methods on obstacle objects.</p>
 */
public final class SphereObbCollision {
    private static final double EPSILON = 1.0E-9D;
    private static final double DISTANCE_EPSILON = 1.0E-12D;
    private static final double SWEEP_TOLERANCE = 1.0E-6D;
    private static final double SWEEP_SPEED_EPSILON = 1.0E-9D;
    private static final double ROOT_TIME_EPSILON = 1.0E-12D;
    private static final double QUADRATIC_EPSILON = 1.0E-24D;
    private static final int MAX_SWEEP_ITERATIONS = 32;

    private SphereObbCollision() {}

    /**
     * Signed distance from sphere surface to OBB surface.  Positive =
     * separated, zero = touching, negative = penetration.
     */
    public static double signedDistance(Sphere3d sphere, Obb3d box) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Vector3d closest = closestPointOnBox(box, sphere.center(), new Vector3d());
        Vector3d offset = new Vector3d(sphere.center()).sub(closest);
        double distSq = offset.lengthSquared();
        if (distSq < DISTANCE_EPSILON) {
            Vector3d local = localPoint(box, sphere.center(), new Vector3d());
            double nearestFaceDist = Math.min(
                    halfX(box) - Math.abs(local.x),
                    Math.min(
                            halfY(box) - Math.abs(local.y),
                            halfZ(box) - Math.abs(local.z)));
            return -(nearestFaceDist + sphere.radius());
        }
        return Math.sqrt(distSq) - sphere.radius();
    }

    public static boolean intersects(Sphere3d sphere, Obb3d box) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Vector3d closest = closestPointOnBox(box, sphere.center(), new Vector3d());
        Vector3d offset = new Vector3d(sphere.center()).sub(closest);
        return offset.lengthSquared() <= sphere.radius() * sphere.radius();
    }

    /** Closest point on the OBB surface/interior to a world point. */
    public static Vector3d closestPointOnBox(
            Obb3d box,
            Vector3dc worldPoint,
            Vector3d dest
    ) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(worldPoint, "worldPoint");
        Objects.requireNonNull(dest, "dest");
        Vector3d local = localPoint(box, worldPoint, new Vector3d());
        local.x = clamp(local.x, -halfX(box), halfX(box));
        local.y = clamp(local.y, -halfY(box), halfY(box));
        local.z = clamp(local.z, -halfZ(box), halfZ(box));
        box.frame().localToWorld(local, dest);
        return dest.add(boxCenter(box, new Vector3d()));
    }

    /** Static contact between one sphere and one OBB. */
    public static SphereObbContact contact(Sphere3d sphere, Obb3d box) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Vector3d center = sphere.center();
        Vector3d closest = closestPointOnBox(box, center, new Vector3d());
        Vector3d fromSphereToBox = new Vector3d(closest).sub(center);
        double distSq = fromSphereToBox.lengthSquared();
        double pen;
        Vector3d normal = new Vector3d();
        Vector3d pointOnBox = new Vector3d();
        OrthonormalFrame3d frame = box.frame();

        if (distSq < DISTANCE_EPSILON) {
            Vector3d localCenter = localPoint(box, center, new Vector3d());
            double[] extents = {halfX(box), halfY(box), halfZ(box)};
            double[] local = {
                    localCenter.x, localCenter.y, localCenter.z
            };
            double minOverlap = Double.MAX_VALUE;
            double[] bestLocalPoint = {
                    localCenter.x, localCenter.y, localCenter.z
            };
            int bestAxis = 0;
            double bestSign = 1.0D;
            Vector3d axis0 = frame.axisX(new Vector3d());
            Vector3d axis1 = frame.axisY(new Vector3d());
            Vector3d axis2 = frame.axisZ(new Vector3d());
            Vector3d[] worldAxes = {axis0, axis1, axis2};
            for (int i = 0; i < 3; i++) {
                double ext = extents[i];
                double ctr = local[i];
                double overlap = ext - Math.abs(ctr);
                if (overlap < minOverlap) {
                    minOverlap = overlap;
                    double sign = ctr < 0.0D ? -1.0D : 1.0D;
                    bestAxis = i;
                    bestSign = sign;
                    bestLocalPoint[i] = ext * sign;
                }
            }
            normal.set(worldAxes[bestAxis]).mul(-bestSign);
            normalizedFinite(normal, worldAxes[0]);
            pen = minOverlap + sphere.radius();
            frame.localToWorld(
                    new Vector3d(
                            bestLocalPoint[0],
                            bestLocalPoint[1],
                            bestLocalPoint[2]),
                    pointOnBox
            );
            pointOnBox.add(boxCenter(box, new Vector3d()));
        } else {
            double dist = Math.sqrt(distSq);
            normal.set(fromSphereToBox).mul(1.0D / dist);
            normalizedFinite(normal, frame.axisX(new Vector3d()));
            pen = sphere.radius() - dist;
            pointOnBox.set(closest);
        }
        if (pen < 0.0D) {
            pen = 0.0D;
        }
        return new SphereObbContact(pointOnBox, normal, pen);
    }

    /**
     * Translational sweep of an OBB against a sphere.  {@code movement} is
     * the OBB's world-space displacement over [0, 1].
     */
    public static Optional<SphereObbSweepHit> sweep(
            Sphere3d sphere,
            Obb3d box,
            Vector3dc movement
    ) {
        SphereSweepResult result = sweepDetailed(sphere, box, movement);
        if (result.status() == SphereSweepResult.Status.INDETERMINATE) {
            throw new IllegalStateException(
                    "indeterminate sphere sweep: " + result.diagnostic());
        }
        return result.hit();
    }

    public static SphereSweepResult sweepDetailed(
            Sphere3d sphere,
            Obb3d box,
            Vector3dc movement
    ) {
        return sweepDetailed(sphere, box, movement, MAX_SWEEP_ITERATIONS);
    }

    /** Testable bounded conservative advancement. */
    public static SphereSweepResult sweepDetailed(
            Sphere3d sphere,
            Obb3d box,
            Vector3dc movement,
            int maxIterations
    ) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(movement, "movement");
        if (!isFinite(movement)) {
            return SphereSweepResult.indeterminate(
                    "non-finite movement: " + movement);
        }
        if (maxIterations < 0) {
            throw new IllegalArgumentException(
                    "maxIterations must be non-negative");
        }
        return sweepConservativeAdvancement(
                sphere, box, movement, SWEEP_TOLERANCE, maxIterations);
    }

    private static SphereSweepResult sweepConservativeAdvancement(
            Sphere3d sphere,
            Obb3d box,
            Vector3dc movement,
            double tolerance,
            int maxIterations
    ) {
        Vector3d movementVector = new Vector3d(movement);
        if (movementVector.lengthSquared() < DISTANCE_EPSILON) {
            SphereObbContact initial = contact(sphere, box);
            double distance = signedDistance(sphere, box);
            if (!Double.isFinite(distance)) {
                return SphereSweepResult.indeterminate(
                        "non-finite zero-motion distance");
            }
            if (distance <= tolerance) {
                return SphereSweepResult.hit(new SphereObbSweepHit(
                        0.0D,
                        initial.normalFromSphereToBox(),
                        initial.pointOnBox()));
            }
            return SphereSweepResult.miss(
                    "zero motion with positive separation");
        }

        double t = 0.0D;
        double maximumClosingSpeed = movementVector.length();

        for (int iter = 0; iter < maxIterations; iter++) {
            Obb3d currentBox = box.moved(movementVector.mul(t, new Vector3d()));
            double distance = signedDistance(sphere, currentBox);
            SphereObbContact currentContact = contact(sphere, currentBox);
            if (!Double.isFinite(distance)) {
                return SphereSweepResult.indeterminate(
                        "non-finite distance at iteration " + iter);
            }

            if (iter == 0 && distance < -tolerance) {
                return SphereSweepResult.hit(new SphereObbSweepHit(
                        0.0D,
                        currentContact.normalFromSphereToBox(),
                        currentContact.pointOnBox()));
            }
            if (distance <= tolerance) {
                return refineTranslationalSweep(sphere, box, movementVector);
            }

            double closingSpeed = -movementVector.dot(
                    currentContact.normalFromSphereToBox());
            if (!Double.isFinite(closingSpeed)) {
                return SphereSweepResult.indeterminate(
                        "non-finite closing speed at iteration " + iter);
            }
            if (closingSpeed <= SWEEP_SPEED_EPSILON) {
                return SphereSweepResult.miss(
                        "convex distance derivative is non-closing at t=" + t);
            }

            double deltaT = distance / maximumClosingSpeed;
            if (!Double.isFinite(deltaT) || deltaT <= 1.0E-15D) {
                return SphereSweepResult.indeterminate(
                        "non-progressing advancement at iteration " + iter);
            }

            double nextT = t + deltaT;
            if (!Double.isFinite(nextT)) {
                return SphereSweepResult.indeterminate(
                        "non-finite TOI advancement at iteration " + iter);
            }
            if (nextT > 1.0D) {
                return SphereSweepResult.miss(
                        "remaining interval is shorter than the Lipschitz "
                                + "separation bound");
            }
            t = nextT;
        }

        if (maxIterations >= MAX_SWEEP_ITERATIONS) {
            return refineTranslationalSweep(sphere, box, movementVector);
        }
        return SphereSweepResult.indeterminate(
                "maximum conservative-advancement iterations reached: "
                        + maxIterations);
    }

    /**
     * Exact translational refinement in the immutable OBB local frame: the
     * moving OBB becomes a point moving against a fixed local AABB whose
     * squared distance is quadratic on each face-crossing interval.
     */
    private static SphereSweepResult refineTranslationalSweep(
            Sphere3d sphere,
            Obb3d box,
            Vector3d movement
    ) {
        double initialDistance = signedDistance(sphere, box);
        SphereObbContact initialContact = contact(sphere, box);
        if (!Double.isFinite(initialDistance)) {
            return SphereSweepResult.indeterminate(
                    "non-finite exact sphere sweep initial distance");
        }
        if (initialDistance < 0.0D) {
            return SphereSweepResult.hit(new SphereObbSweepHit(
                    0.0D,
                    initialContact.normalFromSphereToBox(),
                    initialContact.pointOnBox()));
        }
        if (initialDistance <= EPSILON) {
            double initialClosing = -movement.dot(
                    initialContact.normalFromSphereToBox());
            if (!Double.isFinite(initialClosing)) {
                return SphereSweepResult.indeterminate(
                        "non-finite exact sphere sweep initial closing speed");
            }
            if (initialClosing > SWEEP_SPEED_EPSILON) {
                return SphereSweepResult.hit(new SphereObbSweepHit(
                        0.0D,
                        initialContact.normalFromSphereToBox(),
                        initialContact.pointOnBox()));
            }
            return SphereSweepResult.miss(
                    "initial touching contact is tangent or separating");
        }

        Vector3d center = sphere.center();
        Vector3d pointStart = localPoint(
                box, center, new Vector3d());
        Vector3d pointVelocity = new Vector3d(
                box.frame().worldToLocal(movement, new Vector3d())
        ).mul(-1.0D);
        double[] starts = {pointStart.x, pointStart.y, pointStart.z};
        double[] velocities = {
                pointVelocity.x, pointVelocity.y, pointVelocity.z
        };
        double[] limits = {halfX(box), halfY(box), halfZ(box)};

        List<Double> boundaries = new ArrayList<>();
        boundaries.add(0.0D);
        boundaries.add(1.0D);
        for (int axis = 0; axis < 3; axis++) {
            double velocity = velocities[axis];
            if (Math.abs(velocity) <= SWEEP_SPEED_EPSILON) {
                continue;
            }
            addBoundary(boundaries, (-limits[axis] - starts[axis]) / velocity);
            addBoundary(boundaries, (limits[axis] - starts[axis]) / velocity);
        }
        Collections.sort(boundaries);
        boundaries = mergeBoundaries(boundaries);

        for (int interval = 0; interval + 1 < boundaries.size(); interval++) {
            double minimum = boundaries.get(interval);
            double maximum = boundaries.get(interval + 1);
            if (maximum - minimum <= ROOT_TIME_EPSILON) {
                continue;
            }
            double sample = (minimum + maximum) * 0.5D;
            Quadratic distance = distanceSquaredQuadratic(
                    starts, velocities, limits, sample);
            if (distance == null) {
                return SphereSweepResult.indeterminate(
                        "non-finite exact sphere sweep quadratic");
            }
            QuadraticRootResult rootResult = distance.rootsAgainst(
                    sphere.radius() * sphere.radius());
            if (rootResult.status() == QuadraticRootStatus.INDETERMINATE) {
                return SphereSweepResult.indeterminate(
                        rootResult.diagnostic().isBlank()
                                ? "indeterminate exact sphere sweep quadratic"
                                : rootResult.diagnostic());
            }
            for (double root : rootResult.roots()) {
                if (root < minimum - ROOT_TIME_EPSILON
                        || root > maximum + ROOT_TIME_EPSILON) {
                    continue;
                }
                double time = clamp(root, minimum, maximum);
                Obb3d hitBox = box.moved(
                        movement.mul(time, new Vector3d()));
                SphereObbContact contact = contact(sphere, hitBox);
                double closing = -movement.dot(
                        contact.normalFromSphereToBox());
                if (!Double.isFinite(closing)) {
                    return SphereSweepResult.indeterminate(
                            "non-finite exact sphere sweep closing speed");
                }
                if (closing <= SWEEP_SPEED_EPSILON) {
                    continue;
                }
                return SphereSweepResult.hit(new SphereObbSweepHit(
                        time,
                        contact.normalFromSphereToBox(),
                        contact.pointOnBox()));
            }
        }
        return SphereSweepResult.miss(
                "piecewise-quadratic translational distance has no "
                        + "entering root");
    }

    private static Vector3d localPoint(
            Obb3d box,
            Vector3dc worldPoint,
            Vector3d dest
    ) {
        Vector3d offset = new Vector3d(worldPoint).sub(
                boxCenter(box, new Vector3d()));
        return box.frame().worldToLocal(offset, dest);
    }

    private static Vector3d boxCenter(Obb3d box, Vector3d dest) {
        return box.center(dest);
    }

    private static double halfX(Obb3d box) {
        return box.halfExtents(new Vector3d()).x;
    }

    private static double halfY(Obb3d box) {
        return box.halfExtents(new Vector3d()).y;
    }

    private static double halfZ(Obb3d box) {
        return box.halfExtents(new Vector3d()).z;
    }

    private static void addBoundary(
            List<Double> boundaries,
            double candidate
    ) {
        if (Double.isFinite(candidate)
                && candidate > ROOT_TIME_EPSILON
                && candidate < 1.0D - ROOT_TIME_EPSILON) {
            boundaries.add(candidate);
        }
    }

    private static List<Double> mergeBoundaries(List<Double> sorted) {
        List<Double> merged = new ArrayList<>();
        for (double candidate : sorted) {
            if (merged.isEmpty()
                    || candidate - merged.get(merged.size() - 1)
                    > ROOT_TIME_EPSILON) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static Quadratic distanceSquaredQuadratic(
            double[] starts,
            double[] velocities,
            double[] extents,
            double sampleTime
    ) {
        double quadratic = 0.0D;
        double linear = 0.0D;
        double constant = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            double sample = starts[axis] + velocities[axis] * sampleTime;
            double offset;
            if (sample < -extents[axis]) {
                offset = starts[axis] + extents[axis];
            } else if (sample > extents[axis]) {
                offset = starts[axis] - extents[axis];
            } else {
                continue;
            }
            double velocity = velocities[axis];
            quadratic += velocity * velocity;
            linear += 2.0D * offset * velocity;
            constant += offset * offset;
        }
        if (!Double.isFinite(quadratic)
                || !Double.isFinite(linear)
                || !Double.isFinite(constant)) {
            return null;
        }
        return new Quadratic(quadratic, linear, constant);
    }

    private static void normalizedFinite(
            Vector3d vector,
            Vector3d fallback
    ) {
        if (!isFinite(vector) || vector.lengthSquared() <= EPSILON * EPSILON) {
            vector.set(fallback);
        }
        if (!isFinite(vector) || vector.lengthSquared() <= EPSILON * EPSILON) {
            vector.set(1.0D, 0.0D, 0.0D);
        }
        vector.normalize();
        if (!isFinite(vector)
                || Math.abs(vector.lengthSquared() - 1.0D) > 1.0E-6D) {
            vector.set(1.0D, 0.0D, 0.0D);
        }
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    record Quadratic(
            double quadratic,
            double linear,
            double constant
    ) {
        QuadraticRootResult rootsAgainst(double value) {
            double adjustedConstant = this.constant - value;
            if (Math.abs(this.quadratic) <= QUADRATIC_EPSILON) {
                if (Math.abs(this.linear) <= QUADRATIC_EPSILON) {
                    return QuadraticRootResult.noRealRoot(
                            "degenerate constant quadratic has no unique root");
                }
                double root = -adjustedConstant / this.linear;
                return Double.isFinite(root)
                        ? QuadraticRootResult.roots(List.of(root))
                        : QuadraticRootResult.indeterminate(
                                "non-finite linear-sphere quadratic root");
            }
            double discriminant = this.linear * this.linear
                    - 4.0D * this.quadratic * adjustedConstant;
            if (!Double.isFinite(discriminant)) {
                return QuadraticRootResult.indeterminate(
                        "non-finite sphere quadratic discriminant");
            }
            double scale = Math.max(
                    1.0D,
                    Math.max(
                            Math.abs(this.linear * this.linear),
                            Math.abs(4.0D * this.quadratic * adjustedConstant)));
            if (discriminant < -1.0E-14D * scale) {
                return QuadraticRootResult.noRealRoot(
                        "sphere quadratic discriminant is negative");
            }
            double rootMagnitude = Math.sqrt(Math.max(0.0D, discriminant));
            double q = -0.5D * (
                    this.linear + Math.copySign(rootMagnitude, this.linear));
            if (Math.abs(q) <= QUADRATIC_EPSILON) {
                double root = -this.linear / (2.0D * this.quadratic);
                if (!Double.isFinite(root)) {
                    return QuadraticRootResult.indeterminate(
                            "non-finite degenerate sphere quadratic root");
                }
                return QuadraticRootResult.roots(List.of(root, root));
            }
            double first = q / this.quadratic;
            double second = adjustedConstant / q;
            if (!Double.isFinite(first) || !Double.isFinite(second)) {
                return QuadraticRootResult.indeterminate(
                        "non-finite stable sphere quadratic root");
            }
            return QuadraticRootResult.roots(
                    first <= second
                            ? List.of(first, second)
                            : List.of(second, first));
        }
    }

    enum QuadraticRootStatus {
        ROOTS, NO_REAL_ROOT, INDETERMINATE
    }

    record QuadraticRootResult(
            QuadraticRootStatus status,
            List<Double> roots,
            String diagnostic
    ) {
        QuadraticRootResult {
            roots = List.copyOf(roots);
            diagnostic = diagnostic == null ? "" : diagnostic;
            if ((status == QuadraticRootStatus.ROOTS) != !roots.isEmpty()) {
                throw new IllegalArgumentException(
                        "only ROOTS may carry quadratic roots");
            }
        }

        static QuadraticRootResult roots(List<Double> roots) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.ROOTS, roots, "");
        }

        static QuadraticRootResult noRealRoot(String proof) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.NO_REAL_ROOT, List.of(), proof);
        }

        static QuadraticRootResult indeterminate(String diagnostic) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.INDETERMINATE, List.of(), diagnostic);
        }
    }
}
