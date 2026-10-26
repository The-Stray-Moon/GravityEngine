package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import java.util.List;

/** Prescribed rotating OBB versus a translating capsule. Conservative envelopes
 * prove empty intervals; dedicated cotemporal capsule/OBB pairs supply contacts. */
final class CapsuleRigidObstacleSweep {
    private final CharacterCapsule body;
    private final Vec3d movement;
    private final EntityObstacle obstacle;
    private final double start, duration, speed;
    private final SweepInitialState initial;
    private int tests;
    private CapsuleRigidObstacleSweep(CharacterCapsule body, Vec3d movement, EntityObstacle obstacle,
            double start, double duration, SweepInitialState initial) {
        this.body=body; this.movement=movement; this.obstacle=obstacle;
        this.start=start; this.duration=duration; this.initial=initial;
        speed=movement.length()+duration*obstacle.motion().intervalTicks()
                *obstacle.motion().maximumPointSpeed(obstacle.snapshot().localBody());
    }
    static CapsuleAabbCollision.ContactGeometry query(CharacterCapsule body, OrientedBox box) {
        var local=CapsuleAabbCollision.contactGeometry(CollisionNarrowPhase.capsuleInObbLocalSpace(body,box),
                CollisionNarrowPhase.localBounds(box));
        return new CapsuleAabbCollision.ContactGeometry(
                box.localPointToWorld(local.pointOnSegment()),
                box.localPointToWorld(local.pointOnObstacle()),
                box.localVectorToWorld(local.normal()).normalized(),
                local.signedGap(),
                local.penetration());
    }
    static SweepContactResult sweep(CharacterCapsule body, Vec3d movement, EntityObstacle obstacle,
            double start, double duration) {
        var pair=query(body,obstacle.bodyAt(start));
        var initial=CapsuleCapsuleCollision.classify(pair.signedGap());
        var solve=new CapsuleRigidObstacleSweep(body,movement,obstacle,start,duration,initial);
        if(initial==SweepInitialState.OVERLAPPING) return solve.unknown();
        // The rigid body's projection on its rotation axis is invariant.
        // A separating plane there plus non-entering relative translation
        // proves the complete interval clear, including tangent floor carry.
        var single = obstacle.motion().invariantAxisMotion();
        if (single != null) {
            Vec3d axis = new Vec3d(single.ax(), single.ay(), single.az()).normalized();
            var box = obstacle.bodyAt(start);
            double separation = body.center().subtract(box.center()).dot(axis);
            double capsuleRadius = body.radius() + body.halfSegmentLength() * Math.abs(body.axis().dot(axis));
            double boxRadius = box.halfExtents().x() * Math.abs(box.orientation().axisX().dot(axis))
                    + box.halfExtents().y() * Math.abs(box.orientation().axisY().dot(axis))
                    + box.halfExtents().z() * Math.abs(box.orientation().axisZ().dot(axis));
            double sign = separation < 0 ? -1 : 1;
            if (Math.abs(separation) - capsuleRadius - boxRadius >= -cc.sighs.gravityengine.math.geometry.GeometryTolerance.TOUCHING
                    && movement.subtract(single.displacementForInterval().multiply(duration)).dot(axis) * sign >= 0)
                return new SweepContactResult(initial, List.of());
        }
        if(initial==SweepInitialState.TOUCHING) {
            var relative=movement.fma(-duration*obstacle.motion().intervalTicks(),
                    obstacle.velocityAt(pair.pointOnObstacle(),start));
            if(relative.dot(pair.normal()) < -CollisionTolerances.ENTERING_PLANE_EPSILON)
                return solve.contact(pair,0,0);
        }
        return solve.refine(0,1,0);
    }
    private double time(double t) { return Math.min(1,start+duration*t); }
    private SweepContactResult unknown() { return new SweepContactResult(initial,List.of(),true); }
    private SweepContactResult contact(CapsuleAabbCollision.ContactGeometry pair,double at,double toi) {
        var p=pair.pointOnObstacle();
        return new SweepContactResult(initial,List.of(new CollisionContact(obstacle,p,pair.normal(),0,toi,
                obstacle.velocityAt(p,time(at)),time(at))));
    }
    private SweepContactResult refine(double lo,double hi,int depth) {
        if(++tests>1024) return unknown();
        var atLo=body.move(movement.multiply(lo));
        var envelope=obstacle.motion().envelope(obstacle.snapshot().localBody(),time(lo),time(hi),
                movement.multiply(hi-lo));
        if(query(atLo,envelope).signedGap() >= 0) return new SweepContactResult(initial,List.of());
        double uncertainty=speed*(hi-lo);
        if(depth>=28 || hi-lo<=CollisionTolerances.TOI_EPSILON && uncertainty<=CollisionTolerances.CONTACT_SKIN) {
            if(uncertainty>CollisionTolerances.CONTACT_SKIN) return unknown();
            var exact=query(body.move(movement.multiply(hi)),obstacle.bodyAt(time(hi)));
            if(exact.signedGap()>CollisionTolerances.CONTACT_SLOP) return unknown();
            return contact(exact,hi,lo);
        }
        double mid=(lo+hi)*.5;
        var first=refine(lo,mid,depth+1);
        return first.indeterminate() || first.hasBlockingContacts() ? first : refine(mid,hi,depth+1);
    }
}
