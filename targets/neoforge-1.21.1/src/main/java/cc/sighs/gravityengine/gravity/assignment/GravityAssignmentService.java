package cc.sighs.gravityengine.gravity.assignment;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationService;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Evaluates composed source gravity and classifies destination-world evidence.
 * It may bind/reset transient reconciliation, but never replaces durable
 * assignment, commits an application, or synchronizes an entity.
 *
 * <p>The resolved {@link GravityState} is assignment/bootstrap and network
 * compatibility state. The returned {@link GravityFieldEvaluation} is raw
 * field evidence for assignment resolution, not a physical runtime snapshot.</p>
 */
public final class GravityAssignmentService {
    private static final double ACCELERATION_EPSILON_SQUARED = 1.0E-12D;

    private GravityAssignmentService() {}

    public record AssignmentResult(
            GravityState previous,
            GravityState resolved,
            boolean fieldPresent,
            boolean changed,
            boolean authoritative,
            GravityFieldEvaluation fieldEvaluation
    ) {
        public AssignmentResult {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(resolved, "resolved");
            Objects.requireNonNull(
                    fieldEvaluation,
                    "fieldEvaluation"
            );
        }
    }

    public static AssignmentResult evaluate(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        var access = GravityEntityAccess.cast(entity);
        var component = access.gravityengine$gravityComponent();
        var fields = GravityFieldRuntime.get(entity.level());
        boolean invalidated = component.bindFieldRuntime(fields);
        GravityState previous = component.state().assignedState();
        boolean previousFieldPresent = component.state().assignedFieldPresent();
        boolean previousUnknown = component.state().fieldEvidenceUnknown();
        AccelerationQuery query = accelerationQuery(entity);

        GravityFieldEvaluation fieldEvaluation =
                GravityEvaluationService.sampleFieldEvaluation(
                        query,
                        fields
                );

        GravitySample sample = fieldEvaluation.sample();
        GravityState resolved = resolve(previous, sample);
        boolean fieldPresent = sample.hasActiveField();
        boolean authoritative = !entity.isRemoved()
                && fieldEvaluation.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
        if (!authoritative) resolved = previous;

        return new AssignmentResult(
                previous,
                resolved,
                fieldPresent,
                invalidated || authoritative && (!previous.sameSyncData(resolved)
                        || previousFieldPresent != fieldPresent
                        || previousUnknown),
                authoritative,
                fieldEvaluation
        );
    }

    /**
     * Complete physics query for the current authoritative assignment sample.
     */
    public static AccelerationQuery accelerationQuery(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        var installedUp = component.operationState()
                .installedCollisionUp();
        Vec3 samplePoint = installedUp == null
                ? GravityEntityGeometry.proxyCenter(entity)
                : GravityEntityGeometry.bodyCenter(entity);

        return new AccelerationQuery(
                MinecraftMathAdapter.toVec3d(samplePoint),
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement()
                ),
                entity.level().getGameTime(),
                1.0D,
                component.state().appliedState()
        );
    }

    /**
     * Builds physical runtime truth from a completed assignment sample and the
     * actually committed application context.
     */
    public static GravityEvaluationSnapshot physicalEvaluation(
            Entity entity,
            AssignmentResult result
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(result, "result");
        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        var runtime = component.operationState();
        /*
         * Assignment/application may have committed a geometry transition
         * after the raw field sample. Re-read the actual committed query so
         * the physical snapshot describes where the entity now is, not the
         * pre-transition evaluation point.
         */
        AccelerationQuery physicalQuery = accelerationQuery(entity);

        var registry = GravityFieldRuntime.get(entity.level());
        var context = GravityEvaluationContexts.capture(
                component.state(),
                registry
        );

        return GravityEvaluationService.evaluateCommitted(
                context,
                registry,
                physicalQuery,
                result.fieldEvaluation(),
                component.state().appliedState().down(),
                runtime.lastCompletedFrame()
        );
    }

    public static GravityState resolve(GravityState previous, GravitySample sample) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(sample, "sample");

        if (sample.contributions().isEmpty()) {
            return GravityState.DEFAULT;
        }

        cc.sighs.gravityengine.api.math.Vec3d acceleration =
                sample.accelerationVector();
        double magnitudeSquared = acceleration.lengthSquared();
        if (magnitudeSquared >= ACCELERATION_EPSILON_SQUARED) {
            return new GravityState(
                    acceleration.multiply(
                            1.0D / Math.sqrt(magnitudeSquared)),
                    Math.sqrt(magnitudeSquared)
            );
        }

        // Active sources cancel. Preserve the previous down direction while
        // publishing zero force. Full basis preservation belongs to the
        // runtime-frame phase.
        return new GravityState(previous.down(), 0.0D);
    }
}
