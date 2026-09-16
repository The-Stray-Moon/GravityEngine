package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import org.joml.Vector3d;

import java.util.Objects;

/** Exact capsule/sphere distance and translation-only conservative advancement. */
public final class CapsuleSphereCollision {
    private static final int MAX_CCD_ITERATIONS = 32;
    private static final double NORMAL_EPSILON_SQUARED = 1.0E-24D;
    private static final double ADVANCE_EPSILON = 1.0E-12D;

    private CapsuleSphereCollision() {}

    public record ContactGeometry(
            Vector3d pointOnSegment,
            Vector3d pointOnSphere,
            Vector3d normal,
            double signedGap,
            double penetration
    ) {}

    public record SweepGeometry(
            SweepInitialState initialState,
            ContactGeometry contact,
            double timeOfImpact,
            boolean indeterminate,
            int iterations,
            String diagnostic,
            CapsuleCcdConvergence.Diagnostics ccdDiagnostics
    ) {
        public SweepGeometry(
                SweepInitialState initialState,
                ContactGeometry contact,
                double timeOfImpact,
                boolean indeterminate,
                int iterations
        ) {
            this(
                    initialState, contact, timeOfImpact, indeterminate, iterations, "",
                    CapsuleCcdConvergence.Diagnostics.normal(iterations)
            );
        }

        public SweepGeometry(
                SweepInitialState initialState,
                ContactGeometry contact,
                double timeOfImpact,
                boolean indeterminate,
                int iterations,
                String diagnostic
        ) {
            this(
                    initialState, contact, timeOfImpact, indeterminate, iterations, diagnostic,
                    CapsuleCcdConvergence.Diagnostics.normal(iterations)
            );
        }

        public boolean hasContact() { return contact != null; }
    }

    public static ContactGeometry contactGeometry(
            CharacterCapsule capsule,
            Vector3d sphereCenter,
            double sphereRadius
    ) {
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(sphereCenter, "sphereCenter");
        ContactScratch scratch = new ContactScratch();
        sample(capsule, 0.0D, 0.0D, 0.0D, sphereCenter, sphereRadius, scratch);
        return scratch.toImmutable();
    }

    public static SweepGeometry sweep(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Vector3d sphereCenter,
            double sphereRadius
    ) {
        ContactScratch geometry = new ContactScratch();
        sample(capsule, 0.0D, 0.0D, 0.0D, sphereCenter, sphereRadius, geometry);
        SweepInitialState initialState = classify(geometry.signedGap);
        if (initialState == SweepInitialState.OVERLAPPING) {
            return new SweepGeometry(initialState, geometry.toImmutable(), 0.0D, false, 0);
        }
        if (initialState == SweepInitialState.TOUCHING) {
            if (geometry.directionalDerivative(relativeMovement)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepGeometry(initialState, null, 0.0D, false, 0);
            }
            return new SweepGeometry(initialState, geometry.toImmutable(), 0.0D, false, 0);
        }
        double speed = relativeMovement.length();
        if (!Double.isFinite(speed)) {
            return new SweepGeometry(
                    initialState, null, 0.0D, true, 0,
                    "non-finite relativeMovement=" + relativeMovement
            );
        }
        if (relativeMovement.lengthSquared()
                <= CollisionTolerances.ZERO_VECTOR_EPSILON_SQUARED) {
            return new SweepGeometry(initialState, null, 0.0D, false, 0);
        }

        double time = 0.0D;
        for (int iteration = 1; iteration <= MAX_CCD_ITERATIONS; iteration++) {
            double safeGap = geometry.signedGap - CollisionTolerances.CONTACT_SLOP;
            double derivative = geometry.directionalDerivative(relativeMovement);
            if (!Double.isFinite(derivative)) {
                return new SweepGeometry(
                        initialState, null, time, true, iteration,
                        describeIndeterminate(
                                capsule, relativeMovement, sphereCenter, sphereRadius,
                                time, geometry, iteration, ""
                        )
                );
            }
            // The capsule/sphere signed gap is convex along a linear
            // translation, so a non-negative derivative cannot later become
            // more negative and therefore cannot reach contact.
            if (derivative >= -CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepGeometry(initialState, null, time, false, iteration);
            }
            // A close but separating/tangent pair is not an entering event.
            // Check the distance derivative before accepting the convergence band.
            if (CapsuleCcdConvergence.reachedContactBand(geometry.signedGap)) {
                return new SweepGeometry(
                        initialState, geometry.toImmutable(), time, false, iteration
                );
            }
            double closingRate = -derivative;
            double remainingTime = 1.0D - time;
            double tangentRoot = safeGap / closingRate;
            if (!Double.isFinite(tangentRoot)) {
                return new SweepGeometry(
                        initialState, null, time, true, iteration,
                        describeIndeterminate(
                                capsule, relativeMovement, sphereCenter, sphereRadius,
                                time, geometry, iteration, ""
                        )
                );
            }
            if (tangentRoot > remainingTime + CollisionTolerances.TOI_EPSILON) {
                return new SweepGeometry(initialState, null, 1.0D, false, iteration);
            }
            double advance = Math.min(tangentRoot, remainingTime);
            if (advance <= ADVANCE_EPSILON) {
                return resolveByFallback(
                        capsule, relativeMovement, sphereCenter, sphereRadius,
                        initialState, time, iteration, geometry
                );
            }
            time += advance;
            sample(
                    capsule,
                    relativeMovement.x * time,
                    relativeMovement.y * time,
                    relativeMovement.z * time,
                    sphereCenter,
                    sphereRadius,
                    geometry
            );
        }
        return resolveByFallback(
                capsule, relativeMovement, sphereCenter, sphereRadius,
                initialState, time, MAX_CCD_ITERATIONS, geometry
        );
    }

