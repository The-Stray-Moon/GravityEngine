package cc.sighs.gravityengine.gravity.collision.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Obb3d;
import cc.sighs.gravityengine.math.geometry.Sphere3d;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

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
        Vec3d center = sphere.center();
        double halfX = box.halfX();
        double halfY = box.halfY();
        double halfZ = box.halfZ();
        Vec3d local = box.worldPointToLocal(center);
        double closestX = clamp(local.x(), -halfX, halfX);
        double closestY = clamp(local.y(), -halfY, halfY);
        double closestZ = clamp(local.z(), -halfZ, halfZ);
        Vec3d closest = box.localPointToWorld(closestX, closestY, closestZ);
        double offsetX = center.x() - closest.x();
        double offsetY = center.y() - closest.y();
        double offsetZ = center.z() - closest.z();
        double distSq = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
        if (distSq < DISTANCE_EPSILON) {
            double nearestFaceDist = Math.min(
                    halfX - Math.abs(local.x()),
                    Math.min(
                            halfY - Math.abs(local.y()),
                            halfZ - Math.abs(local.z())));
            return -(nearestFaceDist + sphere.radius());
        }
        return Math.sqrt(distSq) - sphere.radius();
    }

    public static boolean intersects(Sphere3d sphere, Obb3d box) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Vec3d center = sphere.center();
        double halfX = box.halfX();
        double halfY = box.halfY();
        double halfZ = box.halfZ();
        Vec3d local = box.worldPointToLocal(center);
        Vec3d closest = box.localPointToWorld(
                clamp(local.x(), -halfX, halfX),
                clamp(local.y(), -halfY, halfY),
                clamp(local.z(), -halfZ, halfZ)
        );
        double offsetX = center.x() - closest.x();
        double offsetY = center.y() - closest.y();
        double offsetZ = center.z() - closest.z();
        return offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ
                <= sphere.radius() * sphere.radius();
    }

    /** Closest point on the OBB surface/interior to a world point. */
    public static Vec3d closestPointOnBox(
            Obb3d box,
            Vec3d worldPoint
    ) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(worldPoint, "worldPoint");
        Vec3d local = box.worldPointToLocal(worldPoint);
        return box.localPointToWorld(
                clamp(local.x(), -box.halfX(), box.halfX()),
                clamp(local.y(), -box.halfY(), box.halfY()),
                clamp(local.z(), -box.halfZ(), box.halfZ())
        );
    }

    /** Static contact between one sphere and one OBB. */
    public static SphereObbContact contact(Sphere3d sphere, Obb3d box) {
        Objects.requireNonNull(sphere, "sphere");
        Objects.requireNonNull(box, "box");
        Vec3d center = sphere.center();
        double halfX = box.halfX();
        double halfY = box.halfY();
        double halfZ = box.halfZ();
        Vec3d localCenter = box.worldPointToLocal(center);
        double localCenterX = localCenter.x();
        double localCenterY = localCenter.y();
        double localCenterZ = localCenter.z();
        double closestLocalX = clamp(localCenterX, -halfX, halfX);
        double closestLocalY = clamp(localCenterY, -halfY, halfY);
        double closestLocalZ = clamp(localCenterZ, -halfZ, halfZ);
        Vec3d pointOnBox = box.localPointToWorld(
                closestLocalX,
                closestLocalY,
                closestLocalZ
        );
        double fromSphereToBoxX = pointOnBox.x() - center.x();
        double fromSphereToBoxY = pointOnBox.y() - center.y();
        double fromSphereToBoxZ = pointOnBox.z() - center.z();
        double distSq = fromSphereToBoxX * fromSphereToBoxX
                + fromSphereToBoxY * fromSphereToBoxY
                + fromSphereToBoxZ * fromSphereToBoxZ;
        double pen;
        Vec3d normal;

        if (distSq < DISTANCE_EPSILON) {
            double minOverlap = Double.MAX_VALUE;
            int bestAxis = 0;
            double bestSign = 1.0D;
            double bestLocalX = localCenterX;
            double bestLocalY = localCenterY;
            double bestLocalZ = localCenterZ;

            double overlap = halfX - Math.abs(localCenterX);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                bestAxis = 0;
                bestSign = localCenterX < 0.0D ? -1.0D : 1.0D;
                bestLocalX = halfX * bestSign;
            }
            overlap = halfY - Math.abs(localCenterY);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                bestAxis = 1;
                bestSign = localCenterY < 0.0D ? -1.0D : 1.0D;
                bestLocalY = halfY * bestSign;
            }
            overlap = halfZ - Math.abs(localCenterZ);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                bestAxis = 2;
                bestSign = localCenterZ < 0.0D ? -1.0D : 1.0D;
                bestLocalZ = halfZ * bestSign;
            }

            double localNormalX = 0.0D;
            double localNormalY = 0.0D;
            double localNormalZ = 0.0D;
            if (bestAxis == 0) {
                localNormalX = -bestSign;
            } else if (bestAxis == 1) {
                localNormalY = -bestSign;
            } else {
                localNormalZ = -bestSign;
            }
            /*
             * bestAxis/bestSign are OBB-local. The contact normal is a world
             * space free vector, so transform the selected local axis without
             * applying any translation.
             */
            normal = box.localVectorToWorld(
                    localNormalX,
                    localNormalY,
                    localNormalZ
            );
            pen = minOverlap + sphere.radius();
            pointOnBox = box.localPointToWorld(
                    bestLocalX,
                    bestLocalY,
                    bestLocalZ
            );
        } else {
            double dist = Math.sqrt(distSq);
            normal = normalizedFinite(
                    fromSphereToBoxX / dist,
                    fromSphereToBoxY / dist,
                    fromSphereToBoxZ / dist,
                    Vec3d.X
            );
            pen = sphere.radius() - dist;
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
            Vec3d movement
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
            Vec3d movement
    ) {
        return sweepDetailed(sphere, box, movement, MAX_SWEEP_ITERATIONS);
    }

    /** Testable bounded conservative advancement. */
    public static SphereSweepResult sweepDetailed(
            Sphere3d sphere,
            Obb3d box,
            Vec3d movement,
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
            Vec3d movement,
            double tolerance,
            int maxIterations
    ) {
        Vec3d movementVector = movement;
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
            Obb3d currentBox = box.moved(movementVector.multiply(t));
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
            Vec3d movement
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

        Vec3d center = sphere.center();
        Vec3d pointStart = box.worldPointToLocal(center);
        Vec3d pointVelocity = box.frame().worldToLocal(movement);
        double startX = pointStart.x();
        double startY = pointStart.y();
        double startZ = pointStart.z();
        double velocityX = -pointVelocity.x();
        double velocityY = -pointVelocity.y();
        double velocityZ = -pointVelocity.z();
        double limitX = box.halfX();
        double limitY = box.halfY();
        double limitZ = box.halfZ();

        double[] boundaries = new double[8];
        int boundaryCount = 0;
        boundaries[boundaryCount++] = 0.0D;
        boundaries[boundaryCount++] = 1.0D;
        if (Math.abs(velocityX) > SWEEP_SPEED_EPSILON) {
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (-limitX - startX) / velocityX
            );
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (limitX - startX) / velocityX
            );
        }
        if (Math.abs(velocityY) > SWEEP_SPEED_EPSILON) {
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (-limitY - startY) / velocityY
            );
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (limitY - startY) / velocityY
            );
        }
        if (Math.abs(velocityZ) > SWEEP_SPEED_EPSILON) {
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (-limitZ - startZ) / velocityZ
            );
            boundaryCount = addBoundary(
                    boundaries,
                    boundaryCount,
                    (limitZ - startZ) / velocityZ
            );
        }
        Arrays.sort(boundaries, 0, boundaryCount);
        boundaryCount = mergeBoundaries(boundaries, boundaryCount);

        for (int interval = 0; interval + 1 < boundaryCount; interval++) {
            double minimum = boundaries[interval];
            double maximum = boundaries[interval + 1];
            if (maximum - minimum <= ROOT_TIME_EPSILON) {
                continue;
            }
            double sample = (minimum + maximum) * 0.5D;
            Quadratic distance = distanceSquaredQuadratic(
                    startX,
                    startY,
                    startZ,
                    velocityX,
                    velocityY,
                    velocityZ,
                    limitX,
                    limitY,
                    limitZ,
                    sample
            );
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
            for (int rootIndex = 0;
                 rootIndex < rootResult.rootCount();
                 rootIndex++) {
                double root = rootResult.root(rootIndex);
                if (root < minimum - ROOT_TIME_EPSILON
                        || root > maximum + ROOT_TIME_EPSILON) {
                    continue;
                }
                double time = clamp(root, minimum, maximum);
                Obb3d hitBox = box.moved(movement.multiply(time));
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

    private static int addBoundary(
            double[] boundaries,
            int count,
            double candidate
    ) {
        if (Double.isFinite(candidate)
                && candidate > ROOT_TIME_EPSILON
                && candidate < 1.0D - ROOT_TIME_EPSILON) {
            boundaries[count++] = candidate;
        }
        return count;
    }

    private static int mergeBoundaries(
            double[] sorted,
            int count
    ) {
        int mergedCount = 0;
        for (int index = 0; index < count; index++) {
            double candidate = sorted[index];
            if (mergedCount == 0
                    || candidate - sorted[mergedCount - 1]
                    > ROOT_TIME_EPSILON) {
                sorted[mergedCount++] = candidate;
            }
        }
        return mergedCount;
    }

    private static Quadratic distanceSquaredQuadratic(
            double startX,
            double startY,
            double startZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            double extentX,
            double extentY,
            double extentZ,
            double sampleTime
    ) {
        double quadratic = 0.0D;
        double linear = 0.0D;
        double constant = 0.0D;
        double sample = startX + velocityX * sampleTime;
        double offset;
        if (sample < -extentX) {
            offset = startX + extentX;
            quadratic += velocityX * velocityX;
            linear += 2.0D * offset * velocityX;
            constant += offset * offset;
        } else if (sample > extentX) {
            offset = startX - extentX;
            quadratic += velocityX * velocityX;
            linear += 2.0D * offset * velocityX;
            constant += offset * offset;
        }
        sample = startY + velocityY * sampleTime;
        if (sample < -extentY) {
            offset = startY + extentY;
            quadratic += velocityY * velocityY;
            linear += 2.0D * offset * velocityY;
            constant += offset * offset;
        } else if (sample > extentY) {
            offset = startY - extentY;
            quadratic += velocityY * velocityY;
            linear += 2.0D * offset * velocityY;
            constant += offset * offset;
        }
        sample = startZ + velocityZ * sampleTime;
        if (sample < -extentZ) {
            offset = startZ + extentZ;
            quadratic += velocityZ * velocityZ;
            linear += 2.0D * offset * velocityZ;
            constant += offset * offset;
        } else if (sample > extentZ) {
            offset = startZ - extentZ;
            quadratic += velocityZ * velocityZ;
            linear += 2.0D * offset * velocityZ;
            constant += offset * offset;
        }
        if (!Double.isFinite(quadratic)
                || !Double.isFinite(linear)
                || !Double.isFinite(constant)) {
            return null;
        }
        return new Quadratic(quadratic, linear, constant);
    }

    private static Vec3d normalizedFinite(
            double x,
            double y,
            double z,
            Vec3d fallback
    ) {
        double lengthSquared = x * x + y * y + z * z;
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || lengthSquared <= EPSILON * EPSILON) {
            x = fallback.x();
            y = fallback.y();
            z = fallback.z();
            lengthSquared = x * x + y * y + z * z;
        }
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= EPSILON * EPSILON) {
            return Vec3d.X;
        }
        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        double normalizedX = x * inverseLength;
        double normalizedY = y * inverseLength;
        double normalizedZ = z * inverseLength;
        if (!Double.isFinite(normalizedX)
                || !Double.isFinite(normalizedY)
                || !Double.isFinite(normalizedZ)) {
            return Vec3d.X;
        }
        double normalizedLengthSquared = normalizedX * normalizedX
                + normalizedY * normalizedY
                + normalizedZ * normalizedZ;
        if (Math.abs(normalizedLengthSquared - 1.0D) > 1.0E-6D) {
            return Vec3d.X;
        }
        return new Vec3d(normalizedX, normalizedY, normalizedZ);
    }

    private static boolean isFinite(Vec3d vector) {
        return vector.isFinite();
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
                        ? QuadraticRootResult.roots(root)
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
                return QuadraticRootResult.roots(root, root);
            }
            double first = q / this.quadratic;
            double second = adjustedConstant / q;
            if (!Double.isFinite(first) || !Double.isFinite(second)) {
                return QuadraticRootResult.indeterminate(
                        "non-finite stable sphere quadratic root");
            }
            return first <= second
                    ? QuadraticRootResult.roots(first, second)
                    : QuadraticRootResult.roots(second, first);
        }
    }

    enum QuadraticRootStatus {
        ROOTS, NO_REAL_ROOT, INDETERMINATE
    }

    record QuadraticRootResult(
            QuadraticRootStatus status,
            double firstRoot,
            double secondRoot,
            int rootCount,
            String diagnostic
    ) {
        QuadraticRootResult {
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (rootCount < 0 || rootCount > 2) {
                throw new IllegalArgumentException(
                        "quadratic root count must be 0, 1, or 2"
                );
            }
            if ((status == QuadraticRootStatus.ROOTS) != (rootCount > 0)) {
                throw new IllegalArgumentException(
                        "only ROOTS may carry quadratic roots");
            }
        }

        double root(int index) {
            if (index == 0) return this.firstRoot;
            if (index == 1 && this.rootCount == 2) return this.secondRoot;
            throw new IndexOutOfBoundsException(index);
        }

        static QuadraticRootResult roots(double root) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.ROOTS, root, 0.0D, 1, "");
        }

        static QuadraticRootResult roots(double first, double second) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.ROOTS, first, second, 2, "");
        }

        static QuadraticRootResult noRealRoot(String proof) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.NO_REAL_ROOT, 0.0D, 0.0D, 0, proof);
        }

        static QuadraticRootResult indeterminate(String diagnostic) {
            return new QuadraticRootResult(
                    QuadraticRootStatus.INDETERMINATE, 0.0D, 0.0D, 0, diagnostic);
        }
    }
}
