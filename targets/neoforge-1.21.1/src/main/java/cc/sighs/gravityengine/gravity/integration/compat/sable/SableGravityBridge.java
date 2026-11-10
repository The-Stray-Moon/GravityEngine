package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.api.math.Vec3d;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.Map;
import java.util.WeakHashMap;

/** Replaces Sable's uniform baseline with complete GE field evidence at its mass points.
 * Sable retains all rigid mass, velocity, pose and integration authority. */
public final class SableGravityBridge {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SableGravityBridge.class);
    private static final Map<SubLevelPhysicsSystem, Vec3d> BASELINES = new WeakHashMap<>();
    private SableGravityBridge() {}

    /** Native body lifetime owns this transient step, never a Level registry. */
    public interface StepAccess {
        ForceStep gravityengine$forceStep();
        void gravityengine$forceStep(ForceStep step);
    }
    public static final class ForceStep {
        private final Pose3d pose;
        private final dev.ryanhcode.sable.api.physics.mass.MassData mass;
        private final double massValue;
        private final Vector3d center;
        private final org.joml.Matrix3d inverseInertia;
        private final Vector3d linearVelocity, angularVelocity;
        private final double seconds;
        private final long tick;
        private final Vector3d linear = new Vector3d(), angular = new Vector3d();
        private final Map<cc.sighs.gravityengine.api.field.GravityFieldQuery,
                cc.sighs.gravityengine.api.ComposedGravitySample> samples = new java.util.HashMap<>();
        private boolean complete = true;
        private ForceStep(ServerSubLevel body, double seconds) {
            this.pose = new Pose3d(body.logicalPose()); this.mass = body.getMassTracker();
            this.massValue=mass.getMass(); this.center=new Vector3d(mass.getCenterOfMass());
            this.inverseInertia=new org.joml.Matrix3d(mass.getInverseInertiaTensor());
            var handle=SubLevelPhysicsSystem.require(body.getLevel()).getPhysicsHandle(body);
            this.linearVelocity=handle.getLinearVelocity(new Vector3d());
            this.angularVelocity=handle.getAngularVelocity(new Vector3d());
            this.seconds = seconds; this.tick = body.getLevel().getGameTime();
        }
    }
    public static void beginStep(ServerSubLevel body, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("invalid force step");
        discardStep(body);
        if(!Boolean.parseBoolean(System.getProperty("gravityengine.sableGravity","true"))
                || body.getMassTracker()==null || body.getMassTracker().isInvalid()) return;
        ((StepAccess)body).gravityengine$forceStep(new ForceStep(body, seconds));
    }
    public static void discardStep(ServerSubLevel body) { ((StepAccess)body).gravityengine$forceStep(null); }
    public static boolean hasStep(ServerSubLevel body) { return ((StepAccess)body).gravityengine$forceStep()!=null; }
    public static void rejectStep(ServerSubLevel body) {
        var step=((StepAccess)body).gravityengine$forceStep();
        if(step!=null) step.complete=false;
    }
    public static void stageLocalImpulse(ServerSubLevel body, org.joml.Vector3dc linear, org.joml.Vector3dc angular) {
        var step=((StepAccess)body).gravityengine$forceStep();
        if(step==null) throw new IllegalStateException("GE lift correction outside native force step");
        step.linear.add(linear); step.angular.add(angular);
        if(!finite(step.linear)||!finite(step.angular)) step.complete=false;
    }

    /** One native lift calculation owns one complete FIELD query; absence keeps its native operand. */
    public static Vector3d liftGravity(ServerSubLevel body, org.joml.Vector3dc point,
                                       Vector3d nativeGravity, double seconds) {
        return liftGravity(body,point,new Vector3d(),nativeGravity,seconds);
    }
    public static Vector3d liftGravity(ServerSubLevel body, org.joml.Vector3dc point,
                                       org.joml.Vector3dc relativeVelocitySeconds, Vector3d nativeGravity, double seconds) {
        if (!Boolean.parseBoolean(System.getProperty("gravityengine.sableGravity", "true"))) return nativeGravity;
        if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("invalid lift step");
        var system = SubLevelPhysicsSystem.require(body.getLevel());
        var handle = system.getPhysicsHandle(body);
        var step=((StepAccess)body).gravityengine$forceStep();
        var center=body.logicalPose().transformPosition(body.getMassTracker().getCenterOfMass(),new Vector3d());
        var radius = new Vector3d(point).sub(center);
        var velocity = (step==null?handle.getAngularVelocity(new Vector3d()):new Vector3d(step.angularVelocity)).cross(radius)
                .add(step==null?handle.getLinearVelocity(new Vector3d()):step.linearVelocity).add(relativeVelocitySeconds).div(20);
        var query = new cc.sighs.gravityengine.api.field.GravityFieldQuery(new Vec3d(point.x(), point.y(), point.z()),
                new Vec3d(velocity.x, velocity.y, velocity.z), body.getLevel().getGameTime(), seconds * 20);
        if(step!=null && (step.tick!=query.gameTick() || step.seconds!=seconds)) {
            step.complete=false; return nativeGravity;
        }
        var sample=sample(body,query);
        if(sample==null) return nativeGravity;
        if (sample.coverage() == FieldCoverage.COMPLETE && sample.fieldPresent()) {
            var acceleration = sample.acceleration().multiply(400);
            nativeGravity.set(acceleration.x(), acceleration.y(), acceleration.z());
        }
        return nativeGravity;
    }
    private static cc.sighs.gravityengine.api.ComposedGravitySample sample(ServerSubLevel body,
            cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        var step=((StepAccess)body).gravityengine$forceStep();
        var cache=step==null?null:step.samples;
        var sample=cache==null?null:cache.get(query);
        if(sample==null) {
            if(cache!=null && cache.size()>=SableMassPoints.MAX_POINTS) {
                if(step!=null) step.complete=false;
                return null;
            }
            sample=GravityEngineApi.sample(body.getLevel(),query.position(),query.velocity(),query.gameTick(),query.intervalTicks());
            if(cache!=null) cache.put(query,sample);
        }
        if(step!=null && sample.coverage()!=FieldCoverage.COMPLETE) step.complete=false;
        return sample;
    }

    public static void initialized(SubLevelPhysicsSystem system, double x, double y, double z) {
        BASELINES.put(system, new Vec3d(x,y,z));
    }

    public static Vec3d correctiveVelocityIncrement(Vec3d accelerationTicks, Vec3d baselineSeconds,
                                                    double seconds) {
        if (!accelerationTicks.isFinite() || !baselineSeconds.isFinite()
                || !Double.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("invalid Sable gravity inputs");
        return accelerationTicks.multiply(400).subtract(baselineSeconds).multiply(seconds);
    }

    public static void apply(ServerSubLevel body, SubLevelPhysicsSystem system,
                             RigidBodyHandle handle, double seconds) {
        try { applyCaptured(body,system,handle,seconds); }
        finally { discardStep(body); }
    }
    private static void applyCaptured(ServerSubLevel body, SubLevelPhysicsSystem system,
                             RigidBodyHandle handle, double seconds) {
        if (!Boolean.parseBoolean(System.getProperty("gravityengine.sableGravity", "true"))) return;
        var step=((StepAccess)body).gravityengine$forceStep();
        if(step!=null && (!step.complete || step.seconds!=seconds || step.tick!=body.getLevel().getGameTime()
                || step.mass!=body.getMassTracker() || step.massValue!=step.mass.getMass()
                || !step.center.equals(step.mass.getCenterOfMass()) || !step.inverseInertia.equals(step.mass.getInverseInertiaTensor())
                || !samePose(step.pose,body.logicalPose()))) return;
        var mass = body.getMassTracker();
        if (mass == null || mass.isInvalid()) return;
        var baseline = BASELINES.get(system);
        if (baseline == null) throw new IllegalStateException("Sable physics baseline was not captured");
        if (!Double.isFinite(seconds) || seconds <= 0 || !baseline.isFinite())
            throw new IllegalArgumentException("invalid Sable gravity step");
        var pose = new Pose3d(body.logicalPose());

        var captures = new java.util.ArrayList<MassCapture>();
        var self = capture(body.getSelfMassTracker(), null, new Vector3d(), new Vector3d(), null, 0);
        if (self == null) return;
        captures.add(self);
        int count = self.points().size();
        double phase = (float)system.getPartialPhysicsTick(); // same operand as updateMergedMassData
        for (var contraption : body.getPlot().getContraptions()) {
            if (!contraption.sable$isValid()) return;
            // Match Sable's kinematic velocity operands (also used by floating-block drag).
            var previousPosition = new Vector3d(contraption.sable$getPosition(phase - 1));
            var previousOrientation = new Quaterniond(contraption.sable$getOrientation(phase - 1));
            var childPose = new Pose3d(contraption.sable$getLocalPose(new Pose3d(), phase));
            var childVelocity = new Vector3d(contraption.sable$getPosition(phase)).sub(previousPosition).mul(20);
            var childAngular = dev.ryanhcode.sable.util.SableMathUtils.getAngularVelocity(previousOrientation,
                    new Quaterniond(contraption.sable$getOrientation(phase)), new Vector3d()).mul(20);
            // Quaternion difference is expressed in the child's local axes.
            previousOrientation.transform(childAngular);
            var child = capture(contraption.sable$getMassTracker(), childPose, childVelocity, childAngular, contraption, phase);
            if (child == null || (count += child.points().size()) > SableMassPoints.MAX_POINTS) return;
            captures.add(child);
        }
        double nativeMass = mass.getMass();
        var nativeCenter = new Vector3d(mass.getCenterOfMass());
        var nativeInertia = new org.joml.Matrix3d(mass.getInverseInertiaTensor());
        var worldCenter = pose.transformPosition(nativeCenter, new Vector3d());
        var linearVelocity = step==null?handle.getLinearVelocity(new Vector3d()):step.linearVelocity;
        var angularVelocity = step==null?handle.getAngularVelocity(new Vector3d()):step.angularVelocity;
        var netForce = new Vector3d();
        var netTorque = new Vector3d();
        var weightedCenter = new Vector3d();
        double capturedMass = 0;

        for (var capture : captures) {
            for (var point : capture.points()) {
                double blockMass = point.mass();
                var p = point.position();
                var localPoint = new Vector3d(p.x(), p.y(), p.z());
                if (capture.localPose() != null) capture.localPose().transformPosition(localPoint);
                weightedCenter.fma(blockMass, localPoint);
                capturedMass += blockMass;
                var worldPoint = pose.transformPosition(localPoint, new Vector3d());
                var radius = worldPoint.sub(worldCenter, new Vector3d());
                var pointVelocity = new Vector3d(angularVelocity).cross(radius).add(linearVelocity);
                if (capture.localPose() != null) {
                    var childRadius = new Vector3d(localPoint).sub(capture.localPose().position());
                    var relative = new Vector3d(capture.angularVelocity()).cross(childRadius).add(capture.linearVelocity());
                    pointVelocity.add(pose.orientation().transform(relative.mul(pose.scale())));
                }
                var sample = sample(body,new cc.sighs.gravityengine.api.field.GravityFieldQuery(
                        new Vec3d(worldPoint.x, worldPoint.y, worldPoint.z),
                        new Vec3d(pointVelocity.x, pointVelocity.y, pointVelocity.z).divide(20),
                        body.getLevel().getGameTime(), seconds * 20));
                if (sample==null || sample.coverage() != FieldCoverage.COMPLETE) return;
                if (!sample.fieldPresent()) continue;
                var difference = sample.acceleration().multiply(400).subtract(baseline);
                var force = new Vector3d(difference.x(), difference.y(), difference.z()).mul(blockMass);
                netForce.add(force);
                netTorque.add(new Vector3d(radius).cross(force));
            }
        }
        if (!(capturedMass > 0) || !Double.isFinite(capturedMass)) return;
        weightedCenter.div(capturedMass);
        if (!sameMass(capturedMass, nativeMass) || weightedCenter.distance(nativeCenter) > 1.0E-7
                || mass != body.getMassTracker() || mass.getMass() != nativeMass
                || !nativeCenter.equals(mass.getCenterOfMass())
                || !nativeInertia.equals(mass.getInverseInertiaTensor())
                || !samePose(pose, body.logicalPose())
                || captures.stream().anyMatch(c -> !c.unchanged())) return;

        // Sable 2.0.6 Rapier has no usable inverse mass before a new body's first
        // step. Its velocity increment API works then and accepts world vectors.
        var deltaV = netForce.mul(seconds / nativeMass);
        var orientation = new Quaterniond(pose.orientation()).normalize();
        var localTorqueImpulse = orientation.conjugate(new Quaterniond()).transform(netTorque.mul(seconds), new Vector3d());
        if(step!=null) {
            deltaV.add(orientation.transform(new Vector3d(step.linear).div(nativeMass)));
            localTorqueImpulse.add(step.angular);
        }
        var localDeltaOmega = nativeInertia.transform(localTorqueImpulse, new Vector3d());
        var deltaOmega = orientation.transform(localDeltaOmega, new Vector3d());
        if (!finite(deltaV) || !finite(deltaOmega)) {
            LOGGER.warn("Sable gravity produced non-finite velocity correction for {}", body.getUniqueId());
            return;
        }
        if (deltaV.lengthSquared() > 0 || deltaOmega.lengthSquared() > 0)
            handle.addLinearAndAngularVelocity(deltaV, deltaOmega);
    }

    private record MassCapture(MassTracker tracker, java.util.List<SableMassPoints.Point> points,
                               double mass, Vector3d center, org.joml.Matrix3d inverseInertia, Pose3d localPose,
                               Vector3d linearVelocity, Vector3d angularVelocity,
                               dev.ryanhcode.sable.api.sublevel.KinematicContraption child, double phase) {
        boolean unchanged() {
            return ((SableMassPoints.Access)tracker).gravityengine$massPoints().snapshot() == points
                    && tracker.getMass() == mass && center.equals(tracker.getCenterOfMass())
                    && inverseInertia.equals(tracker.getInverseInertiaTensor())
                    && (child == null || child.sable$isValid() && child.sable$getMassTracker() == tracker
                        && samePose(localPose, child.sable$getLocalPose(new Pose3d(), phase)));
        }
    }
    private static MassCapture capture(MassTracker tracker, Pose3d localPose, Vector3d linear, Vector3d angular,
                                       dev.ryanhcode.sable.api.sublevel.KinematicContraption child, double phase) {
        if (tracker == null || tracker.isInvalid()) return null;
        var points = ((SableMassPoints.Access)tracker).gravityengine$massPoints().snapshot();
        if (points == null) return null;
        double mass = 0;
        var center = new Vector3d();
        for (var point : points) {
            mass += point.mass();
            var p = point.position();
            center.fma(point.mass(), new Vector3d(p.x(), p.y(), p.z()));
        }
        if (!(mass > 0) || !sameMass(mass, tracker.getMass())) return null;
        center.div(mass);
        if (center.distance(tracker.getCenterOfMass()) > 1e-7) return null;
        return new MassCapture(tracker, points, tracker.getMass(), new Vector3d(tracker.getCenterOfMass()),
                new org.joml.Matrix3d(tracker.getInverseInertiaTensor()), localPose, linear, angular, child, phase);
    }

    private static boolean samePose(Pose3d a, Pose3d b) {
        return a.position().equals(b.position()) && a.orientation().equals(b.orientation())
                && a.rotationPoint().equals(b.rotationPoint()) && a.scale().equals(b.scale());
    }

    private static boolean sameMass(double a, double b) {
        return Double.isFinite(b) && Math.abs(a - b) <= Math.max(1.0E-8, Math.abs(b) * 1.0E-8);
    }

    private static boolean finite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

}
