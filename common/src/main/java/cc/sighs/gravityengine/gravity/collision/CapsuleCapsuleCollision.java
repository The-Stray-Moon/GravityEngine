package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.math.ScalarMath;

/** Exact segment/segment distance and bounded translation CCD. Normal points from B to A.
 * Static queries enumerate the four boundary minima and the interior stationary point;
 * no iterative convex solver, polytope or convergence budget is involved. */
public final class CapsuleCapsuleCollision {
    private CapsuleCapsuleCollision() {}
    public record ContactGeometry(Vec3d witnessA, Vec3d witnessB, Vec3d normal,
                                  double signedGap, double penetration) {
    }
    public record SweepGeometry(SweepInitialState initialState, ContactGeometry contact,
                                double timeOfImpact, boolean indeterminate) {}

    public static ContactGeometry contactGeometry(CharacterCapsule a, CharacterCapsule b) {
        return contactGeometry(a, b, Vec3d.ZERO);
    }
    private static ContactGeometry contactGeometry(CharacterCapsule a, CharacterCapsule b, Vec3d motion) {
        Vec3d centerA = a.center();
        Vec3d centerB = b.center();
        return contactGeometry(
                a,
                b,
                motion,
                centerA.x(),
                centerA.y(),
                centerA.z(),
                centerB.x(),
                centerB.y(),
                centerB.z()
        );
    }

