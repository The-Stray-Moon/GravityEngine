package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Vector3d;
import java.util.List;

/** Prescribed rotating OBB versus a translating capsule. Conservative envelopes
 * prove empty intervals; dedicated cotemporal capsule/OBB pairs supply contacts. */
final class CapsuleRigidObstacleSweep {
    private final CharacterCapsule body;
    private final Vector3d movement;
    private final EntityObstacle obstacle;
    private final double start, duration, speed;
    private final SweepInitialState initial;
    private int tests;
    private CapsuleRigidObstacleSweep(CharacterCapsule body, Vector3d movement, EntityObstacle obstacle,
            double start, double duration, SweepInitialState initial) {
        this.body=body; this.movement=movement; this.obstacle=obstacle;
        this.start=start; this.duration=duration; this.initial=initial;
        speed=movement.length()+duration*obstacle.motion().intervalTicks()
                *obstacle.motion().maximumPointSpeed(obstacle.snapshot().localBody());
    }
    static CapsuleAabbCollision.ContactGeometry query(CharacterCapsule body, OrientedBox box) {
        var local=CapsuleAabbCollision.contactGeometry(CollisionNarrowPhase.capsuleInObbLocalSpace(body,box),
                CollisionNarrowPhase.localBounds(box));
        return new CapsuleAabbCollision.ContactGeometry(box.localPointToWorld(local.pointOnSegment(),new Vector3d()),
                box.localPointToWorld(local.pointOnObstacle(),new Vector3d()),
                box.localVectorToWorld(local.normal(),new Vector3d()).normalize(),local.signedGap(),local.penetration());
    }
    static SweepContactResult sweep(CharacterCapsule body, Vector3d movement, EntityObstacle obstacle,
            double start, double duration) {
        var pair=query(body,obstacle.bodyAt(start));
        var initial=CapsuleCapsuleCollision.classify(pair.signedGap());
        var solve=new CapsuleRigidObstacleSweep(body,movement,obstacle,start,duration,initial);
        if(initial==SweepInitialState.OVERLAPPING) return solve.unknown();
        if(initial==SweepInitialState.TOUCHING) {
            var relative=new Vector3d(movement).fma(-duration*obstacle.motion().intervalTicks(),
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
        var atLo=body.move(new Vector3d(movement).mul(lo));
        var envelope=obstacle.motion().envelope(obstacle.snapshot().localBody(),time(lo),time(hi),
                new Vector3d(movement).mul(hi-lo));
        if(query(atLo,envelope).signedGap() >= 0) return new SweepContactResult(initial,List.of());
        double uncertainty=speed*(hi-lo);
        if(depth>=28 || hi-lo<=CollisionTolerances.TOI_EPSILON && uncertainty<=CollisionTolerances.CONTACT_SKIN) {
            if(uncertainty>CollisionTolerances.CONTACT_SKIN) return unknown();
            var exact=query(body.move(new Vector3d(movement).mul(hi)),obstacle.bodyAt(time(hi)));
            if(exact.signedGap()>CollisionTolerances.CONTACT_SLOP) return unknown();
            return contact(exact,hi,lo);
        }
        double mid=(lo+hi)*.5;
        var first=refine(lo,mid,depth+1);
        return first.indeterminate() || first.hasBlockingContacts() ? first : refine(mid,hi,depth+1);
    }
}