    /**
     * Bounded convex fallback used only when the conservative-advancement loop
     * reaches its numerical iteration budget or the remaining interval is too
     * small for a meaningful bounded advance.
     */
    private static SweepGeometry resolveByFallback(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Vector3d sphereCenter,
            double sphereRadius,
            SweepInitialState initialState,
            double time,
            int caIterations,
            ContactScratch geometry
    ) {
        CapsuleCcdConvergence.Result result = CapsuleCcdConvergence.findEarliestContact(
                t -> {
                    sample(
                            capsule,
                            relativeMovement.x * t,
                            relativeMovement.y * t,
                            relativeMovement.z * t,
                            sphereCenter,
                            sphereRadius,
                            geometry
                    );
                    return geometry.signedGap;
                },
                time,
                1.0D
        );
        if (result.isHit()) {
            double toi = result.timeOfImpact();
            sample(
                    capsule,
                    relativeMovement.x * toi,
                    relativeMovement.y * toi,
                    relativeMovement.z * toi,
                    sphereCenter,
                    sphereRadius,
                    geometry
            );
            return new SweepGeometry(
                    initialState, geometry.toImmutable(), toi, false, caIterations,
                    "", CapsuleCcdConvergence.Diagnostics.fallback(
                    caIterations, result.minimumSamples(), result.rootSamples()
            )
            );
        }
        if (result.isNoHit()) {
            return new SweepGeometry(
                    initialState, null, 1.0D, false, caIterations,
                    "", CapsuleCcdConvergence.Diagnostics.fallback(
                    caIterations, result.minimumSamples(), result.rootSamples()
            )
            );
        }
        sample(
                capsule,
                relativeMovement.x * time,
                relativeMovement.y * time,
                relativeMovement.z * time,
                sphereCenter,
                sphereRadius,
                geometry
        );
        return new SweepGeometry(
                initialState, null, time, true, caIterations,
                describeIndeterminate(
                        capsule, relativeMovement, sphereCenter, sphereRadius,
                        time, geometry, caIterations, result.diagnostic()
                ),
                CapsuleCcdConvergence.Diagnostics.fallback(
                        caIterations, result.minimumSamples(), result.rootSamples()
                )
        );
    }

    private static String describeIndeterminate(
            CharacterCapsule capsule,
            Vector3d relativeMovement,
            Vector3d sphereCenter,
            double sphereRadius,
            double time,
            ContactScratch geometry,
            int iteration,
            String fallbackDetail
    ) {
        double safeGap = geometry.signedGap - CollisionTolerances.CONTACT_SLOP;
        double derivative = geometry.directionalDerivative(relativeMovement);
        double closingRate = -derivative;
        return "body=" + capsule
                + " obstacle=sphere" + sphereCenter + " radius=" + sphereRadius
                + " relativeMovement=" + relativeMovement
                + " time=" + time
                + " remainingTime=" + (1.0D - time)
                + " signedGap=" + geometry.signedGap
                + " safeGap=" + safeGap
                + " normal=" + new Vector3d(
                geometry.normalX, geometry.normalY, geometry.normalZ
        )
                + " derivative=" + derivative
                + " closingRate=" + closingRate
                + " iteration=" + iteration
                + (fallbackDetail == null || fallbackDetail.isBlank()
                ? "" : " fallback=" + fallbackDetail);
    }