    private static ContactGeometry contactGeometry(
            CharacterCapsule a,
            CharacterCapsule b,
            Vec3d motion,
            double centerAX,
            double centerAY,
            double centerAZ,
            double centerBX,
            double centerBY,
            double centerBZ
    ) {
        // Center-relative arithmetic retains precision away from the world origin.
        Vec3d u = a.axis(), v = b.axis();
        double ux = u.x(), uy = u.y(), uz = u.z();
        double vx = v.x(), vy = v.y(), vz = v.z();
        double rx = centerAX - centerBX;
        double ry = centerAY - centerBY;
        double rz = centerAZ - centerBZ;
        double h = a.halfSegmentLength(), k = b.halfSegmentLength();
        double uv = ux * vx + uy * vy + uz * vz;
        double ur = ux * rx + uy * ry + uz * rz;
        double vr = vx * rx + vy * ry + vz * rz;
        double best = Double.POSITIVE_INFINITY, bestS = 0, bestT = 0;

        double s = -h;
        double t = ScalarMath.clamp(vr - h * uv, -k, k);
        double d2 = candidateDistanceSquared(
                s, t, h, k, rx, ry, rz, ux, uy, uz, vx, vy, vz
        );
        if (d2 < best) {
            best = d2;
            bestS = s;
            bestT = t;
        }

        s = h;
        t = ScalarMath.clamp(vr + h * uv, -k, k);
        d2 = candidateDistanceSquared(
                s, t, h, k, rx, ry, rz, ux, uy, uz, vx, vy, vz
        );
        if (d2 < best) {
            best = d2;
            bestS = s;
            bestT = t;
        }

        s = ScalarMath.clamp(-ur - k * uv, -h, h);
        t = -k;
        d2 = candidateDistanceSquared(
                s, t, h, k, rx, ry, rz, ux, uy, uz, vx, vy, vz
        );
        if (d2 < best) {
            best = d2;
            bestS = s;
            bestT = t;
        }

        s = ScalarMath.clamp(-ur + k * uv, -h, h);
        t = k;
        d2 = candidateDistanceSquared(
                s, t, h, k, rx, ry, rz, ux, uy, uz, vx, vy, vz
        );
        if (d2 < best) {
            best = d2;
            bestS = s;
            bestT = t;
        }

        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        double denominator =
                crossX * crossX + crossY * crossY + crossZ * crossZ;
        // Cross-product form avoids subtracting two nearly equal unit dot products.
        if (denominator != 0.0D) {
            double vCrossR = (vy * rz - vz * ry) * crossX
                    + (vz * rx - vx * rz) * crossY
                    + (vx * ry - vy * rx) * crossZ;
            double uCrossR = (uy * rz - uz * ry) * crossX
                    + (uz * rx - ux * rz) * crossY
                    + (ux * ry - uy * rx) * crossZ;
            s = vCrossR / denominator;
            t = uCrossR / denominator;
            d2 = candidateDistanceSquared(
                    s, t, h, k, rx, ry, rz, ux, uy, uz, vx, vy, vz
            );
            if (d2 < best) {
                best = d2;
                bestS = s;
                bestT = t;
            }
        }
        if (denominator == 0) {
            // Parallel overlapping projections have a continuum of closest pairs.
            // Its midpoint is invariant under segment reversal and A/B swapping.
            double lo = Math.max(-h, -ur - k);
            double hi = Math.min(h, -ur + k);
            if (lo <= hi) {
                bestS = (lo + hi) * 0.5D;
                bestT = ScalarMath.clamp(vr + bestS * uv, -k, k);
                best = distanceSquared(
                        bestS, bestT, rx, ry, rz, ux, uy, uz, vx, vy, vz
                );
            }
        }

        double deltaX = (rx + bestS * ux) + (-bestT) * vx;
        double deltaY = (ry + bestS * uy) + (-bestT) * vy;
        double deltaZ = (rz + bestS * uz) + (-bestT) * vz;
        double distance = Math.sqrt(best);
        double normalX;
        double normalY;
        double normalZ;
        if (distance > 1.0E-12D) {
            normalX = deltaX / distance;
            normalY = deltaY / distance;
            normalZ = deltaZ / distance;
        } else {
            // Nonunique normals: choose a separating cross/orthogonal direction. For
            // parallel spines remove axial motion so an axial endpoint cannot be used
            // as a fictitious minimum-translation direction through a long capsule.
            normalX = crossX;
            normalY = crossY;
            normalZ = crossZ;
            if (normalX * normalX + normalY * normalY + normalZ * normalZ
                    <= 1.0E-24D) {
                if (h == 0.0D && k == 0.0D) {
                    normalX = 1.0D;
                    normalY = 0.0D;
                    normalZ = 0.0D;
                } else if (Math.abs(ux) < 0.75D) {
                    normalX = 0.0D;
                    normalY = uz;
                    normalZ = -uy;
                } else {
                    normalX = -uz;
                    normalY = 0.0D;
                    normalZ = ux;
                }
                double radialMotionX = motion.x();
                double radialMotionY = motion.y();
                double radialMotionZ = motion.z();
                if (h != 0 || k != 0) {
                    double radialDotU = radialMotionX * ux
                            + radialMotionY * uy
                            + radialMotionZ * uz;
                    radialMotionX = radialMotionX + (-radialDotU) * ux;
                    radialMotionY = radialMotionY + (-radialDotU) * uy;
                    radialMotionZ = radialMotionZ + (-radialDotU) * uz;
                }
                if (radialMotionX * radialMotionX
                        + radialMotionY * radialMotionY
                        + radialMotionZ * radialMotionZ > 1.0E-24D) {
                    normalX = radialMotionX;
                    normalY = radialMotionY;
                    normalZ = radialMotionZ;
                }
            }
            Vec3d normalized = new Vec3d(
                    normalX, normalY, normalZ
            ).normalized();
            normalX = normalized.x();
            normalY = normalized.y();
            normalZ = normalized.z();

            double motionLengthSquared = motion.lengthSquared();
            if (motionLengthSquared > 1.0E-24D
                    && normalX * motion.x()
                    + normalY * motion.y()
                    + normalZ * motion.z() > 0.0D) {
                normalX = -normalX;
                normalY = -normalY;
                normalZ = -normalZ;
            } else if (motionLengthSquared <= 1.0E-24D
                    && normalX * rx + normalY * ry + normalZ * rz < 0.0D) {
                normalX = -normalX;
                normalY = -normalY;
                normalZ = -normalZ;
            }
        }
        double gap = distance-a.radius()-b.radius();
        double radiusA = a.radius();
        double radiusB = b.radius();
        return new ContactGeometry(
                new Vec3d(
                        (centerAX + bestS * ux) + (-radiusA) * normalX,
                        (centerAY + bestS * uy) + (-radiusA) * normalY,
                        (centerAZ + bestS * uz) + (-radiusA) * normalZ
                ),
                new Vec3d(
                        (centerBX + bestT * vx) + radiusB * normalX,
                        (centerBY + bestT * vy) + radiusB * normalY,
                        (centerBZ + bestT * vz) + radiusB * normalZ
                ),
                new Vec3d(normalX, normalY, normalZ),
                gap,
                Math.max(0.0D, -gap)
        );
    }

    private static double candidateDistanceSquared(
            double s,
            double t,
            double h,
            double k,
            double rx,
            double ry,
            double rz,
            double ux,
            double uy,
            double uz,
            double vx,
            double vy,
            double vz
    ) {
        if (!Double.isFinite(s)
                || !Double.isFinite(t)
                || s < -h
                || s > h
                || t < -k
                || t > k) {
            return Double.NaN;
        }
        return distanceSquared(s, t, rx, ry, rz, ux, uy, uz, vx, vy, vz);
    }

