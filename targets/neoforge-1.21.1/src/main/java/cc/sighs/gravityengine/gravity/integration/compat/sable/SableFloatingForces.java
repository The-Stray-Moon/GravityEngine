package cc.sighs.gravityengine.gravity.integration.compat.sable;

import dev.ryanhcode.sable.physics.floating_block.FloatingBlockCluster;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.List;

/** Cluster-centroid quadrature over native floating materials, in native local units. */
public final class SableFloatingForces {
    private SableFloatingForces() {}
    public interface DataAccess {
        double gravityengine$totalScale();
        Vector3d gravityengine$weightedPosition();
    }
    public static Vector3d center(FloatingClusterContainer container, FloatingBlockCluster cluster) {
        var data=(DataAccess)cluster.getBlockData();
        return container.rotationOffset.transform(data.gravityengine$weightedPosition(),new Vector3d())
                .div(data.gravityengine$totalScale()).add(container.positionOffset);
    }
    public static Vector3d gravity(ServerSubLevel body,FloatingClusterContainer container,
            FloatingBlockCluster cluster,Vector3dc nativeLocal,double seconds) {
        var local=center(container,cluster);
        var pose=body.logicalPose();
        var point=pose.transformPosition(new Vector3d(local).add(body.getMassTracker().getCenterOfMass()));
        var data=(DataAccess)cluster.getBlockData();
        var radius=container.rotationOffset.transform(data.gravityengine$weightedPosition(),new Vector3d())
                .div(data.gravityengine$totalScale());
        // Native angular velocity is child-local; radius is already parent-local.
        var angular=container.rotationOffset.transform(container.angularVelocity,new Vector3d());
        var relative=angular.cross(radius).add(container.velocity);
        pose.orientation().transform(relative.mul(pose.scale()));
        var world=pose.orientation().transform(nativeLocal,new Vector3d());
        SableGravityBridge.liftGravity(body,point,relative,world,seconds);
        return pose.orientation().transformInverse(world);
    }
    /** Same native uniform-field self-lift cap, generalized using the
     * lift-weighted mean gravity and center for the sampled cluster forces. */
    public static void lift(ServerSubLevel body,List<FloatingClusterContainer> containers,
            Vector3dc nativeLocal,double seconds,Vector3d linear,Vector3d angular) {
        var cappedForce=new Vector3d();var cappedTorque=new Vector3d();
        var weightedCenter=new Vector3d();var weightedGravity=new Vector3d();
        double weight=0;
        int visited=0;
        for(var container:containers) for(var cluster:container.clusters) {
            if(++visited>SableMassPoints.MAX_POINTS) {
                SableGravityBridge.rejectStep(body);return;
            }
            var material=cluster.getMaterial();var data=cluster.getBlockData();
            double totalScale=((DataAccess)data).gravityengine$totalScale();
            if(material.liftStrength()==0 || totalScale<=0) continue;
            double strength=material.liftStrength()*(material.scaleWithPressure()?data.getPressureScale():1);
            double amount=strength*totalScale;
            var center=center(container,cluster);
            var gravity=gravity(body,container,cluster,nativeLocal,seconds);
            var force=new Vector3d(gravity).mul(-amount);
            var torque=center.cross(force,new Vector3d());
            if(material.preventSelfLift()) {
                weight+=amount;weightedCenter.fma(amount,center);weightedGravity.fma(amount,gravity);
                cappedForce.add(force);cappedTorque.add(torque);
            } else {linear.fma(seconds,force);angular.fma(seconds,torque);}
        }
        if(weight<=0) return;
        weightedCenter.div(weight);weightedGravity.div(weight);
        if(weightedGravity.lengthSquared()==0) {
            // Cancelling sampled gravity can still produce a tidal couple.
            linear.fma(seconds,cappedForce);angular.fma(seconds,cappedTorque);return;
        }
        var acceleration=body.getMassTracker().getInverseInertiaTensor().transform(cappedTorque,new Vector3d())
                .cross(weightedCenter).fma(1/body.getMassTracker().getMass(),cappedForce);
        double projection=weightedGravity.dot(acceleration);
        double scale=projection<0?Math.min(1,-weightedGravity.lengthSquared()/projection):1;
        linear.fma(seconds*scale,cappedForce);angular.fma(seconds*scale,cappedTorque);
    }
}