    /** Populates one reusable sample; the conservative-advancement loop allocates nothing. */
    private static void sample(
            CharacterCapsule capsule,
            double offsetX,
            double offsetY,
            double offsetZ,
            Vector3d sphereCenter,
            double sphereRadius,
            ContactScratch scratch
    ) {
        Vector3d center = capsule.center();
        Vector3d axis = capsule.axis();
        double half = capsule.halfSegmentLength();
        double ax = center.x - axis.x * half + offsetX;
        double ay = center.y - axis.y * half + offsetY;
        double az = center.z - axis.z * half + offsetZ;
        double dx = axis.x * half * 2.0D;
        double dy = axis.y * half * 2.0D;
        double dz = axis.z * half * 2.0D;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        double parameter = lengthSquared <= CollisionTolerances.GEOMETRIC_AXIS_EPSILON
                ? 0.0D
                : clamp(
                        ((sphereCenter.x - ax) * dx
                                + (sphereCenter.y - ay) * dy
                                + (sphereCenter.z - az) * dz) / lengthSquared,
                        0.0D,
                        1.0D
                );
        scratch.segmentX = ax + dx * parameter;
        scratch.segmentY = ay + dy * parameter;
        scratch.segmentZ = az + dz * parameter;
        double ex = scratch.segmentX - sphereCenter.x;
        double ey = scratch.segmentY - sphereCenter.y;
        double ez = scratch.segmentZ - sphereCenter.z;
        double distanceSquared = ex * ex + ey * ey + ez * ez;
        double distance;
        if (distanceSquared > NORMAL_EPSILON_SQUARED) {
            distance = Math.sqrt(distanceSquared);
            scratch.normalX = ex / distance;
            scratch.normalY = ey / distance;
            scratch.normalZ = ez / distance;
        } else {
            distance = 0.0D;
            double centerX = center.x + offsetX - sphereCenter.x;
            double centerY = center.y + offsetY - sphereCenter.y;
            double centerZ = center.z + offsetZ - sphereCenter.z;
            double centerLengthSquared = centerX * centerX
                    + centerY * centerY + centerZ * centerZ;
            if (centerLengthSquared > NORMAL_EPSILON_SQUARED) {
                double inverseLength = 1.0D / Math.sqrt(centerLengthSquared);
                scratch.normalX = centerX * inverseLength;
                scratch.normalY = centerY * inverseLength;
                scratch.normalZ = centerZ * inverseLength;
            } else {
                deterministicPerpendicular(axis, scratch);
            }
        }
        double combinedRadius = capsule.radius() + sphereRadius;
        scratch.signedGap = distance - combinedRadius;
        scratch.penetration = Math.max(0.0D, -scratch.signedGap);
        scratch.obstacleX = sphereCenter.x + scratch.normalX * sphereRadius;
        scratch.obstacleY = sphereCenter.y + scratch.normalY * sphereRadius;
        scratch.obstacleZ = sphereCenter.z + scratch.normalZ * sphereRadius;
    }

    private static void deterministicPerpendicular(Vector3d axis, ContactScratch scratch) {
        double seedX;
        double seedY;
        double seedZ;
        if (Math.abs(axis.x) <= Math.abs(axis.y) && Math.abs(axis.x) <= Math.abs(axis.z)) {
            seedX = 1.0D;
            seedY = 0.0D;
            seedZ = 0.0D;
        } else if (Math.abs(axis.y) <= Math.abs(axis.z)) {
            seedX = 0.0D;
            seedY = 1.0D;
            seedZ = 0.0D;
        } else {
            seedX = 0.0D;
            seedY = 0.0D;
            seedZ = 1.0D;
        }
        double nx = axis.y * seedZ - axis.z * seedY;
        double ny = axis.z * seedX - axis.x * seedZ;
        double nz = axis.x * seedY - axis.y * seedX;
        double inverseLength = 1.0D / Math.sqrt(nx * nx + ny * ny + nz * nz);
        scratch.normalX = nx * inverseLength;
        scratch.normalY = ny * inverseLength;
        scratch.normalZ = nz * inverseLength;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static SweepInitialState classify(double signedGap) {
        if (signedGap < -CollisionTolerances.PENETRATION_EPSILON) {
            return SweepInitialState.OVERLAPPING;
        }
        if (CapsuleCcdConvergence.reachedContactBand(signedGap)) {
            return SweepInitialState.TOUCHING;
        }
        return SweepInitialState.SEPARATED;
    }

    private static Vector3d deterministicPerpendicular(Vector3d axis) {
        Vector3d seed = Math.abs(axis.x) <= Math.abs(axis.y)
                && Math.abs(axis.x) <= Math.abs(axis.z)
                ? new Vector3d(1.0D, 0.0D, 0.0D)
                : Math.abs(axis.y) <= Math.abs(axis.z)
                ? new Vector3d(0.0D, 1.0D, 0.0D)
                : new Vector3d(0.0D, 0.0D, 1.0D);
        return axis.cross(seed).normalize();
    }

    private static final class ContactScratch {
        private double segmentX;
        private double segmentY;
        private double segmentZ;
        private double obstacleX;
        private double obstacleY;
        private double obstacleZ;
        private double normalX;
        private double normalY;
        private double normalZ;
        private double signedGap;
        private double penetration;

        private double directionalDerivative(Vector3d movement) {
            return movement.x * normalX + movement.y * normalY + movement.z * normalZ;
        }

        private ContactGeometry toImmutable() {
            return new ContactGeometry(
                    new Vector3d(segmentX, segmentY, segmentZ),
                    new Vector3d(obstacleX, obstacleY, obstacleZ),
                    new Vector3d(normalX, normalY, normalZ),
                    signedGap,
                    penetration
            );
        }
    }
}