    private static double distanceSquared(
            double s,
            double t,
            double rx,
            double ry,
            double rz,
            double ux,
            double uy,
            double uz,
            double vx,
            double vy,
            double vz
    ) {
        double deltaX = (rx + s * ux) + (-t) * vx;
        double deltaY = (ry + s * uy) + (-t) * vy;
        double deltaZ = (rz + s * uz) + (-t) * vz;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    public static SweepGeometry sweep(CharacterCapsule a, Vec3d motionA, CharacterCapsule b, Vec3d motionB) {
        Vec3d relative = motionA.subtract(motionB);
        if (!relative.isFinite()) return new SweepGeometry(SweepInitialState.SEPARATED,null,0,true);
        Vec3d centerA = a.center();
        Vec3d centerB = b.center();
        double centerAX = centerA.x();
        double centerAY = centerA.y();
        double centerAZ = centerA.z();
        double centerBX = centerB.x();
        double centerBY = centerB.y();
        double centerBZ = centerB.z();
        var g = contactGeometry(a,b,relative);
        var initial = classify(g.signedGap());
        if (initial == SweepInitialState.OVERLAPPING) return new SweepGeometry(initial,g,0,false);
        if (initial == SweepInitialState.TOUCHING) return new SweepGeometry(initial,
                g.normal().dot(relative) < -CollisionTolerances.ENTERING_PLANE_EPSILON ? g : null,0,false);
        double time = 0.0D;
        for (int i = 0; i < 32; i++) {
            double safeGap =
                    g.signedGap() - CollisionTolerances.CONTACT_SLOP;
            double rate = -g.normal().dot(relative);

            // Segment distance along translation is convex; a nonclosing supporting
            // tangent proves clearance for all remaining time.
            if (rate <= CollisionTolerances.ENTERING_PLANE_EPSILON) {
                return new SweepGeometry(initial, null, time, false);
            }

            if (CapsuleCcdConvergence.reachedContactBand(g.signedGap())) {
                return atHit(
                        a, b, motionA, motionB,
                        relative, initial, time
                );
            }

            double advance = safeGap / rate;
            if (advance > 1.0D - time + CollisionTolerances.TOI_EPSILON) {
                return new SweepGeometry(initial, null, 1.0D, false);
            }
            if (advance <= 1.0E-12D) {
                break;
            }

            time = Math.min(1.0D, time + advance);
            g = contactGeometry(
                    a,
                    b,
                    relative,
                    centerAX + relative.x() * time,
                    centerAY + relative.y() * time,
                    centerAZ + relative.z() * time,
                    centerBX,
                    centerBY,
                    centerBZ
            );
        }
        var refined = CapsuleCcdConvergence.findEarliestContact(t ->
                contactGeometry(
                        a,
                        b,
                        relative,
                        centerAX + relative.x() * t,
                        centerAY + relative.y() * t,
                        centerAZ + relative.z() * t,
                        centerBX,
                        centerBY,
                        centerBZ
                ).signedGap(), time, 1);
        if (refined.isHit()) return atHit(a,b,motionA,motionB,relative,initial,refined.timeOfImpact());
        return new SweepGeometry(initial,null,1,!refined.isNoHit());
    }
    private static SweepGeometry atHit(CharacterCapsule a, CharacterCapsule b, Vec3d da, Vec3d db,
            Vec3d relative, SweepInitialState initial, double t) {
        Vec3d centerA = a.center();
        Vec3d centerB = b.center();
        return new SweepGeometry(
                initial,
                contactGeometry(
                        a,
                        b,
                        relative,
                        centerA.x() + da.x() * t,
                        centerA.y() + da.y() * t,
                        centerA.z() + da.z() * t,
                        centerB.x() + db.x() * t,
                        centerB.y() + db.y() * t,
                        centerB.z() + db.z() * t
                ),
                t,
                false
        );
    }
    static SweepInitialState classify(double signedGap) {
        if (signedGap < -CollisionTolerances.PENETRATION_EPSILON) {
            return SweepInitialState.OVERLAPPING;
        }
        if (CapsuleCcdConvergence.reachedContactBand(signedGap)) {
            return SweepInitialState.TOUCHING;
        }
        return SweepInitialState.SEPARATED;
    }
}
