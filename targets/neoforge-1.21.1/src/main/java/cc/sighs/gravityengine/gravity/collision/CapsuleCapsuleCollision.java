package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import org.joml.Vector3d;

/** Exact segment/segment distance and bounded translation CCD. Normal points from B to A.
 * Static queries enumerate the four boundary minima and the interior stationary point;
 * no iterative convex solver, polytope or convergence budget is involved. */
public final class CapsuleCapsuleCollision {
    private CapsuleCapsuleCollision() {}
    public record ContactGeometry(Vector3d witnessA, Vector3d witnessB, Vector3d normal,
                                  double signedGap, double penetration) {
        public ContactGeometry { witnessA = new Vector3d(witnessA); witnessB = new Vector3d(witnessB); normal = new Vector3d(normal); }
        @Override public Vector3d witnessA() { return new Vector3d(witnessA); }
        @Override public Vector3d witnessB() { return new Vector3d(witnessB); }
        @Override public Vector3d normal() { return new Vector3d(normal); }
    }
    public record SweepGeometry(SweepInitialState initialState, ContactGeometry contact,
                                double timeOfImpact, boolean indeterminate) {}

    public static ContactGeometry contactGeometry(CharacterCapsule a, CharacterCapsule b) {
        return contactGeometry(a, b, new Vector3d());
    }
    private static ContactGeometry contactGeometry(CharacterCapsule a, CharacterCapsule b, Vector3d motion) {
        // Center-relative arithmetic retains precision away from the world origin.
        Vector3d u = a.axis(), v = b.axis(), r = a.center().sub(b.center());
        double h = a.halfSegmentLength(), k = b.halfSegmentLength();
        double uv = u.dot(v), ur = u.dot(r), vr = v.dot(r);
        double best = Double.POSITIVE_INFINITY, bestS = 0, bestT = 0;
        double[][] candidates = new double[5][2];
        candidates[0] = new double[]{-h, Math.clamp(vr-h*uv,-k,k)};
        candidates[1] = new double[]{ h, Math.clamp(vr+h*uv,-k,k)};
        candidates[2] = new double[]{Math.clamp(-ur-k*uv,-h,h),-k};
        candidates[3] = new double[]{Math.clamp(-ur+k*uv,-h,h), k};
        Vector3d cross = u.cross(v,new Vector3d());
        double denominator = cross.lengthSquared();
        // Cross-product form avoids subtracting two nearly equal unit dot products.
        double s = denominator == 0 ? Double.NaN : v.cross(r,new Vector3d()).dot(cross)/denominator;
        double t = denominator == 0 ? Double.NaN : u.cross(r,new Vector3d()).dot(cross)/denominator;
        candidates[4] = new double[]{s,t};
        for (double[] pair : candidates) {
            if (!Double.isFinite(pair[0]) || !Double.isFinite(pair[1])
                    || pair[0] < -h || pair[0] > h || pair[1] < -k || pair[1] > k) continue;
            double d2 = new Vector3d(r).fma(pair[0],u).fma(-pair[1],v).lengthSquared();
            if (d2 < best) { best = d2; bestS = pair[0]; bestT = pair[1]; }
        }
        if (denominator == 0) {
            // Parallel overlapping projections have a continuum of closest pairs.
            // Its midpoint is invariant under segment reversal and A/B swapping.
            double lo = Math.max(-h,-ur-k), hi = Math.min(h,-ur+k);
            if (lo <= hi) {
                bestS = (lo+hi)*.5;
                bestT = Math.clamp(vr+bestS*uv,-k,k);
                best = new Vector3d(r).fma(bestS,u).fma(-bestT,v).lengthSquared();
            }
        }
        Vector3d delta = new Vector3d(r).fma(bestS,u).fma(-bestT,v);
        double distance = Math.sqrt(best);
        Vector3d normal;
        if (distance > 1e-12) normal = delta.div(distance);
        else {
            // Nonunique normals: choose a separating cross/orthogonal direction. For
            // parallel spines remove axial motion so an axial endpoint cannot be used
            // as a fictitious minimum-translation direction through a long capsule.
            normal = cross;
            if (normal.lengthSquared() <= 1e-24) {
                Vector3d seed = Math.abs(u.x) < .75 ? new Vector3d(1,0,0) : new Vector3d(0,1,0);
                normal = h == 0 && k == 0 ? new Vector3d(1,0,0) : u.cross(seed,new Vector3d());
                Vector3d radialMotion = new Vector3d(motion);
                if (h != 0 || k != 0) radialMotion.fma(-radialMotion.dot(u),u);
                if (radialMotion.lengthSquared() > 1e-24) normal = radialMotion;
            }
            normal.normalize();
            if (motion.lengthSquared() > 1e-24 && normal.dot(motion) > 0) normal.negate();
            else if (motion.lengthSquared() <= 1e-24 && normal.dot(r) < 0) normal.negate();
        }
        double gap = distance-a.radius()-b.radius();
        return new ContactGeometry(a.center().fma(bestS,u).fma(-a.radius(),normal),
                b.center().fma(bestT,v).fma(b.radius(),normal),normal,gap,Math.max(0,-gap));
    }

    public static SweepGeometry sweep(CharacterCapsule a, Vector3d motionA, CharacterCapsule b, Vector3d motionB) {
        Vector3d relative = new Vector3d(motionA).sub(motionB);
        if (!relative.isFinite()) return new SweepGeometry(SweepInitialState.SEPARATED,null,0,true);
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
                    a.move(new Vector3d(relative).mul(time)),
                    b,
                    relative
            );
        }
        var refined = CapsuleCcdConvergence.findEarliestContact(t ->
                contactGeometry(a.move(new Vector3d(relative).mul(t)),b,relative).signedGap(),time,1);
        if (refined.isHit()) return atHit(a,b,motionA,motionB,relative,initial,refined.timeOfImpact());
        return new SweepGeometry(initial,null,1,!refined.isNoHit());
    }
    private static SweepGeometry atHit(CharacterCapsule a, CharacterCapsule b, Vector3d da, Vector3d db,
            Vector3d relative, SweepInitialState initial, double t) {
        return new SweepGeometry(initial,contactGeometry(a.move(new Vector3d(da).mul(t)),
                b.move(new Vector3d(db).mul(t)),relative),t,false);
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
