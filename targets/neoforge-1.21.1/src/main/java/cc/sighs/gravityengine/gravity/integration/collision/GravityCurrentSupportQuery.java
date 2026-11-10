package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/** Invocation-local evidence on installed geometry. Never publishes ground or transport state. */
public final class GravityCurrentSupportQuery {
    private GravityCurrentSupportQuery() {}

    public record Capture(GravityFrame frame, CollisionBody body, CollisionScene scene,
                          ObbQueryContext context) {
        public Optional<RestingContactSnapshot> stableSupport(Entity entity) {
            var result = StepStartSupportQuery.query(body, frame, scene, context,
                    MinecraftMathAdapter.toVec3d(entity.getDeltaMovement()),
                    entity.level().getGameTime(), null);
            return result.indeterminate() || context.workTracker().limitExceeded()
                    || context.supportWorkTracker().limitExceeded()
                    ? Optional.empty() : result.support();
        }
    }

    public static Capture capture(Entity entity) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        var operation = runtime.collisionOperation();
        var frame = operation == null ? GravityFrameAccess.installedLocomotionFrame(entity) : operation.frame();
        var body = GravityEntityGeometry.body(entity);
        var context = operation == null ? new ObbQueryContext() : operation.geometryContext();
        long tick = entity.level().getGameTime();
        var scene = operation == null
                ? MinecraftCollisionSceneCapture.capture(entity,
                        CollisionCaptureDomain.forTransition(body, entity.maxUpStep()), tick, 0,
                        KinematicStepContext.fullTick(tick, 0), context.workTracker())
                : operation.scene();
        return new Capture(frame, body, scene, context);
    }

    public static Optional<RestingContactSnapshot> stableSupport(Entity entity) {
        try {
            return capture(entity).stableSupport(entity);
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return Optional.empty();
        }
    }
}
