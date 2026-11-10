package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import java.util.List;

/** Equal-duration native samples, with a captured rigid path for each interval.
 * Derivatives are right-sided at internal knots. Bounds include every interval;
 * support subdivision aligns with knots instead of assuming smooth acceleration. */
public record SampledRigidTrajectory(List<RigidTrajectory> segments) implements RigidTrajectory {
    public SampledRigidTrajectory {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("empty motion evidence");
        var first = segments.get(0);
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            if (segment.tick()!=first.tick() || segment.revision()!=first.revision()
                    || segment.continuityEpoch()!=first.continuityEpoch()
                    || segment.intervalTicks()!=first.intervalTicks() || segment.subdivisionMultiple()!=1)
                throw new CollisionSceneCoverageException("inconsistent motion samples");
            if (i>0) {
                var previous = segments.get(i-1).poseAt(1);
                var next = segment.poseAt(0);
                if (previous.center().distance(next.center())>1e-7
                        || previous.orientation().axisY().distance(next.orientation().axisY())>1e-7
                        || previous.orientation().axisX().distance(next.orientation().axisX())>1e-7)
                    throw new CollisionSceneCoverageException("discontinuous motion samples");
            }
        }
    }
    @Override public long tick() { return segments.get(0).tick(); }
    @Override public long revision() { return segments.get(0).revision(); }
    @Override public long continuityEpoch() { return segments.get(0).continuityEpoch(); }
    @Override public double intervalTicks() { return segments.size()*segments.get(0).intervalTicks(); }
    @Override public int subdivisionMultiple() { return segments.size(); }
    // Nonlinear translation also requires the general curved-path solver.
    @Override public boolean requiresGeneralSweep() { return moving(); }
    @Override public boolean moving() { return segments.stream().anyMatch(RigidTrajectory::moving); }
    private int index(double time) {
        if (!Double.isFinite(time) || time<0 || time>1) throw new CollisionSceneCoverageException("sample time outside capture");
        return Math.min(segments.size()-1, (int)(time*segments.size()));
    }
    private double localTime(double time, int index) { return Math.max(0, Math.min(1,time*segments.size()-index)); }
    @Override public RigidPose poseAt(double time) {
        int i=index(time); return segments.get(i).poseAt(localTime(time,i));
    }
    @Override public Vec3d velocityAt(Vec3d point, double time) {
        int i=index(time); return segments.get(i).velocityAt(point,localTime(time,i));
    }
    @Override public OrientedBox bodyAt(OrientedBox local, double time) {
        int i=index(time); return segments.get(i).bodyAt(local,localTime(time,i));
    }
    @Override public OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observer) {
        int begin=index(lo), end=index(hi);
        if (hi<lo) throw new IllegalArgumentException("reversed sampled interval");
        if (hi==lo) return bodyAt(local,lo);
        // Preserve the segment's oriented proof enclosure when no velocity knot
        // is crossed. An AABB here retains orientation slack as hi approaches lo
        // and prevents CCD refinement from proving grazing clearance.
        if (begin==end || hi==(double)(begin+1)/segments.size())
            return segments.get(begin).envelope(local,localTime(lo,begin),localTime(hi,begin),observer);
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (int i=begin;i<=end;i++) {
            double a=Math.max(lo,(double)i/segments.size()), b=Math.min(hi,(double)(i+1)/segments.size());
            var shift=observer.multiply(-(a-lo)/(hi-lo));
            var box=segments.get(i).envelope(local,localTime(a,i),localTime(b,i),observer.multiply((b-a)/(hi-lo)))
                    .enclosingAabb();
            minX=Math.min(minX,box.minX()+shift.x()); minY=Math.min(minY,box.minY()+shift.y()); minZ=Math.min(minZ,box.minZ()+shift.z());
            maxX=Math.max(maxX,box.maxX()+shift.x()); maxY=Math.max(maxY,box.maxY()+shift.y()); maxZ=Math.max(maxZ,box.maxZ()+shift.z());
        }
        return new OrientedBox(new Vec3d((minX+maxX)*.5,(minY+maxY)*.5,(minZ+maxZ)*.5),
                new Vec3d((maxX-minX)*.5,(maxY-minY)*.5,(maxZ-minZ)*.5),OrthonormalFrame3d.IDENTITY);
    }
    @Override public Vec3d centerDisplacement(double lo,double hi) { return poseAt(hi).center().subtract(poseAt(lo).center()); }
    @Override public double maximumPointDisplacement(OrientedBox local) { return maximumPointSpeed(local)*intervalTicks(); }
    @Override public double maximumPointSpeed(OrientedBox local) { return segments.stream().mapToDouble(s->s.maximumPointSpeed(local)).max().orElseThrow(); }
    @Override public double maximumAngularDerivativeU() { return segments.stream().mapToDouble(RigidTrajectory::maximumAngularDerivativeU).max().orElseThrow()*segments.size(); }
    @Override public double maximumPointSecondDerivativeU(OrientedBox local) {
        return segments.stream().mapToDouble(s->s.maximumPointSecondDerivativeU(local)).max().orElseThrow()*segments.size()*segments.size();
    }
}
