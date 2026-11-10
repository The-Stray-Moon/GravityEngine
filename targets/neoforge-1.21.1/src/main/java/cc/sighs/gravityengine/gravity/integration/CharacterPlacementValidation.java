package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Invocation-local dismount clearance. Vehicle transforms and native candidate
 * selection retain ownership; only the installed physical collider is tested. */
public final class CharacterPlacementValidation {
    private CharacterPlacementValidation() {}
    private static final ThreadLocal<DismountSelection> DISMOUNT=new ThreadLocal<>();
    /** Native stopRiding already owns passenger removal. Only speculative pose
     * selection belongs to this scope; rejection must not remount a removed seat. */
    public static final class DismountSelection implements AutoCloseable {
        private final LivingEntity actor;
        private final net.minecraft.world.entity.Pose pose;
        private final DismountSelection previous;
        private DismountSelection(LivingEntity actor) {
            this.actor=actor;this.pose=actor.getPose();this.previous=DISMOUNT.get();DISMOUNT.set(this);
        }
        public void restorePose() { actor.setPose(pose); }
        @Override public void close() {
            if(DISMOUNT.get()!=this) throw new IllegalStateException("dismount scopes closed out of order");
            if(previous==null) DISMOUNT.remove();else DISMOUNT.set(previous);
        }
    }
    public static DismountSelection beginDismountSelection(LivingEntity actor) { return new DismountSelection(actor); }
    public static void rejectDismountPlacement(LivingEntity actor) {
        var selection=DISMOUNT.get();
        if(selection!=null && selection.actor==actor) selection.restorePose();
    }
    public static boolean owns(LivingEntity actor) {
        return !actor.noPhysics && (GravityInfluencePolicy.usesExactBodyCollision(actor)
                || GravityInfluencePolicy.hasExternalCollisionProviders(actor));
    }
    public static boolean clearAt(LivingEntity actor,Vec3 nativeAnchor) {
        var anchor=SableMovementCompatibility.projectOut(actor,nativeAnchor);
        var body=GravityEntityGeometry.body(actor).move(MinecraftMathAdapter.toVec3d(anchor.subtract(actor.position())));
        return clearBody(actor,body);
    }
    /** Native pose enumeration supplies dimensions that may differ from the
     * currently installed body. Preserve them rather than validating a seated
     * body and later accepting a standing pose on that evidence. */
    public static boolean clearCandidate(net.minecraft.world.level.CollisionGetter level, LivingEntity actor,
                                         net.minecraft.world.phys.AABB candidate) {
        if(level!=actor.level()) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,
                    new CollisionSceneCoverageException("dismount candidate uses an unsupported collision world"));
            return false;
        }
        var anchor=SableMovementCompatibility.projectOut(actor,new Vec3((candidate.minX+candidate.maxX)*.5,
                candidate.minY,(candidate.minZ+candidate.maxZ)*.5));
        if(!(candidate.getXsize()>0 && candidate.getYsize()>0)
                || Math.abs(candidate.getXsize()-candidate.getZsize())>1e-7) return false;
        CollisionBody body;
        if(GravityInfluencePolicy.usesExactBodyCollision(actor)) {
            body=GravityEntityGeometry.candidateBody(net.minecraft.world.entity.EntityDimensions.scalable(
                    (float)candidate.getXsize(),(float)candidate.getYsize()),anchor,GravityEntityGeometry.installedUp(actor));
        } else {
            var shifted=candidate.move(anchor.x-(candidate.minX+candidate.maxX)*.5,anchor.y-candidate.minY,
                    anchor.z-(candidate.minZ+candidate.maxZ)*.5);
            body=new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(
                    MinecraftMathAdapter.toVec3d(shifted.getCenter()),
                    new cc.sighs.gravityengine.api.math.Vec3d(shifted.getXsize()/2,shifted.getYsize()/2,shifted.getZsize()/2),
                    cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        }
        var b=body.enclosingAabb();
        return level.getWorldBorder().isWithinBounds(new net.minecraft.world.phys.AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()))
                && clearBody(actor,body);
    }
    private static boolean clearBody(LivingEntity actor,CollisionBody body) {
        var bounds=body.enclosingAabb().inflate(CollisionTolerances.CONTACT_SKIN*2);
        var tracker=new CollisionWorkTracker(CollisionWorkBudget.defaults());
        var time=KinematicStepContext.fullTick(actor.level().getGameTime(),0);
        try {
            var scene=MinecraftCollisionSceneCapture.capture(actor,new CollisionCaptureDomain(bounds,bounds),
                    time.gameTick(),0,time,tracker);
            var context=new ObbQueryContext();context.setWorkTracker(tracker);
            var contacts=CurrentContactQuery.contacts(body,scene,1,context);
            return !tracker.limitExceeded() && !scene.diagnostics().limitExceeded()
                    && !contacts.indeterminate() && !CurrentContactQuery.hasMeaningfulPenetration(contacts);
        } catch(CollisionSceneCoverageException | CollisionComplexityLimitException unavailable) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,unavailable);
            return false;
        }
    }
}
